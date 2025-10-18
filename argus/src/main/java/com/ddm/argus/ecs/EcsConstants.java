// src/main/java/com/ddm/argus/ecs/EcsConstants.java
package com.ddm.argus.ecs;

import java.time.Duration;

/**
 * {ECS_TASK_DEFINITION_FAMILY=demo-order-rpc-task, ECS_CLUSTER_NAME=demo-cluster, AVAILABILITY_ZONE=us-east-1b, AWS_INSTANCE_PORT=8081, ECS_SERVICE_NAME=demo-order-rpc-default, AWS_INSTANCE_IPV4=10.0.22.242, AWS_INIT_HEALTH_STATUS=HEALTHY, REGION=us-east-1}
 */
public final class EcsConstants {
    private EcsConstants() {
    }

    // ===== Metadata JSON fields =====
    public static final String META_FIELD_CLUSTER = "Cluster";
    public static final String META_FIELD_TASK_ARN = "TaskARN";

    // ===== Tag keys =====
    public static final String TAG_LANE = "lane";
    public static final String TAG_PROFILE = "profile";

    // ===== PropertySource name & property keys =====
    public static final String PS_ECS_INSTANCE = "ecs-instance";
    public static final String PROP_PREFIX = "ecs.instance.";
    public static final String PROP_CLUSTER_ARN = PROP_PREFIX + "cluster-arn";
    public static final String PROP_TASK_ARN = PROP_PREFIX + "task-arn";
    public static final String PROP_TASK_ID = PROP_PREFIX + "task-id";
    public static final String PROP_REGION_ID = PROP_PREFIX + "region-id";
    public static final String PROP_META_BASE = PROP_PREFIX + "meta-base";
    public static final String PROP_SERVICE_NAME = PROP_PREFIX + "service-name";
    public static final String PROP_SERVICE_ARN = PROP_PREFIX + "service-arn";
    public static final String PROP_TASK_DEF_ARN = PROP_PREFIX + "task-def-arn";
    public static final String PROP_CONTAINER_NAME = PROP_PREFIX + "container-name";
    public static final String PROP_CONTAINER_PORT = PROP_PREFIX + "container-port";
    public static final String PROP_CONTAINER_HOST = PROP_PREFIX + "container-host";
    public static final String PROP_LANE = PROP_PREFIX + "lane";
    public static final String PROP_PROFILES = PROP_PREFIX + "profiles";

    // ===== Defaults / Timeouts =====
    public static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration SDK_TIMEOUT = Duration.ofSeconds(6);

    /**
     * 环境变量：ECS 元数据 URI (v4)
     */
    public static final String ENV_ECS_METADATA_V4 = "ECS_CONTAINER_METADATA_URI_V4";

    /**
     * Cloud Map 实例属性：IPv4 地址
     */
    public static final String CM_ATTR_IPV4 = "AWS_INSTANCE_IPV4";

    /**
     * Cloud Map 实例属性：端口号
     */
    public static final String CM_ATTR_PORT = "AWS_INSTANCE_PORT";


    /**
     * Cloud Map 实例属性：泳道
     */
    public static final String CM_ATTR_LANE = TAG_LANE;

    /**
     * 兼容字段：备用 IPv4（部分环境会写成 ipv4）
     */
    public static final String CM_ATTR_IPV4_FALLBACK = "ipv4";
}