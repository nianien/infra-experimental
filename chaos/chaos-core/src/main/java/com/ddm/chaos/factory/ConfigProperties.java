package com.ddm.chaos.factory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * 配置中心主配置绑定类，对应属性前缀：{@code chaos.config-center.*}
 * <p>
 * 该配置类用于绑定 Spring Boot 配置文件中的配置中心相关属性。
 * 通过 {@code @EnableConfigurationProperties(ConfigProperties.class)} 启用配置绑定。
 *
 * <p><strong>示例 YAML 配置：</strong>
 * <pre>{@code
 * chaos:
 *   config-center:
 *     profiles: [ "gray", "hotfix" ]
 *     ttl: 30S
 * }</pre>
 *
 * <p><strong>配置说明：</strong>
 * <ul>
 *   <li>{@code profiles}：泳道标识数组，用于配置项的变体匹配，按优先级顺序排列</li>
 *   <li>{@code ttl}：配置缓存 TTL，单位秒，用于控制缓存刷新频率</li>
 * </ul>
 *
 * @author liyifei
 * @see DefaultConfigFactory
 * @since 1.0
 */
@ConfigurationProperties(prefix = "chaos.config-center")
public record ConfigProperties(

        /**
         * 泳道标识数组，用于配置项的变体匹配。
         * <p>
         * 泳道标识按优先级顺序排列，在解析配置时会按顺序在 variants 中查找匹配的泳道值。
         * 例如：["gray", "hotfix"] 表示优先使用 gray 泳道的值，如果不存在则使用 hotfix 泳道的值。
         * <p>
         * 匹配规则：
         * <ol>
         *   <li>遍历 profiles 数组，在配置项的 variants JSON 中查找匹配的泳道标识</li>
         *   <li>如果找到匹配的泳道值，使用该值作为配置的生效值</li>
         *   <li>如果所有泳道都未匹配，使用配置项的默认 value</li>
         * </ol>
         * <p>
         * 示例：如果配置项的 variants 为 {"gray":"value1", "blue":"value2"}，
         * profiles 为 ["gray", "hotfix"]，则匹配到 "gray" 泳道，使用 "value1"。
         * <p>
         * 可以为 null 或空数组，此时不会进行变体匹配，直接使用配置项的默认 value。
         */
        String[] profiles,

        /**
         * 全局配置缓存 TTL（Time To Live）。
         * <p>
         * 配置数据在缓存中的存活时间，超过该时间后会触发异步刷新。
         * 单位：秒（通过 {@code @DurationUnit(ChronoUnit.SECONDS)} 指定）。
         * <p>
         * 刷新机制：
         * <ul>
         *   <li>使用 Caffeine 的 refreshAfterWrite 策略</li>
         *   <li>超过 TTL 后首次访问时触发异步刷新</li>
         *   <li>刷新期间返回旧值，不影响读取性能</li>
         * </ul>
         * <p>
         * 不能为 null，必须在配置文件中指定。
         */
        @DurationUnit(ChronoUnit.SECONDS)
        Duration ttl) {


}

