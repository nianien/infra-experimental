package com.ddm.chaos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 配置中心主配置绑定类，对应属性前缀：{@code chaos.config-center.*}
 *
 * <p>示例 YAML 配置：
 *
 * <pre>{@code
 * chaos:
 *   config-center:
 *     namespace: com.ddm
 *     groups: [ "cfd" ]
 *     tags: [ "gray", "hotfix" ]
 *     ttl: 30S
 *     provider:
 *       type: jdbc
 *       options:
 *         jdbc-url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
 *         username: sa
 *         password: ""
 * }</pre>
 */
@ConfigurationProperties(prefix = "chaos.config-center")
public record ConfigProperties(

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

