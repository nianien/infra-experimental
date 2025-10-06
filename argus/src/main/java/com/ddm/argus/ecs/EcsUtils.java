package com.ddm.argus.ecs;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 工具类：封装对 ECS 任务/服务定义与标签等元数据的查询。
 * 所有方法尽量空安全，必要时返回 Optional。
 */
public final class EcsUtils {
    private EcsUtils() {
    }

    private static final ClientOverrideConfiguration CFG =
            ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(6))
                    .build();

    public static EcsClient ecs(Region region) {
        return EcsClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(CFG)
                .build();
    }

    public static ServiceDiscoveryClient serviceDiscovery(Region region) {
        return ServiceDiscoveryClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(CFG)
                .build();
    }

    public static Optional<Task> getTask(EcsClient ecs, String clusterArn, String taskArn) {
        var resp = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterArn).tasks(taskArn).build());
        return resp.tasks() == null ? Optional.empty() : resp.tasks().stream().findFirst();
    }

    public static Optional<Service> getService(EcsClient ecs, String clusterArn, String serviceName) {
        var resp = ecs.describeServices(DescribeServicesRequest.builder()
                .cluster(clusterArn).services(serviceName).build());
        return resp.services() == null ? Optional.empty() : resp.services().stream().findFirst();
    }

    public static List<Tag> listTagsForResource(EcsClient ecs, String resourceArn) {
        var resp = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(resourceArn).build());
        return resp.tags();
    }


    public static Optional<TaskDefinition> getTaskDefinition(EcsClient ecs, String taskDefArn) {
        var td = ecs.describeTaskDefinition(
                DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefArn).build()
        ).taskDefinition();
        return Optional.ofNullable(td);
    }

    public static Optional<ContainerDefinition> getFirstPortContainer(TaskDefinition td) {
        if (td == null || td.containerDefinitions() == null) return Optional.empty();
        return td.containerDefinitions().stream()
                .filter(cd -> cd.portMappings() != null && !cd.portMappings().isEmpty())
                .findFirst();
    }

    public static Optional<String> getPrivateIp(EcsClient ecs, String clusterArn, String taskArn, String containerName) {
        var dt = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterArn).tasks(taskArn).build());
        if (dt.tasks() == null || dt.tasks().isEmpty()) return Optional.empty();
        Task t = dt.tasks().get(0);
        return t.containers().stream()
                .filter(c -> containerName.equals(c.name()))
                .findFirst()
                .map(c -> (c.networkInterfaces() == null || c.networkInterfaces().isEmpty()) ? null
                        : c.networkInterfaces().get(0).privateIpv4Address())
                .filter(ip -> ip != null && !ip.isBlank());
    }
}