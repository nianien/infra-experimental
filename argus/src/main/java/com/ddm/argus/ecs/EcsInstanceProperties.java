package com.ddm.argus.ecs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置项（前缀：ecs.instance）：
 * 由启动阶段注入的 ECS 任务/服务元数据（集群 ARN、任务 ARN、私网端口、lane 等）。
 */
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

    public String getClusterArn() {
        return clusterArn;
    }

    public void setClusterArn(String clusterArn) {
        this.clusterArn = clusterArn;
    }

    public String getTaskArn() {
        return taskArn;
    }

    public void setTaskArn(String taskArn) {
        this.taskArn = taskArn;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getRegionId() {
        return regionId;
    }

    public void setRegionId(String regionId) {
        this.regionId = regionId;
    }

    public String getMetaBase() {
        return metaBase;
    }

    public void setMetaBase(String metaBase) {
        this.metaBase = metaBase;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceArn() {
        return serviceArn;
    }

    public void setServiceArn(String serviceArn) {
        this.serviceArn = serviceArn;
    }

    public String getTaskDefArn() {
        return taskDefArn;
    }

    public void setTaskDefArn(String taskDefArn) {
        this.taskDefArn = taskDefArn;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public Integer getContainerPort() {
        return containerPort;
    }

    public void setContainerPort(Integer containerPort) {
        this.containerPort = containerPort;
    }

    public String getLane() {
        return lane;
    }

    public void setLane(String lane) {
        this.lane = lane;
    }
}