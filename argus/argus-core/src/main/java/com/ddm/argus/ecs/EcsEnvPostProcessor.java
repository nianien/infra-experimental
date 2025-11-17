package com.ddm.argus.ecs;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.net.http.HttpClient;
import java.util.*;

import static com.ddm.argus.utils.CommonUtils.*;
import static com.ddm.argus.utils.EcsUtils.*;
import static com.ddm.argus.utils.JsonUtils.httpJson;
import static com.ddm.argus.utils.JsonUtils.text;

/**
 * 启动早期（EnvironmentPostProcessor）注入 ECS 任务元数据：
 * 写入前缀 {@code ecs.instance.*} 的属性，并基于 ECS Service 的 tag:profile 激活 Spring Profile。
 */
public class EcsEnvPostProcessor implements EnvironmentPostProcessor, Ordered {

    private record SelfContainerMeta(String name, String privateIp) {
    }

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
            // ---------- 0) 先拿最基础的 metadata ----------
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

            // 自身容器（可能拿不到）
            final SelfContainerMeta self = fetchSelfContainerMeta(http, metaBase);
            final String selfContainerNameFromMeta = self.name();
            String privateIp = self.privateIp();

            // ---------- 1) 一次性把 ECS 三件套取齐 ----------
            Task task = null;
            Service svc = null;
            TaskDefinition td = null;
            List<Tag> tags = List.of();

            final ClientOverrideConfiguration cfg =
                    ClientOverrideConfiguration.builder().apiCallTimeout(EcsConstants.SDK_TIMEOUT).build();
            try (EcsClient ecs = EcsClient.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .overrideConfiguration(cfg)
                    .build()) {

                task = fetchTask(ecs, clusterArn, taskArn);

                // metadata 没拿到 IP，则从 task 兜底
                if (isBlank(privateIp) && task != null) {
                    privateIp = ipFromTask(task, selfContainerNameFromMeta);
                }

                // 只有 Service 任务才继续
                if (task != null && notBlank(task.group()) && task.group().startsWith("service:")) {
                    String serviceName = task.group().substring("service:".length());
                    svc = fetchService(ecs, clusterArn, serviceName);
                    if (svc != null) {
                        try {
                            tags = ecs.listTagsForResource(
                                    ListTagsForResourceRequest.builder().resourceArn(svc.serviceArn()).build()
                            ).tags();
                        } catch (Exception ignore) { /* ignore */ }
                    }
                    // Task Definition
                    final String taskDefArn = task.taskDefinitionArn();
                    if (notBlank(taskDefArn)) {
                        td = fetchTaskDefinition(ecs, taskDefArn);
                    }
                } else {
                    log.warn("==>[argus] not a service task. group=" + (task == null ? null : task.group()));
                }
            } catch (Exception e1) {
                log.warn("==>[argus] ECS SDK prefetch failed (ignored): " + e1);
            }

            // ---------- 2) 统一解析（在内存里"算"） ----------
            final String serviceName = (svc != null) ? svc.serviceName() : null;
            final String serviceArn = (svc != null) ? svc.serviceArn() : null;
            final String taskDefArn = (task != null) ? task.taskDefinitionArn() : null;

            // 容器名和端口：先选容器，再分别取名称和端口
            final ContainerDefinition appC = pickContainer(td, selfContainerNameFromMeta);
            final String containerName = resolveContainerName(appC);
            final Integer containerPort = resolveContainerPort(appC);

            // lane（与 profile）：从 tag 里拿
            final String lane = firstIgnoreCase(tags, EcsConstants.TAG_LANE).orElse(null);

            // ---------- 3) 注入属性 ----------
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
            putIfNotNull(kv, EcsConstants.PROP_CONTAINER_HOST, privateIp);
            putIfNotNull(kv, EcsConstants.PROP_LANE, lane);
            env.getPropertySources().addFirst(new MapPropertySource(EcsConstants.PS_ECS_INSTANCE, kv));

            // 仅用 tag:profile 激活
            final String activeFromEnv = env.getProperty("spring.profiles.active");
            final List<String> profiles = new ArrayList<>(splitToUniqueList(activeFromEnv));
            final String profilesRaw = firstIgnoreCase(tags, EcsConstants.TAG_PROFILE).orElse(null);
            profiles.addAll(splitToUniqueList(profilesRaw));
            if (!profiles.isEmpty()) {
                activateProfiles(env, profiles);
                kv.put(EcsConstants.PROP_PROFILES, String.join(",", profiles));
            }

            log.info(String.format(
                    "==>[argus] ecs.instance injected: region=%s, service=%s, container=%s:%s, lane='%s', profiles=%s",
                    region.id(), serviceName, containerName, containerPort, lane, profiles));

        } catch (Exception e) {
            log.warn("==>[argus] ecs.instance inject failed: " + e.getMessage(), e);
        }
    }


    /**
     * 从 ECS metadata v4 的 /container 拉取“当前容器名”，失败则返回 null。
     */
    private static SelfContainerMeta fetchSelfContainerMeta(HttpClient http, String metaBase) {
        try {
            JsonNode c = httpJson(http, metaBase + "/container");
            String name = text(c, "Name");
            String ip = Optional.ofNullable(c.path("Networks"))
                    .filter(JsonNode::isArray).map(n -> n.size() > 0 ? n.get(0) : null)
                    .map(n0 -> n0.path("IPv4Addresses"))
                    .filter(JsonNode::isArray).map(n -> n.size() > 0 ? n.get(0).asText(null) : null)
                    .orElse(null);
            return new SelfContainerMeta(name, ip);
        } catch (Exception ignore) {
            return new SelfContainerMeta(null, null);
        }
    }


    // ========================= Spring 顺序 =========================

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private void activateProfiles(ConfigurableEnvironment env, List<String> profiles) {
        final Set<String> existing = new LinkedHashSet<>(Arrays.asList(env.getActiveProfiles()));
        for (String p : profiles) {
            if (existing.add(p)) env.addActiveProfile(p);
        }
        log.info("==>[argus] Activated profiles from ECS tags: " + profiles);
    }


}