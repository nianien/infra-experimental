package com.ddm.hermes.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.Task;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class EcsInstanceEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final Logger log = LoggerFactory.getLogger(EcsInstanceEnvironmentPostProcessor.class);

    public static final String META_ENV = "ECS_CONTAINER_METADATA_URI_V4";
    private static final String PS_NAME = "ecs-instance";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final ObjectMapper M = new ObjectMapper();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String metaBase = System.getenv(META_ENV);
        if (metaBase == null || metaBase.isBlank()) return;

        try {
            // 1) /task ⇒ clusterArn, taskArn
            HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
            JsonNode taskMeta = httpJson(http, metaBase + "/task");
            String clusterArn = text(taskMeta, "Cluster");
            String taskArn = text(taskMeta, "TaskARN");
            if (clusterArn == null || taskArn == null) {
                log.warn("ecs.instance: missing Cluster/TaskARN, skip.");
                return; // 失败就不上报：此处直接不注入
            }
            String taskId = taskArn.substring(taskArn.lastIndexOf('/') + 1);
            Region region = regionFromArn(clusterArn);

            String serviceName = null, serviceArn = null, taskDefArn = null, containerName = null, lane = "";
            Integer containerPort = null;

            // 2) 轻量 SDK 查询（DescribeTasks / DescribeServices / DescribeTaskDefinition）
            try (var ecs = AwsClientFactory.ecs(region)) {
                // DescribeTasks
                Task task = EcsMetadataService.getTask(ecs, clusterArn, taskArn).orElse(null);
                if (task != null && task.group() != null && task.group().startsWith("service:")) {
                    serviceName = task.group().substring("service:".length());
                    taskDefArn = task.taskDefinitionArn();

                    // DescribeServices + lane tag
                    Service svc = EcsMetadataService.getService(ecs, clusterArn, serviceName).orElse(null);
                    if (svc != null) {
                        serviceArn = svc.serviceArn();
                        lane = EcsMetadataService.getLaneTag(ecs, serviceArn).orElse("");
                    }
                    if (lane.isBlank()) lane = suffixAfterDash(serviceName);

                    // DescribeTaskDefinition
                    if (taskDefArn != null) {
                        ContainerDefinition appC = EcsMetadataService.getFirstPortContainer(ecs, taskDefArn).orElse(null);
                        if (appC != null) {
                            containerName = appC.name();
                            containerPort = appC.portMappings().get(0).containerPort();
                        }
                    }
                } else {
                    log.warn("ecs.instance: not a service task. group={}", task == null ? null : task.group());
                }
            } catch (Exception e) {
                log.debug("ecs.instance prefetch failed (ignored): {}", e.toString());
            }

            // 3) 合并到属性（能拿到啥就注啥；后续类会做完整性校验，缺失就不上报）
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("ecs.instance.cluster-arn", clusterArn);
            kv.put("ecs.instance.task-arn", taskArn);
            kv.put("ecs.instance.task-id", taskId);
            kv.put("ecs.instance.region-id", region.id());
            kv.put("ecs.instance.meta-base", metaBase);
            put(kv, "ecs.instance.service-name", serviceName);
            put(kv, "ecs.instance.service-arn", serviceArn);
            put(kv, "ecs.instance.task-def-arn", taskDefArn);
            put(kv, "ecs.instance.container-name", containerName);
            put(kv, "ecs.instance.container-port", containerPort);
            put(kv, "ecs.instance.lane", lane);

            env.getPropertySources().addFirst(new MapPropertySource(PS_NAME, kv));
            log.info("ecs.instance injected: region={}, service={}, container={}:{}, lane='{}'",
                    region.id(), serviceName, containerName, containerPort, lane);
        } catch (Exception e) {
            log.warn("ecs.instance inject failed: {}", e.toString(), e);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    // helpers
    private static JsonNode httpJson(HttpClient http, String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).GET().build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return M.readTree(res.body());
    }

    private static String text(JsonNode root, String field) {
        if (root == null) return null;
        var n = root.path(field);
        return n.isMissingNode() ? null : n.asText();
    }

    private static Region regionFromArn(String arn) {
        String[] p = arn.split(":");
        if (p.length > 3 && p[3] != null && !p[3].isBlank()) return Region.of(p[3]);
        throw new IllegalStateException("Cannot parse region from ARN: " + arn);
    }

    private static String suffixAfterDash(String s) {
        if (s == null) return null;
        int i = s.lastIndexOf('-');
        return (i > 0 && i < s.length() - 1) ? s.substring(i + 1) : "";
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }
}