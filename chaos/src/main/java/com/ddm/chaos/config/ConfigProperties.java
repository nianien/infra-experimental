package com.ddm.chaos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * chaos.options.* 配置绑定类
 * <p>
 * 对应 YAML:
 * chaos:
 * options:
 * provider:
 * type: jdbc
 * namespace: com.ddm
 * group: cfd
 * tags: [ "gray", "hotfix" ]
 * options:
 * url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
 * username: sa
 * password: ""
 * init_sql: "true"
 * ttl: 30S
 */
@ConfigurationProperties(prefix = "chaos.config-center")
public record ConfigProperties(

        /** 命名空间（可选） */
        String namespace,

        /** 分组名称（可选） */
        String[] groups,

        /** 标签（如 gray、hotfix、beta 等） */
        String[] tags,

        /** 全局配置缓存 TTL */
        @DurationUnit(ChronoUnit.SECONDS)
        Duration ttl,

        /** 配置提供者定义 */
        Provider provider

) {

    /**
     * provider.* 节点
     */
    public record Provider(
            /** 提供者类型（jdbc / redis / git / api 等） */
            String type,
            /** provider.options.* 动态参数映射 */
            Map<String, String> options
    ) {
    }

}

