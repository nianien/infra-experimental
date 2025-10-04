package com.ddm.argus.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * LaneBootstrap（统一版 & 零环境变量）
 * <p>
 * 运行机制：
 * - 仅当容器中存在 ECS 元数据 v4（ECS/Fargate 注入的环境变量）时启用；
 * - 启动时自动：
 * 1) 通过元数据拿到 clusterArn / taskArn / 容器 IP / 端口；
 * 2) 调 ECS：DescribeTasks → 得到 serviceName（来自 group: "service:<name>"）；
 * 3) 调 ECS：DescribeServices → 解析 Cloud Map 的 registryArn → serviceId (srv-xxxx)；
 * 4) 计算 lane：
 * a) 优先从 ECS Service 标签中读取 key=lane（忽略大小写）；
 * b) 否则取服务名最后一个 '-' 的后缀；
 * c) 再否则用 taskId 兜底；
 * 5) 调 Cloud Map：RegisterInstance(instanceId=taskId)，附加属性 {AWS_INSTANCE_IPV4, AWS_INSTANCE_PORT, lane}。
 * <p>
 * 说明：
 * - 幂等：同一个 instanceId（taskId）重复 register 会覆盖属性；无副作用。
 * - 与部署解耦：不需要在 Task Definition/Service 里预置环境变量即可完成 lane 上报。
 */
@Component
public class LaneBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LaneBootstrap.class);
    private static final ObjectMapper M = new ObjectMapper();

    // ===== 可按需微调 =====
    private static final int DEFAULT_APP_PORT = 9091;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SDK_API_TIMEOUT = Duration.ofSeconds(5);

    @PostConstruct
    public void init() {
        final String metaBase = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
        if (metaBase == null || metaBase.isBlank()) {
            log.info("LaneBootstrap: no ECS metadata (likely local run). Skip lane registration.");
            return; // 本地/非 ECS 环境自动跳过
        }

        try {
            final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

            // 1) 读取 Task / Container 元数据
            JsonNode taskMeta = getJson(http, metaBase + "/task");
            JsonNode contMeta = getJson(http, metaBase + "/container");

            final String clusterArn = text(taskMeta, "Cluster");
            final String taskArn = text(taskMeta, "TaskARN");
            if (clusterArn == null || taskArn == null) {
                log.warn("LaneBootstrap: metadata missing Cluster/TaskARN. Skip.");
                return;
            }

            final String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);
            final String ip = firstIp(contMeta).orElse("127.0.0.1");
            final int port = detectPort(contMeta);

            final Region region = resolveRegion(clusterArn);

            final ClientOverrideConfiguration cfg = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(SDK_API_TIMEOUT)
                    .build();

            // 2) DescribeTasks → "service:<name>"
            final String serviceName;
            try (EcsClient ecs = EcsClient.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .overrideConfiguration(cfg)
                    .build()) {

                var dt = ecs.describeTasks(DescribeTasksRequest.builder()
                        .cluster(clusterArn)
                        .tasks(taskArn)
                        .build());

                var task = dt.tasks().isEmpty() ? null : dt.tasks().get(0);
                final String group = (task == null) ? null : task.group();
                if (group == null || !group.startsWith("service:")) {
                    log.warn("LaneBootstrap: Task.group is not a service task (group={}). Skip.", group);
                    return;
                }
                serviceName = group.substring("service:".length());

                // 3) DescribeServices → Cloud Map registryArn → serviceId (srv-xxxx)
                var ds = ecs.describeServices(DescribeServicesRequest.builder()
                        .cluster(clusterArn)
                        .services(serviceName)
                        .build());

                Service svc = ds.services().isEmpty() ? null : ds.services().get(0);
                if (svc == null || svc.serviceRegistries().isEmpty()) {
                    log.warn("LaneBootstrap: ECS Service has NO Cloud Map binding. service={}", serviceName);
                    return;
                }

                final String registryArn = svc.serviceRegistries()
                        .stream()
                        .map(r -> r.registryArn())
                        .filter(a -> a != null && !a.isBlank())
                        .findFirst()
                        .orElse(null);

                if (registryArn == null) {
                    log.warn("LaneBootstrap: No registryArn in serviceRegistries. service={}", serviceName);
                    return;
                }

                final String serviceId = registryArn.substring(registryArn.lastIndexOf('/') + 1);

                // 4) lane = 标签优先 → 服务名后缀 → taskId
                final String laneFromTag = fetchLaneFromServiceTags(ecs, svc.serviceArn());
                final String lane = Optional.ofNullable(laneFromTag)
                        .filter(s -> !s.isBlank())
                        .orElseGet(() -> parseLaneFromServiceName(serviceName).orElse(taskId));

                // 5) RegisterInstance 幂等上报（带一次退避重试）
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("AWS_INSTANCE_IPV4", ip);
                attrs.put("AWS_INSTANCE_PORT", String.valueOf(port));
                attrs.put("lane", lane);

                try (ServiceDiscoveryClient sd = ServiceDiscoveryClient.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .overrideConfiguration(cfg)
                        .build()) {
                    registerInstanceWithRetry(sd, serviceId, taskId, attrs);
                }

                log.info("LaneBootstrap: registered. serviceId={}, instanceId={}, ip={}, port={}, lane={}, serviceName={}, region={}",
                        serviceId, taskId, ip, port, lane, serviceName, region.id());
            }

        } catch (AwsServiceException e) {
            var d = e.awsErrorDetails();
            log.warn("LaneBootstrap AWS error: service={}, code={}, message={}, status={}",
                    d.serviceName(), d.errorCode(), d.errorMessage(), e.statusCode(), e);
        } catch (SdkException e) {
            log.warn("LaneBootstrap AWS SDK error: {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        } catch (Exception e) {
            log.warn("LaneBootstrap failed; skip lane registration.", e);
        }
    }

    // ================= helpers =================

    /**
     * 优先从 clusterArn 解析 region；再看 AWS_REGION；最后兜底 us-east-1。
     */
    private static Region resolveRegion(String clusterArn) {
        String fromArn = parseRegionFromArn(clusterArn);
        if (fromArn != null) return Region.of(fromArn);

        String envRegion = System.getenv("AWS_REGION");
        if (envRegion != null && !envRegion.isBlank()) return Region.of(envRegion);

        return Region.US_EAST_1;
    }

    /**
     * arn:aws:ecs:us-east-1:123456789012:cluster/xxx → 提取 us-east-1
     */
    private static String parseRegionFromArn(String arn) {
        try {
            String[] parts = arn.split(":");
            return (parts.length > 3) ? parts[3] : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static JsonNode getJson(HttpClient http, String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return M.readTree(res.body());
    }

    private static String text(JsonNode root, String field) {
        return Optional.ofNullable(root)
                .map(r -> r.path(field))
                .filter(n -> !n.isMissingNode())
                .map(JsonNode::asText)
                .orElse(null);
    }

    /**
     * 从容器元数据中提取首个可用 IP（优先 IPv4，退化 IPv6）。
     */
    private static Optional<String> firstIp(JsonNode contMeta) {
        if (contMeta == null) return Optional.empty();
        JsonNode nets = contMeta.path("Networks");
        if (!nets.isArray()) return Optional.empty();

        // Iterable<JsonNode> → Stream<JsonNode>
        Stream<JsonNode> netStream = StreamSupport.stream(nets.spliterator(), false);

        // 优先 IPv4
        Optional<String> ip4 = netStream
                .map(n -> n.path("IPv4Addresses"))
                .filter(JsonNode::isArray)
                .flatMap(arr -> StreamSupport.stream(arr.spliterator(), false))
                .map(JsonNode::asText)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();

        if (ip4.isPresent()) return ip4;

        // 再试 IPv6
        netStream = StreamSupport.stream(nets.spliterator(), false);
        return netStream
                .map(n -> n.path("IPv6Addresses"))
                .filter(JsonNode::isArray)
                .flatMap(arr -> StreamSupport.stream(arr.spliterator(), false))
                .map(JsonNode::asText)
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
    }

    /**
     * 端口探测：Metadata → 环境变量 → 默认值。
     */
    private static int detectPort(JsonNode contMeta) {
        // Metadata Ports
        if (contMeta != null) {
            JsonNode ports = contMeta.path("Ports");
            if (ports.isArray()) {
                Optional<Integer> metaPort = StreamSupport.stream(ports.spliterator(), false)
                        .map(p -> p.path("ContainerPort").asInt(-1))
                        .filter(p -> p > 0)
                        .findFirst();
                if (metaPort.isPresent()) return metaPort.get();
            }
        }

        // 环境变量兜底
        String env = Optional.ofNullable(System.getenv("GRPC_SERVER_PORT"))
                .filter(s -> !s.isBlank())
                .orElse(System.getenv("APP_PORT"));
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env);
            } catch (NumberFormatException ignore) {
            }
        }

        return DEFAULT_APP_PORT;
    }

    /**
     * 从 ECS Service 标签中读取 lane（忽略大小写）。
     */
    private static String fetchLaneFromServiceTags(EcsClient ecs, String serviceArn) {
        try {
            var tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                            .resourceArn(serviceArn)
                            .build())
                    .tags();

            return Optional.ofNullable(tags).orElseGet(Collections::emptyList)
                    .stream()
                    .filter(t -> {
                        String k = t.key();
                        return k != null && "lane".equalsIgnoreCase(k);
                    })
                    .map(t -> t.value())
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst()
                    .orElse(null);

        } catch (Exception e) {
            // 标签获取失败不致命，后续会走其他兜底
            log.debug("LaneBootstrap: listTagsForResource failed, fallback to name-suffix. {}", e.toString());
            return null;
        }
    }

    /**
     * 从服务名解析 lane（最后一个 '-' 后的片段）。
     */
    private static Optional<String> parseLaneFromServiceName(String serviceName) {
        if (serviceName == null) return Optional.empty();
        int i = serviceName.lastIndexOf('-');
        if (i > 0 && i < serviceName.length() - 1) {
            return Optional.of(serviceName.substring(i + 1));
        }
        return Optional.empty();
    }

    /**
     * 幂等上报 + 一次退避重试（处理偶发一致性错误）。
     */
    private void registerInstanceWithRetry(ServiceDiscoveryClient sd,
                                           String serviceId,
                                           String taskId,
                                           Map<String, String> attrs) throws InterruptedException {
        int retries = 2;               // 最大重试次数（=执行2次：初始 + 重试1次）
        long backoffMs = 300L;         // 初始退避时间

        while (retries-- > 0) {
            try {
                sd.registerInstance(RegisterInstanceRequest.builder()
                        .serviceId(serviceId)
                        .instanceId(taskId)
                        .attributes(attrs)
                        .build());
                log.info("Cloud Map 注册成功: serviceId={}, instanceId={}, attrs={}", serviceId, taskId, attrs);
                return; // 成功就直接退出
            } catch (AwsServiceException e) {
                log.warn("Cloud Map 注册失败 (剩余重试次数={}): code={}, msg={}",
                        retries, e.awsErrorDetails().errorCode(), e.awsErrorDetails().errorMessage());

                if (retries > 0) {
                    Thread.sleep(backoffMs);
                    backoffMs *= 2; // 指数退避
                } else {
                    throw e; // 最后一次也失败则抛出
                }
            }
        }
    }
}