package com.ddm.argus.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class LaneBootstrap {

    private static final Logger log = LoggerFactory.getLogger(LaneBootstrap.class);
    private static final ObjectMapper M = new ObjectMapper();

    // 仅用来获取 clusterArn / taskArn
    private static final String META_ENV = "ECS_CONTAINER_METADATA_URI_V4";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SDK_TIMEOUT = Duration.ofSeconds(6);

    private static final String ATTR_IPV4 = "AWS_INSTANCE_IPV4";
    private static final String ATTR_PORT = "AWS_INSTANCE_PORT";
    private static final String ATTR_LANE = "lane";

    @PostConstruct
    public void init() {
        String metaBase = System.getenv(META_ENV);
        if (metaBase == null || metaBase.isBlank()) {
            log.info("LaneRegistrar: not in ECS (no metadata). Skip.");
            return;
        }

        try {
            // 1) 从 metadata 取 clusterArn / taskArn
            HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            JsonNode taskMeta = httpJson(http, metaBase + "/task");
            String clusterArn = text(taskMeta, "Cluster");
            String taskArn = text(taskMeta, "TaskARN");
            if (clusterArn == null || taskArn == null) {
                log.warn("LaneRegistrar: metadata missing Cluster/TaskARN. Skip.");
                return;
            }
            String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);
            Region region = regionFromArn(clusterArn);

            // 2) ECS/SD 客户端
            ClientOverrideConfiguration cfg = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(SDK_TIMEOUT)
                    .build();

            try (EcsClient ecs = EcsClient.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .overrideConfiguration(cfg)
                    .build();
                 ServiceDiscoveryClient sd = ServiceDiscoveryClient.builder()
                         .region(region)
                         .credentialsProvider(DefaultCredentialsProvider.create())
                         .overrideConfiguration(cfg)
                         .build()) {

                // 3) DescribeTasks → serviceName + taskDefArn
                Task task = ecs.describeTasks(DescribeTasksRequest.builder()
                                .cluster(clusterArn).tasks(taskArn).build())
                        .tasks().stream().findFirst().orElse(null);
                if (task == null || task.group() == null || !task.group().startsWith("service:")) {
                    log.warn("LaneRegistrar: not a service task. group={}", (task == null ? null : task.group()));
                    return;
                }
                String serviceName = task.group().substring("service:".length());
                String taskDefArn = task.taskDefinitionArn();

                // 4) DescribeServices → Cloud Map serviceId、serviceArn
                Service svc = ecs.describeServices(DescribeServicesRequest.builder()
                                .cluster(clusterArn).services(serviceName).build())
                        .services().stream().findFirst().orElse(null);
                if (svc == null) {
                    log.warn("LaneRegistrar: service not found.");
                    return;
                }

                String registryArn = svc.serviceRegistries().stream()
                        .map(ServiceRegistry::registryArn)
                        .filter(a -> a != null && !a.isBlank())
                        .findFirst().orElse(null);
                if (registryArn == null) {
                    log.warn("LaneRegistrar: no CloudMap binding.");
                    return;
                }

                String serviceId = registryArn.substring(registryArn.lastIndexOf('/') + 1);
                String serviceArn = svc.serviceArn();

                // 5) DescribeTaskDefinition → 选有端口的容器，拿 containerName/containerPort
                TaskDefinition td = ecs.describeTaskDefinition(
                        DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefArn).build()
                ).taskDefinition();
                var appContainer = td.containerDefinitions().stream()
                        .filter(cd -> cd.portMappings() != null && !cd.portMappings().isEmpty())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No container with portMappings."));
                String containerName = appContainer.name();
                int containerPort = appContainer.portMappings().get(0).containerPort();

                // 6) 轮询 DescribeTasks 拿私网 IP（同一套固定间隔重试）
                String ip = Retry.get(
                        () -> tryGetPrivateIp(ecs, clusterArn, taskArn, containerName),
                        v -> v != null && !v.isBlank(),
                        /*attempts*/ 20,
                        /*sleepMs*/ 800
                );
                if (ip == null) {
                    log.warn("LaneRegistrar: cannot get private IP after polling.");
                    return;
                }

                // 7) lane：标签优先 -> 名称后缀 -> 空
                String lane = fetchLaneTag(ecs, serviceArn);
                if (lane == null) lane = suffixAfterDash(serviceName).orElse("");

                // 8) RegisterInstance（同一套固定间隔重试）
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put(ATTR_IPV4, ip);
                attrs.put(ATTR_PORT, String.valueOf(containerPort));
                attrs.put(ATTR_LANE, lane);

                Retry.run(
                        () -> sd.registerInstance(RegisterInstanceRequest.builder()
                                .serviceId(serviceId)
                                .instanceId(taskId)
                                .attributes(attrs)
                                .build()),
                        /*attempts*/ 5,
                        /*sleepMs*/ 1000
                );

                log.info("LaneRegistrar OK. serviceId={}, instanceId={}, ip={}, port={}, lane={}, region={}",
                        serviceId, taskId, ip, containerPort, lane, region.id());
            }
        } catch (Exception e) {
            log.warn("LaneRegistrar failed: {}", e.toString(), e);
        }
    }

    // ======= 单一重试工具（固定间隔；成功或次数用尽） =======
    static final class Retry {
        interface SupplierX<T> {
            T get() throws Exception;
        }

        interface RunnableX {
            void run() throws Exception;
        }

        /**
         * 有返回值的轮询：predicate 为 true 视为成功；否则固定 sleep 重试。
         */
        static <T> T get(SupplierX<T> s, java.util.function.Predicate<T> ok,
                         int attempts, long sleepMs) throws Exception {
            T v;
            for (int i = 1; i <= attempts; i++) {
                try {
                    v = s.get();
                } catch (Exception ignore) {
                    v = null;
                }
                if (ok.test(v)) return v;
                if (i < attempts) Thread.sleep(sleepMs);
            }
            return null;
        }

        /**
         * 无返回值的执行：不抛异常视为成功；否则固定 sleep 重试。
         */
        static void run(RunnableX r, int attempts, long sleepMs) throws Exception {
            for (int i = 1; i <= attempts; i++) {
                try {
                    r.run();
                    return;
                } catch (Exception e) {
                    if (i >= attempts) throw e;
                    Thread.sleep(sleepMs);
                }
            }
        }
    }

    // ======= helpers =======

    private static String tryGetPrivateIp(EcsClient ecs, String clusterArn, String taskArn, String containerName) {
        var dt = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterArn).tasks(taskArn).build());
        if (dt.tasks().isEmpty()) return null;
        Task t = dt.tasks().get(0);
        return t.containers().stream()
                .filter(c -> containerName.equals(c.name()))
                .findFirst()
                .map(c -> (c.networkInterfaces() == null || c.networkInterfaces().isEmpty()) ? null
                        : c.networkInterfaces().get(0).privateIpv4Address())
                .orElse(null);
    }

    private static String fetchLaneTag(EcsClient ecs, String serviceArn) {
        try {
            var tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                    .resourceArn(serviceArn).build()).tags();
            if (tags == null) return null;
            return tags.stream()
                    .filter(t -> t.key() != null && t.key().equalsIgnoreCase("lane"))
                    .map(Tag::value)
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Optional<String> suffixAfterDash(String name) {
        if (name == null) return Optional.empty();
        int i = name.lastIndexOf('-');
        return (i > 0 && i < name.length() - 1) ? Optional.of(name.substring(i + 1)) : Optional.empty();
    }

    private static JsonNode httpJson(HttpClient http, String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT).GET().build();
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

    private static Region regionFromArn(String arn) {
        String[] p = arn.split(":");
        if (p.length > 3 && p[3] != null && !p[3].isBlank()) return Region.of(p[3]);
        throw new IllegalStateException("Cannot parse region from ARN: " + arn);
    }
}