package com.ddm.argus.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTagsForResourceRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.DeregisterInstanceRequest;
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.StreamSupport;

/**
 * LaneRegistrar（统一版 / 零环境变量 / 高内聚封装）
 *
 * 功能：
 *  1) 仅在 ECS/Fargate 容器环境运行（依赖 ECS metadata v4 环境变量）。
 *  2) 启动时自动：
 *     - 读取 task/container 元数据 -> clusterArn, taskArn, container IP, 端口
 *     - ECS DescribeTasks -> 拿到 serviceName (来自 group: "service:<name>")
 *     - ECS DescribeServices -> 拿到 Cloud Map registryArn -> 解析 serviceId (srv-xxxx)
 *     - 计算 lane：
 *         a) 优先从 ECS Service 标签 key=lane（忽略大小写）
 *         b) 否则取 serviceName 最后一个 '-' 后缀
 *         c) 否则用 taskId 兜底
 *     - 调 Cloud Map RegisterInstance（幂等），写入属性：
 *         AWS_INSTANCE_IPV4 / AWS_INSTANCE_PORT / lane
 *  3) 支持可选的优雅下线：在 @PreDestroy 时 DeregisterInstance（可按需开启/关闭）。
 *
 * 设计要点：
 *  - 无外部环境变量强依赖；Region 优先从 clusterArn 解析，其次看 AWS_REGION，最后 us-east-1。
 *  - 所有 I/O（metadata/SDK）都有超时；注册有指数退避重试。
 *  - 日志包含完整上下文，出问题“一眼看到底”。
 *
 * 需要权限（运行在“任务角色”里）：
 *  - ecs:DescribeTasks / ecs:DescribeServices / ecs:ListTagsForResource
 *  - servicediscovery:RegisterInstance / servicediscovery:DeregisterInstance / servicediscovery:ListInstances（可选）
 */
@Component
public class LaneBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LaneBootstrap.class);

    // ======================== 常量与可调项 ========================
    private static final String META_ENV = "ECS_CONTAINER_METADATA_URI_V4";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);   // 拉取 metadata
    private static final Duration SDK_TIMEOUT  = Duration.ofSeconds(5);   // 调 AWS API
    private static final int      DEFAULT_APP_PORT = 9091;                // 兜底端口

    private static final String ATTR_IPV4 = "AWS_INSTANCE_IPV4";
    private static final String ATTR_PORT = "AWS_INSTANCE_PORT";
    private static final String ATTR_LANE = "lane";

    private static final boolean ENABLE_DEREGISTER_ON_SHUTDOWN = false;   // 如需下线，改为 true

    // ======================== 运行时缓存（用于下线） ========================
    private volatile String cachedServiceId;
    private volatile String cachedInstanceId;
    private volatile Region cachedRegion;

    // ======================== 生命周期入口 ========================
    @PostConstruct
    public void init() {
        final String metaBase = System.getenv(META_ENV);
        if (metaBase == null || metaBase.isBlank()) {
            // 非 ECS 容器（本地/单测环境）直接跳过
            log.info("LaneRegistrar: no ECS metadata v4 env; skip registration.");
            return;
        }

        try {
            // 1) 拉元数据（幂等 + 明确的失败日志）
            final Metadata md = Metadata.loadFrom(metaBase);
            log.debug("LaneRegistrar: metadata = {}", md);

            // 2) 构建 SDK client（短超时 + 默认凭证链）
            final ClientOverrideConfiguration cfg = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(SDK_TIMEOUT)
                    .build();
            final Region region = resolveRegion(md.clusterArn());
            this.cachedRegion = region;

            try (EcsClient ecs = EcsClient.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .overrideConfiguration(cfg)
                    .build()) {

                // 3) 通过 DescribeTasks 拿 serviceName（源于 group）
                final String serviceName = fetchServiceName(ecs, md.clusterArn(), md.taskArn());
                if (serviceName == null) {
                    log.warn("LaneRegistrar: not a service task (no group=service:*). Skip.");
                    return;
                }

                // 4) DescribeServices -> Cloud Map serviceId（srv-xxxx）
                final String serviceId = fetchCloudMapServiceId(ecs, md.clusterArn(), serviceName);
                if (serviceId == null) {
                    log.warn("LaneRegistrar: service has no Cloud Map binding. service={}", serviceName);
                    return;
                }

                // 5) 计算 lane（标签优先 -> 名称后缀 -> taskId）
                final String lane = Optional.ofNullable(fetchLaneFromServiceTag(ecs, md.clusterArn(), serviceName))
                        .filter(s -> !s.isBlank())
                        .orElseGet(() -> parseLaneFromServiceName(serviceName)
                                .orElse(md.taskId()));

                // 6) 组装属性并注册（带一次重试）
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put(ATTR_IPV4, md.ipv4());
                attrs.put(ATTR_PORT, String.valueOf(md.port()));
                attrs.put(ATTR_LANE, lane);

                try (ServiceDiscoveryClient sd = ServiceDiscoveryClient.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .overrideConfiguration(cfg)
                        .build()) {

                    registerWithRetry(sd, serviceId, md.taskId(), attrs);
                    this.cachedServiceId  = serviceId;
                    this.cachedInstanceId = md.taskId();

                    log.info("LaneRegistrar: registered OK. serviceId={}, instanceId={}, ip={}, port={}, lane={}, serviceName={}, region={}",
                            serviceId, md.taskId(), md.ipv4(), md.port(), lane, serviceName, region.id());
                }
            }
        } catch (AwsServiceException e) {
            var d = e.awsErrorDetails();
            log.warn("LaneRegistrar AWS error: svc={}, code={}, msg={}, status={}",
                    d.serviceName(), d.errorCode(), d.errorMessage(), e.statusCode(), e);
        } catch (SdkException e) {
            log.warn("LaneRegistrar SDK error: {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        } catch (Exception e) {
            log.warn("LaneRegistrar failed; skip. {}", e.toString(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!ENABLE_DEREGISTER_ON_SHUTDOWN) return;
        if (cachedServiceId == null || cachedInstanceId == null || cachedRegion == null) return;

        try (ServiceDiscoveryClient sd = ServiceDiscoveryClient.builder()
                .region(cachedRegion)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(SDK_TIMEOUT).build())
                .build()) {
            sd.deregisterInstance(DeregisterInstanceRequest.builder()
                    .serviceId(cachedServiceId)
                    .instanceId(cachedInstanceId)
                    .build());
            log.info("LaneRegistrar: deregistered. serviceId={}, instanceId={}", cachedServiceId, cachedInstanceId);
        } catch (Exception e) {
            log.warn("LaneRegistrar: deregister failed: {}", e.toString());
        }
    }

    // ======================== 业务封装 ========================

    /** region 优先从 clusterArn（arn:...:region:...）解析，其次环境变量 AWS_REGION，最后 us-east-1。 */
    private static Region resolveRegion(String clusterArn) {
        String fromArn = parseRegionFromArn(clusterArn);
        if (fromArn != null) return Region.of(fromArn);
        String env = System.getenv("AWS_REGION");
        return (env != null && !env.isBlank()) ? Region.of(env) : Region.US_EAST_1;
    }

    private static String parseRegionFromArn(String arn) {
        try {
            String[] parts = arn.split(":");
            return parts.length > 3 ? parts[3] : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** DescribeTasks -> 解析 group: "service:<name>" */
    private static String fetchServiceName(EcsClient ecs, String clusterArn, String taskArn) {
        var dt = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterArn)
                .tasks(taskArn)
                .build());
        if (dt.tasks().isEmpty()) return null;
        String group = dt.tasks().get(0).group();
        if (group == null || !group.startsWith("service:")) return null;
        return group.substring("service:".length());
    }

    /** DescribeServices -> 取 serviceRegistries[0].registryArn -> 解析 srv-xxxx */
    private static String fetchCloudMapServiceId(EcsClient ecs, String clusterArn, String serviceName) {
        var ds = ecs.describeServices(DescribeServicesRequest.builder()
                .cluster(clusterArn)
                .services(serviceName)
                .build());
        Service svc = ds.services().isEmpty() ? null : ds.services().get(0);
        if (svc == null || svc.serviceRegistries().isEmpty()) return null;

        return svc.serviceRegistries().stream()
                .map(r -> r.registryArn())
                .filter(a -> a != null && !a.isBlank())
                .findFirst()
                .map(arn -> arn.substring(arn.lastIndexOf('/') + 1))
                .orElse(null);
    }

    /** lane 优先来自 Service 标签 key=lane（忽略大小写），兼容大小写混用。 */
    private static String fetchLaneFromServiceTag(EcsClient ecs, String clusterArn, String serviceName) {
        // 先拿 serviceArn（DescribeServices 里已经有，但为了清晰与独立性，再取一次）
        var ds = ecs.describeServices(DescribeServicesRequest.builder()
                .cluster(clusterArn)
                .services(serviceName)
                .build());
        if (ds.services().isEmpty()) return null;
        String serviceArn = ds.services().get(0).serviceArn();

        var res = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(serviceArn)
                .build());
        if (res.tags() == null) return null;

        return res.tags().stream()
                .filter(t -> {
                    String k = t.key();
                    return k != null && "lane".equalsIgnoreCase(k);
                })
                .map(t -> t.value())
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    /** 从服务名解析 lane（最后一个 '-' 后的片段）。 */
    private static Optional<String> parseLaneFromServiceName(String serviceName) {
        if (serviceName == null) return Optional.empty();
        int i = serviceName.lastIndexOf('-');
        return (i > 0 && i < serviceName.length() - 1)
                ? Optional.of(serviceName.substring(i + 1))
                : Optional.empty();
    }

    /** 带一次指数退避重试的注册（幂等：同 instanceId 会覆盖属性）。 */
    private static void registerWithRetry(ServiceDiscoveryClient sd,
                                          String serviceId,
                                          String instanceId,
                                          Map<String, String> attrs) throws InterruptedException {
        int attempts = 2;      // 总尝试 = 2（首次 + 重试1次）
        long backoff = 300L;   // ms

        for (int i = 1; i <= attempts; i++) {
            try {
                sd.registerInstance(RegisterInstanceRequest.builder()
                        .serviceId(serviceId)
                        .instanceId(instanceId)
                        .attributes(attrs)
                        .build());
                return; // 成功
            } catch (AwsServiceException e) {
                if (i == attempts) throw e;
                long jitter = ThreadLocalRandom.current().nextLong(50L); // 轻微抖动
                Thread.sleep(backoff + jitter);
                backoff *= 2;
            }
        }
    }

    // ======================== 元数据抽象 ========================

    /**
     * 容器/任务元数据（只保留我们需要的字段），封装解析与兜底逻辑。
     */
    private record Metadata(String clusterArn, String taskArn, String taskId, String ipv4, int port) {
        static Metadata loadFrom(String metaBase) throws IOException, InterruptedException {
            HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

            JsonNode task = httpJson(http, metaBase + "/task");
            JsonNode cont = httpJson(http, metaBase + "/container");

            String clusterArn = text(task, "Cluster");
            String taskArn    = text(task, "TaskARN");
            if (clusterArn == null || taskArn == null) {
                throw new IllegalStateException("metadata missing Cluster/TaskARN");
            }

            String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);
            String ip     = firstIp(cont).orElse("127.0.0.1"); // 如果拿不到，兜底本地（至少不为空）
            int port      = detectPort(cont);

            return new Metadata(clusterArn, taskArn, taskId, ip, port);
        }

        private static JsonNode httpJson(HttpClient http, String url) throws IOException, InterruptedException {
            try {
                var req = HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).GET().build();
                var res = http.send(req, HttpResponse.BodyHandlers.ofString());
                return JSON.readTree(res.body());
            } catch (Exception e) {
                throw (e instanceof IOException ioe) ? ioe : new IOException(e);
            }
        }

        private static String text(JsonNode root, String field) {
            return Optional.ofNullable(root)
                    .map(r -> r.path(field))
                    .filter(n -> !n.isMissingNode())
                    .map(JsonNode::asText)
                    .orElse(null);
        }

        /** 优先 IPv4 -> 再 IPv6；任一网络都可用即返回。 */
        private static Optional<String> firstIp(JsonNode cont) {
            if (cont == null) return Optional.empty();
            JsonNode nets = cont.path("Networks");
            if (!nets.isArray()) return Optional.empty();

            // 先找 IPv4
            Optional<String> ip4 = StreamSupport.stream(nets.spliterator(), false)
                    .map(n -> n.path("IPv4Addresses"))
                    .filter(JsonNode::isArray)
                    .flatMap(arr -> StreamSupport.stream(arr.spliterator(), false))
                    .map(JsonNode::asText)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst();
            if (ip4.isPresent()) return ip4;

            // 再找 IPv6
            return StreamSupport.stream(nets.spliterator(), false)
                    .map(n -> n.path("IPv6Addresses"))
                    .filter(JsonNode::isArray)
                    .flatMap(arr -> StreamSupport.stream(arr.spliterator(), false))
                    .map(JsonNode::asText)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst();
        }

        /** 端口探测：metadata Ports[].ContainerPort -> 环境变量 -> 默认值。 */
        private static int detectPort(JsonNode cont) {
            // metadata
            if (cont != null) {
                JsonNode ports = cont.path("Ports");
                if (ports.isArray()) {
                    Optional<Integer> p = StreamSupport.stream(ports.spliterator(), false)
                            .map(n -> n.path("ContainerPort").asInt(-1))
                            .filter(v -> v > 0)
                            .findFirst();
                    if (p.isPresent()) return p.get();
                }
            }
            // env（可选：允许你覆盖）
            String env = Optional.ofNullable(System.getenv("GRPC_SERVER_PORT"))
                    .filter(s -> !s.isBlank())
                    .orElse(System.getenv("APP_PORT"));
            if (env != null) {
                try { return Integer.parseInt(env); } catch (NumberFormatException ignore) {}
            }
            return DEFAULT_APP_PORT;
        }
    }
}