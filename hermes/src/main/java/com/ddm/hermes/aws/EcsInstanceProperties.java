// src/main/java/com/ddm/hermes/aws/starter/EcsInstanceProperties.java
package com.ddm.hermes.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ecs.instance")
public class EcsInstanceProperties {
    private String clusterArn;
    private String taskArn;
    private String taskId;
    private String regionId;
    private String metaBase;
    private String serviceName;
    private String serviceArn;
    private String taskDefArn;
    private String containerName;
    private Integer containerPort;
    private String lane;

    // getters/setters
    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String v) { this.clusterArn = v; }
    public String getTaskArn() { return taskArn; }
    public void setTaskArn(String v) { this.taskArn = v; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String v) { this.taskId = v; }
    public String getRegionId() { return regionId; }
    public void setRegionId(String v) { this.regionId = v; }
    public String getMetaBase() { return metaBase; }
    public void setMetaBase(String v) { this.metaBase = v; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String v) { this.serviceName = v; }
    public String getServiceArn() { return serviceArn; }
    public void setServiceArn(String v) { this.serviceArn = v; }
    public String getTaskDefArn() { return taskDefArn; }
    public void setTaskDefArn(String v) { this.taskDefArn = v; }
    public String getContainerName() { return containerName; }
    public void setContainerName(String v) { this.containerName = v; }
    public Integer getContainerPort() { return containerPort; }
    public void setContainerPort(Integer v) { this.containerPort = v; }
    public String getLane() { return lane; }
    public void setLane(String v) { this.lane = v; }
}