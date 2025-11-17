package com.ddm.argus.utils;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import static com.ddm.argus.utils.CommonUtils.notBlank;

/**
 * 工具类：封装对 ECS 任务/服务定义与标签等元数据的查询。
 * - 空安全优先：必要时返回 null 或 Optional（看方法风格）。
 * - 注意：创建的 SDK Client 需要由调用方关闭（try-with-resources）。
 */
public final class EcsUtils {

    private EcsUtils() {
    }

    private static final ClientOverrideConfiguration CFG =
            ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(6))
                    .build();

    /**
     * 由调用方负责关闭（try-with-resources）。
     */
    public static EcsClient ecs(Region region) {
        return EcsClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(CFG)
                .build();
    }

    /**
     * 由调用方负责关闭（try-with-resources）。
     */
    public static ServiceDiscoveryClient serviceDiscovery(Region region) {
        return ServiceDiscoveryClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(CFG)
                .build();
    }

    // ---------- 基础解析 ----------

    public static Region regionFromArn(String arn) {
        final String[] p = arn.split(":");
        if (p.length > 3 && notBlank(p[3])) return Region.of(p[3]);
        throw new IllegalArgumentException("Cannot parse region from ARN: " + arn);
    }

    // ---------- ECS 查询：统一"fetch"风格：找不到返回 null ----------

    public static Task fetchTask(EcsClient ecs, String clusterArn, String taskArn) {
        var dt = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterArn)
                .tasks(taskArn)
                .build());
        if (dt.tasks() == null || dt.tasks().isEmpty()) return null;
        return dt.tasks().get(0);
    }

    public static Service fetchService(EcsClient ecs, String clusterArn, String serviceName) {
        var ds = ecs.describeServices(DescribeServicesRequest.builder()
                .cluster(clusterArn)
                .services(serviceName)
                .build());
        if (ds.services() == null || ds.services().isEmpty()) return null;
        return ds.services().get(0);
    }

    public static TaskDefinition fetchTaskDefinition(EcsClient ecs, String taskDefArn) {
        return ecs.describeTaskDefinition(DescribeTaskDefinitionRequest.builder()
                .taskDefinition(taskDefArn)
                .build()).taskDefinition();
    }

    // （可选）保留一个 Optional 风格的入口，便于某些调用方直接用 Optional。
    public static Optional<Service> getService(EcsClient ecs, String clusterArn, String serviceName) {
        return Optional.ofNullable(fetchService(ecs, clusterArn, serviceName));
    }

    // ---------- 容器与端口解析 ----------

    /**
     * 先在 TD 里选出目标容器：优先用 preferName 命中且有端口；否则挑第一个有端口的。
     */
    public static ContainerDefinition pickContainer(TaskDefinition td, String preferName) {
        if (td == null || td.containerDefinitions() == null) return null;

        if (notBlank(preferName)) {
            var hit = td.containerDefinitions().stream()
                    .filter(cd -> preferName.equals(cd.name()))
                    .filter(EcsUtils::hasPorts)
                    .findFirst();
            if (hit.isPresent()) return hit.get();
        }

        return td.containerDefinitions().stream()
                .filter(EcsUtils::hasPorts)
                .findFirst()
                .orElse(null);
    }

    public static String resolveContainerName(ContainerDefinition cd) {
        return (cd == null) ? null : cd.name();
    }

    /**
     * 从选中的容器取端口：GRPC 优先，否则第一个。
     */
    public static Integer resolveContainerPort(ContainerDefinition cd) {
        if (!hasPorts(cd)) return null;
        return cd.portMappings().stream()
                .filter(pm -> ApplicationProtocol.GRPC.equals(pm.appProtocol()))
                .map(PortMapping::containerPort)
                .findFirst()
                .orElseGet(() -> cd.portMappings().get(0).containerPort());
    }

    private static boolean hasPorts(ContainerDefinition cd) {
        return cd != null && cd.portMappings() != null && !cd.portMappings().isEmpty();
    }

    /**
     * 从 Task 中查找指定名称的容器；若 containerName 为 null，则返回第一个容器。
     */
    public static Container findContainer(Task task, String containerName) {
        if (task == null || task.containers() == null) return null;
        return task.containers().stream()
                .filter(c -> containerName == null || Objects.equals(containerName, c.name()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 从 Task 里兜底拿 IP：
     * - 优先：指定容器的 networkInterfaces[0].privateIpv4Address；
     * - 其次：按该容器的 ENI attachmentId 精确匹配到 attachments 里的 ENI，取 privateIPv4Address。
     */
    public static String ipFromTask(Task task, String selfContainerName) {
        if (task == null) return null;

        String ip = null;
        final String[] eniAttachmentId = {null};

        // 1) 指定容器优先
        Container target = findContainer(task, selfContainerName);
        if (target != null && target.networkInterfaces() != null && !target.networkInterfaces().isEmpty()) {
            var ni = target.networkInterfaces().get(0);
            ip = ni.privateIpv4Address();
            eniAttachmentId[0] = ni.attachmentId();
        }
        if (ip != null) return ip;

        // 2) ENI 兜底（尽量匹配同一 attachmentId）
        if (task.attachments() != null) {
            return task.attachments().stream()
                    .filter(a -> "ElasticNetworkInterface".equals(a.type()))
                    .filter(a -> eniAttachmentId[0] == null || Objects.equals(eniAttachmentId[0], a.id()))
                    .flatMap(a -> a.details().stream())
                    .filter(d -> "privateIPv4Address".equals(d.name()))
                    .map(KeyValuePair::value)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

}