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
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * LaneBootstrap（统一版 & 零环境变量）
 * <p>
 * 工作流：
 * 1) 仅在 ECS/Fargate 且存在 ECS_CONTAINER_METADATA_URI_V4 时运行；
 * 2) 读 /task 与 /container 元数据，按 DockerId/Name 匹配“当前容器”，
 * 从 /task 的容器项中拿私网 IP 与容器端口（拿不到就放弃注册，不再回落 127.0.0.1）；
 * 3) ECS DescribeTasks -> group = "service:<name>" 拿 serviceName；
 * 4) ECS DescribeServices -> 解析 Cloud Map registryArn 得 serviceId (srv-xxxx)；
 * 5) 计算 lane：优先 Service 标签 key=lane（忽略大小写）-> 名称后缀 -> taskId；
 * 6) Cloud Map RegisterInstance(instanceId=taskId, attrs={AWS_INSTANCE_IPV4, AWS_INSTANCE_PORT, lane}),
 * 幂等覆盖；带 attempts 重试 + 指数退避。
 */
@Component
public class LaneBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LaneBootstrap.class);
    private static final ObjectMapper M = new ObjectMapper();

    // 可调参数
    private static final int DEFAULT_APP_PORT = 9091;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SDK_API_TIMEOUT = Duration.ofSeconds(5);

    @PostConstruct
    public void init() {
        final String metaBase = System.getenv("ECS_CONTAINER_METADATA_URI_V4");
        if (metaBase == null || metaBase.isBlank()) {
            log.info("LaneBootstrap: no ECS metadata (likely local run). Skip lane registration.");
            return;
        }

        try {
            final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();

            // 1) 读取 Task / Container 元数据
            final JsonNode taskMeta = getJson(http, metaBase + "/task");
            final JsonNode contMeta = getJson(http, metaBase + "/container");

            final String clusterArn = text(taskMeta, "Cluster");
            final String taskArn = text(taskMeta, "TaskARN");
            if (clusterArn == null || taskArn == null) {
                log.warn("LaneBootstrap: metadata missing Cluster/TaskARN. Skip.");
                return;
            }
            final String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);

            // === 精确定位“当前容器”并取 IP/端口（严格：拿不到就不注册）===
            final JsonNode me = matchSelfContainer(taskMeta, contMeta); // 从 /task 容器列表里，按 DockerId/Name 匹配
            final String ip = extractPrivateIp(me).orElse(null);
            final int port = detectPort(me).orElseGet(() -> detectPort(contMeta).orElse(DEFAULT_APP_PORT));

            if (ip == null || ip.startsWith("127.") || "localhost".equalsIgnoreCase(ip)) {
                log.warn("LaneBootstrap: cannot resolve private IP from ECS metadata; skip registration. ip={}", ip);
                return;
            }

            final Region region = resolveRegion(clusterArn);
            final ClientOverrideConfiguration cfg = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(SDK_API_TIMEOUT)
                    .build();

            // 2) DescribeTasks -> "service:<name>"
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

                // 3) DescribeServices -> Cloud Map registryArn -> serviceId (srv-xxxx)
                var ds = ecs.describeServices(DescribeServicesRequest.builder()
                        .cluster(clusterArn)
                        .services(serviceName)
                        .build());
                Service svc = ds.services().isEmpty() ? null : ds.services().get(0);
                if (svc == null || svc.serviceRegistries().isEmpty()) {
                    log.warn("LaneBootstrap: ECS Service has NO Cloud Map binding. service={}", serviceName);
                    return;
                }
                final String registryArn = svc.serviceRegistries().stream()
                        .map(r -> r.registryArn())
                        .filter(a -> a != null && !a.isBlank())
                        .findFirst().orElse(null);
                if (registryArn == null) {
                    log.warn("LaneBootstrap: No registryArn in serviceRegistries. service={}", serviceName);
                    return;
                }
                final String serviceId = registryArn.substring(registryArn.lastIndexOf('/') + 1);

                // 4) lane：标签优先 -> 名称后缀 -> taskId
                final String lane = Optional.ofNullable(fetchLaneFromServiceTags(ecs, svc.serviceArn()))
                        .filter(s -> !s.isBlank())
                        .orElseGet(() -> parseLaneFromServiceName(serviceName).orElse(taskId));

                // 5) RegisterInstance 幂等上报（attempts 写法 + 指数退避）
                final Map<String, String> attrs = Map.of(
                        "AWS_INSTANCE_IPV4", ip,
                        "AWS_INSTANCE_PORT", String.valueOf(port),
                        "lane", lane
                );

                try (ServiceDiscoveryClient sd = ServiceDiscoveryClient.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .overrideConfiguration(cfg)
                        .build()) {
                    registerInstanceWithAttempts(sd, serviceId, taskId, attrs, 3, 300L);
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

    private static Region resolveRegion(String clusterArn) {
        String fromArn = parseRegionFromArn(clusterArn);
        if (fromArn != null) return Region.of(fromArn);
        String envRegion = System.getenv("AWS_REGION");
        return (envRegion != null && !envRegion.isBlank()) ? Region.of(envRegion) : Region.US_EAST_1;
    }

    private static String parseRegionFromArn(String arn) {
        try {
            String[] parts = arn.split(":");
            return (parts.length > 3) ? parts[3] : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    private static JsonNode getJson(HttpClient http, String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).GET().build();
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
     * 在 /task 的 Containers[] 中，按 /container 的 DockerId 或 Name 匹配当前容器项
     */
    private static JsonNode matchSelfContainer(JsonNode taskMeta, JsonNode contMeta) {
        if (taskMeta == null || contMeta == null) return null;
        String dockerId = text(contMeta, "DockerId");
        String name = text(contMeta, "Name");

        JsonNode list = taskMeta.path("Containers");
        if (!list.isArray()) return null;

        for (JsonNode c : list) {
            String did = text(c, "DockerId");
            String nm = text(c, "Name");
            if ((dockerId != null && dockerId.equals(did)) ||
                    (name != null && name.equals(nm))) {
                return c;
            }
        }
        return null;
    }

    /**
     * 从匹配到的容器项里取私网 IPv4
     */
    private static Optional<String> extractPrivateIp(JsonNode me) {
        if (me == null) return Optional.empty();
        JsonNode nets = me.path("Networks");
        if (!nets.isArray()) return Optional.empty();
        return StreamSupport.stream(nets.spliterator(), false)
                .map(n -> n.path("IPv4Addresses"))
                .filter(JsonNode::isArray)
                .flatMap(arr -> StreamSupport.stream(arr.spliterator(), false))
                .map(JsonNode::asText)
                .filter(ip -> ip != null && !ip.isBlank())
                .findFirst();
    }

    /**
     * 端口探测：优先 me.Ports[].ContainerPort；其次 contMeta；最后默认（上层兜底）
     */
    private static Optional<Integer> detectPort(JsonNode node) {
        if (node == null) return Optional.empty();
        JsonNode ports = node.path("Ports");
        if (!ports.isArray()) return Optional.empty();
        return StreamSupport.stream(ports.spliterator(), false)
                .map(p -> p.path("ContainerPort").asInt(-1))
                .filter(p -> p > 0)
                .findFirst();
    }

    /**
     * 从 Service 标签中读 lane（忽略大小写）
     */
    private static String fetchLaneFromServiceTags(EcsClient ecs, String serviceArn) {
        try {
            var tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                            .resourceArn(serviceArn)
                            .build())
                    .tags();
            return Optional.ofNullable(tags).orElseGet(Collections::emptyList).stream()
                    .filter(t -> {
                        String k = t.key();
                        return k != null && "lane".equalsIgnoreCase(k);
                    })
                    .map(t -> t.value())
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.debug("LaneBootstrap: listTagsForResource failed, fallback to name-suffix. {}", e.toString());
            return null;
        }
    }

    /**
     * 从 serviceName 最后一个 '-' 后取 lane
     */
    private static Optional<String> parseLaneFromServiceName(String serviceName) {
        if (serviceName == null) return Optional.empty();
        int i = serviceName.lastIndexOf('-');
        return (i > 0 && i < serviceName.length() - 1)
                ? Optional.of(serviceName.substring(i + 1))
                : Optional.empty();
    }

    /**
     * attempts 写法 + 指数退避（幂等 upsert）
     */
    private void registerInstanceWithAttempts(ServiceDiscoveryClient sd,
                                              String serviceId,
                                              String instanceId,
                                              Map<String, String> attrs,
                                              int attempts,
                                              long initialBackoffMs) throws InterruptedException {
        long backoff = initialBackoffMs;
        for (int i = 1; i <= attempts; i++) {
            try {
                sd.registerInstance(RegisterInstanceRequest.builder()
                        .serviceId(serviceId)
                        .instanceId(instanceId)
                        .attributes(attrs)
                        .build());
                log.info("Cloud Map upsert OK (attempt {}/{}): serviceId={}, instanceId={}, attrs={}",
                        i, attempts, serviceId, instanceId, attrs);
                return;
            } catch (AwsServiceException e) {
                var d = e.awsErrorDetails();
                log.warn("Cloud Map upsert FAILED (attempt {}/{}): code={}, msg={}",
                        i, attempts, d.errorCode(), d.errorMessage());
                if (i < attempts) {
                    Thread.sleep(backoff);
                    backoff *= 2;
                } else {
                    throw e;
                }
            }
        }
    }
}