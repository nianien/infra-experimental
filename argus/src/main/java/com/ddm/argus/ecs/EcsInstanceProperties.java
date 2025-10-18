package com.ddm.argus.ecs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ECS 实例元信息（由 EnvironmentPostProcessor 注入）。
 * 前缀：ecs.instance
 */
@ConfigurationProperties(prefix = "ecs.instance")
public record EcsInstanceProperties(
        String clusterArn,
        String taskArn,
        String taskId,
        String regionId,
        String metaBase,
        String serviceName,
        String serviceArn,
        String taskDefArn,
        String containerName,
        String containerHost,
        Integer containerPort,
        String lane
) {
}