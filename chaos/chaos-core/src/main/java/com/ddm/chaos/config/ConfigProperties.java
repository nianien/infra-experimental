package com.ddm.chaos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 配置中心主配置绑定类，对应属性前缀：{@code chaos.config-center.*}
 * <p>
 * 该配置类用于绑定 Spring Boot 配置文件中的配置中心相关属性。
 *
 * <p><strong>示例 YAML 配置：</strong>
 * <pre>{@code
 * chaos:
 *   config-center:
 *     tags: [ "gray", "hotfix" ]
 *     ttl: 30S
 *     provider:
 *       type: jdbc
 *       options:
 *         jdbc-url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
 *         username: sa
 *         password: ""
 * }</pre>
 *
 * @author liyifei
 * @since 1.0
 */
@ConfigurationProperties(prefix = "chaos.config-center")
public record ConfigProperties(

        /**
         * 标签数组，用于配置项的变体匹配。
         * <p>
         * 标签按优先级顺序排列，在解析配置时会按顺序在 variants 中查找匹配的标签值。
         * 例如：["gray", "hotfix"] 表示优先使用 gray 标签的值，如果不存在则使用 hotfix 标签的值。
         */
        String[] tags,

        /**
         * 全局配置缓存 TTL（Time To Live）。
         * <p>
         * 配置数据在缓存中的存活时间，超过该时间后会触发异步刷新。
         * 单位：秒（通过 {@code @DurationUnit(ChronoUnit.SECONDS)} 指定）。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        Duration ttl) {


}

