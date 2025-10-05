package com.ddm.hermes.aws;

import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

import java.util.Optional;

public final class EcsMetadataService {
    private EcsMetadataService() {}

    public static Optional<Task> getTask(EcsClient ecs, String clusterArn, String taskArn) {
        var resp = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterArn).tasks(taskArn).build());
        return resp.tasks().stream().findFirst();
    }

    public static Optional<Service> getService(EcsClient ecs, String clusterArn, String serviceName) {
        var resp = ecs.describeServices(DescribeServicesRequest.builder()
                .cluster(clusterArn).services(serviceName).build());
        return resp.services().stream().findFirst();
    }

    public static Optional<String> getLaneTag(EcsClient ecs, String serviceArn) {
        var tags = ecs.listTagsForResource(ListTagsForResourceRequest.builder()
                .resourceArn(serviceArn).build()).tags();
        if (tags == null) return Optional.empty();
        return tags.stream()
                .filter(t -> t.key() != null && t.key().equalsIgnoreCase("lane"))
                .map(Tag::value).filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    public static Optional<ContainerDefinition> getFirstPortContainer(EcsClient ecs, String taskDefArn) {
        TaskDefinition td = ecs.describeTaskDefinition(
                DescribeTaskDefinitionRequest.builder().taskDefinition(taskDefArn).build()
        ).taskDefinition();
        return td.containerDefinitions().stream()
                .filter(cd -> cd.portMappings() != null && !cd.portMappings().isEmpty())
                .findFirst();
    }

    public static Optional<String> getPrivateIp(EcsClient ecs, String clusterArn, String taskArn, String containerName) {
        var dt = ecs.describeTasks(DescribeTasksRequest.builder()
                .cluster(clusterArn).tasks(taskArn).build());
        if (dt.tasks().isEmpty()) return Optional.empty();
        Task t = dt.tasks().get(0);
        return t.containers().stream()
                .filter(c -> containerName.equals(c.name()))
                .findFirst()
                .map(c -> (c.networkInterfaces() == null || c.networkInterfaces().isEmpty()) ? null
                        : c.networkInterfaces().get(0).privateIpv4Address());
    }
}