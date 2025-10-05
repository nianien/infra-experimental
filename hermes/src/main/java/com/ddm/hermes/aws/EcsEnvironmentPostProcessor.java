package com.ddm.hermes.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class EcsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String META_ENV = "ECS_CONTAINER_METADATA_URI_V4";
    private static final String PS_NAME = "ecs-instance";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SDK_TIMEOUT = Duration.ofSeconds(6);
    private static final ObjectMapper M = new ObjectMapper();

    private final Log log;

    public EcsEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(EcsEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        final String metaBase = System.getenv(META_ENV);
        if (metaBase == null || metaBase.isBlank()) {
            log.info(String.format("==>[hermes] env:%s not found; skip.", META_ENV));
            return;
        }

        try {
            // 1) /task → clusterArn, taskArn
            final HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            final JsonNode taskMeta = httpJson(http, metaBase + "/task");
            final String clusterArn = text(taskMeta, "Cluster");
            final String taskArn = text(taskMeta, "TaskARN");
            if (isBlank(clusterArn) || isBlank(taskArn)) {
                log.warn("==>[hermes] metadata missing Cluster/TaskARN; skip.");
                return;
            }
            final String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);
            final Region region = regionFromArn(clusterArn);

            String serviceName = null, serviceArn = null, taskDefArn = null, containerName = null, lane = "";
            Integer containerPort = null;
            List<Tag> tags = List.of();

            // 2) ECS SDK
            final ClientOverrideConfiguration cfg = ClientOverrideConfiguration.builder()
                    .apiCallTimeout(SDK_TIMEOUT).build();

            try (EcsClient ecs = EcsClient.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .overrideConfiguration(cfg)
                    .build()) {

                final Task task = ecs.describeTasks(DescribeTasksRequest.builder()
                                .cluster(clusterArn).tasks(taskArn).build())
                        .tasks().stream().findFirst().orElse(null);

                if (task != null && notBlank(task.group()) && task.group().startsWith("service:")) {
                    serviceName = task.group().substring("service:".length());
                    taskDefArn = task.taskDefinitionArn();

                    final Service svc = ecs.describeServices(DescribeServicesRequest.builder()
                                    .cluster(clusterArn).services(serviceName).build())
                            .services().stream().findFirst().orElse(null);

                    if (svc != null) {
                        serviceArn = svc.serviceArn();
                        try {
                            tags = ecs.listTagsForResource(
                                    ListTagsForResourceRequest.builder().resourceArn(serviceArn).build()
                            ).tags();
                        } catch (Exception ignore) {
                            // ignore tag fetch failure
                        }
                    }

                    if (notBlank(taskDefArn)) {
                        final TaskDefinition td = ecs.describeTaskDefinition(
                                DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefArn).build()
                        ).taskDefinition();

                        final ContainerDefinition appC = td.containerDefinitions().stream()
                                .filter(cd -> cd.portMappings() != null && !cd.portMappings().isEmpty())
                                .findFirst().orElse(null);

                        if (appC != null) {
                            containerName = appC.name();
                            containerPort = appC.portMappings().get(0).containerPort();
                        }
                    }

                    lane = firstTagIgnoreCase(tags, "lane").orElse("");
                    if (lane.isBlank()) lane = suffixAfterDash(serviceName);
                } else {
                    log.warn("==>[hermes] not a service task. group=" + (task == null ? null : task.group()));
                }
            } catch (Exception e1) {
                log.warn("==>[hermes] ECS SDK prefetch failed (ignored): " + e1);
            }

            // 3) 注入 ecs.instance.* 属性（仅放非空值）
            final Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("ecs.instance.cluster-arn", clusterArn);
            kv.put("ecs.instance.task-arn", taskArn);
            kv.put("ecs.instance.task-id", taskId);
            kv.put("ecs.instance.region-id", region.id());
            kv.put("ecs.instance.meta-base", metaBase);
            putIfNotNull(kv, "ecs.instance.service-name", serviceName);
            putIfNotNull(kv, "ecs.instance.service-arn", serviceArn);
            putIfNotNull(kv, "ecs.instance.task-def-arn", taskDefArn);
            putIfNotNull(kv, "ecs.instance.container-name", containerName);
            putIfNotNull(kv, "ecs.instance.container-port", containerPort);
            putIfNotNull(kv, "ecs.instance.lane", lane);
            env.getPropertySources().addFirst(new MapPropertySource(PS_NAME, kv));

            // 4) 仅使用 tag "profile" 激活 profiles
            final String profilesRaw = firstTagIgnoreCase(tags, "profile").orElse(null);
            final List<String> profiles = parseProfiles(profilesRaw);
            if (!profiles.isEmpty()) {
                activateProfiles(env, profiles);
                kv.put("ecs.instance.profiles", String.join(",", profiles));
            }

            log.info(String.format(
                    "==>[hermes] ecs.instance injected: region=%s, service=%s, container=%s:%s, lane='%s', profiles=%s",
                    region.id(), serviceName, containerName, containerPort, lane, profiles));

        } catch (Exception e) {
            log.warn("==>[hermes] ecs.instance inject failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    // ===== helpers =====

    private static JsonNode httpJson(HttpClient http, String url) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT).GET().build();
        final HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return M.readTree(res.body());
    }

    private static String text(JsonNode root, String field) {
        if (root == null) return null;
        final JsonNode n = root.path(field);
        return n.isMissingNode() ? null : n.asText();
    }

    private static Region regionFromArn(String arn) {
        final String[] p = arn.split(":");
        if (p.length > 3 && notBlank(p[3])) return Region.of(p[3]);
        throw new IllegalStateException("Cannot parse region from ARN: " + arn);
    }

    private static String suffixAfterDash(String s) {
        if (isBlank(s)) return "";
        final int i = s.lastIndexOf('-');
        return (i > 0 && i < s.length() - 1) ? s.substring(i + 1) : "";
    }

    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static Optional<String> firstTagIgnoreCase(List<Tag> tags, String key) {
        if (tags == null || key == null) return Optional.empty();
        for (Tag t : tags) {
            if (t.key() != null && t.key().equalsIgnoreCase(key)) {
                final String v = t.value();
                if (notBlank(v)) return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    private static List<String> parseProfiles(String raw) {
        if (isBlank(raw)) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(EcsEnvironmentPostProcessor::notBlank)
                .distinct()
                .toList();
    }

    private void activateProfiles(ConfigurableEnvironment env, List<String> profiles) {
        final Set<String> existing = new LinkedHashSet<>(Arrays.asList(env.getActiveProfiles()));
        for (String p : profiles) {
            if (existing.add(p)) env.addActiveProfile(p);
        }
        log.info("==>[hermes] Activated profiles from ECS tags: " + profiles);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return !isBlank(s);
    }
}