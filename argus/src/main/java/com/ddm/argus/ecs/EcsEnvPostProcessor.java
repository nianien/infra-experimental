package com.ddm.argus.ecs;

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
import java.util.*;

/**
 * 启动早期（EnvironmentPostProcessor）注入 ECS 任务元数据：
 * 写入前缀 {@code ecs.instance.*} 的属性，并基于 ECS Service 的 tag:profile 激活 Spring Profile。
 */
public class EcsEnvPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final ObjectMapper M = new ObjectMapper();

    private final Log log;

    public EcsEnvPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(EcsEnvPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        final String metaBase = System.getenv(EcsConstants.ENV_ECS_METADATA_V4);
        if (metaBase == null || metaBase.isBlank()) {
            log.info("==>[argus] env:" + EcsConstants.ENV_ECS_METADATA_V4 + " not found; skip.");
            return;
        }
        try {
            // 1) /task → clusterArn, taskArn
            final HttpClient http = HttpClient.newBuilder().connectTimeout(EcsConstants.HTTP_TIMEOUT).build();
            final JsonNode taskMeta = httpJson(http, metaBase + "/task");
            final String clusterArn = text(taskMeta, EcsConstants.META_FIELD_CLUSTER);
            final String taskArn = text(taskMeta, EcsConstants.META_FIELD_TASK_ARN);
            if (isBlank(clusterArn) || isBlank(taskArn)) {
                log.warn("==>[argus] metadata missing Cluster/TaskARN; skip.");
                return;
            }
            final String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);
            final Region region = regionFromArn(clusterArn);

            String serviceName = null, serviceArn = null, taskDefArn = null, containerName = null, lane = null;
            Integer containerPort = null;
            List<Tag> tags = List.of();

            // 2) ECS SDK 预取
            final ClientOverrideConfiguration cfg = ClientOverrideConfiguration.builder().apiCallTimeout(EcsConstants.SDK_TIMEOUT).build();

            try (EcsClient ecs = EcsClient.builder().region(region).credentialsProvider(DefaultCredentialsProvider.create()).overrideConfiguration(cfg).build()) {

                final Task task = ecs.describeTasks(DescribeTasksRequest.builder().cluster(clusterArn).tasks(taskArn).build()).tasks().stream().findFirst().orElse(null);

                if (task != null && notBlank(task.group()) && task.group().startsWith("service:")) {
                    serviceName = task.group().substring("service:".length());
                    taskDefArn = task.taskDefinitionArn();

                    final Service svc = ecs.describeServices(DescribeServicesRequest.builder().cluster(clusterArn).services(serviceName).build()).services().stream().findFirst().orElse(null);

                    if (svc != null) {
                        serviceArn = svc.serviceArn();
                        try {
                            tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder().resourceArn(serviceArn).build()).tags();
                        } catch (Exception ignore) {
                            // ignore tag fetch failure
                        }
                    }

                    if (notBlank(taskDefArn)) {
                        final TaskDefinition td = ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefArn).build()).taskDefinition();

                        final ContainerDefinition appC = (td == null) ? null : td.containerDefinitions().stream().filter(cd -> cd.portMappings() != null && !cd.portMappings().isEmpty()).findFirst().orElse(null);

                        if (appC != null) {
                            containerName = appC.name();
                            containerPort = appC.portMappings().get(0).containerPort();
                        }
                    }
                    lane = firstTagIgnoreCase(tags, EcsConstants.TAG_LANE).orElse(null);
                } else {
                    log.warn("==>[argus] not a service task. group=" + (task == null ? null : task.group()));
                }
            } catch (Exception e1) {
                log.warn("==>[argus] ECS SDK prefetch failed (ignored): " + e1);
            }

            // 3) 注入 ecs.instance.* 属性（仅放非空值）
            final Map<String, Object> kv = new LinkedHashMap<>();
            kv.put(EcsConstants.PROP_CLUSTER_ARN, clusterArn);
            kv.put(EcsConstants.PROP_TASK_ARN, taskArn);
            kv.put(EcsConstants.PROP_TASK_ID, taskId);
            kv.put(EcsConstants.PROP_REGION_ID, region.id());
            kv.put(EcsConstants.PROP_META_BASE, metaBase);
            putIfNotNull(kv, EcsConstants.PROP_SERVICE_NAME, serviceName);
            putIfNotNull(kv, EcsConstants.PROP_SERVICE_ARN, serviceArn);
            putIfNotNull(kv, EcsConstants.PROP_TASK_DEF_ARN, taskDefArn);
            putIfNotNull(kv, EcsConstants.PROP_CONTAINER_NAME, containerName);
            putIfNotNull(kv, EcsConstants.PROP_CONTAINER_PORT, containerPort);
            putIfNotNull(kv, EcsConstants.PROP_LANE, lane);
            env.getPropertySources().addFirst(new MapPropertySource(EcsConstants.PS_ECS_INSTANCE, kv));

            // 4) 仅使用 tag "profile" 激活 profiles
            String activeFromEnv = env.getProperty("spring.profiles.active");
            List<String> profiles = new ArrayList<>(parseProfiles(activeFromEnv));
            String profilesRaw = firstTagIgnoreCase(tags, EcsConstants.TAG_PROFILE).orElse(null);
            profiles.addAll(parseProfiles(profilesRaw));
            if (!profiles.isEmpty()) {
                activateProfiles(env, profiles);
                kv.put(EcsConstants.PROP_PROFILES, String.join(",", profiles));
            }
            log.info(String.format("==>[argus] ecs.instance injected: region=%s, service=%s, container=%s:%s, lane='%s', profiles=%s", region.id(), serviceName, containerName, containerPort, lane, profiles));

        } catch (Exception e) {
            log.warn("==>[argus] ecs.instance inject failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    // ===== helpers =====

    private static JsonNode httpJson(HttpClient http, String url) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(EcsConstants.HTTP_TIMEOUT).GET().build();
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


    private static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (v == null) return;
        if (v instanceof String s && s.isBlank()) return;
        m.put(k, v);
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
        return Arrays.stream(raw.split(",")).map(String::trim).filter(EcsEnvPostProcessor::notBlank).distinct().toList();
    }

    private void activateProfiles(ConfigurableEnvironment env, List<String> profiles) {
        final Set<String> existing = new LinkedHashSet<>(Arrays.asList(env.getActiveProfiles()));
        for (String p : profiles) {
            if (existing.add(p)) env.addActiveProfile(p);
        }
        log.info("==>[argus] Activated profiles from ECS tags: " + profiles);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean notBlank(String s) {
        return !isBlank(s);
    }
}