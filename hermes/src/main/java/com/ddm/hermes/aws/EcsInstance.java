// src/main/java/com/ddm/hermes/aws/starter/EcsInstance.java
package com.ddm.hermes.aws;

public record EcsInstance(
        String clusterArn,
        String taskArn,
        String taskId,
        String regionId,
        String metaBase,
        String serviceName,
        String serviceArn,
        String taskDefArn,
        String containerName,
        Integer containerPort,
        String lane
) {}