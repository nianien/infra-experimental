package com.ddm.chaos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

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
        Duration ttl,

        /**
         * 配置提供者定义。
         * <p>
         * 指定配置数据的来源，如 JDBC、Redis、Git、HTTP API 等。
         */
        Provider provider

) {

    /**
     * 配置提供者定义。
     * <p>
     * 包含提供者类型和初始化选项。
     *
     * @param type    提供者类型，如 "jdbc"、"redis"、"git"、"api" 等
     * @param options 提供者初始化选项，键值对形式，具体选项取决于提供者类型
     */
    public record Provider(
            /**
             * 提供者类型，不区分大小写。
             * <p>
             * 支持的提供者类型通过 SPI 机制加载，常见类型包括：
             * <ul>
             *   <li>jdbc：从关系型数据库读取配置</li>
             *   <li>redis：从 Redis 读取配置</li>
             *   <li>git：从 Git 仓库读取配置</li>
             *   <li>api：通过 HTTP API 读取配置</li>
             * </ul>
             */
            String type,
            
            /**
             * 提供者初始化选项。
             * <p>
             * 键值对形式，具体选项取决于提供者类型。例如：
             * <ul>
             *   <li>jdbc 类型：jdbc-url、username、password 等</li>
             *   <li>redis 类型：host、port、password 等</li>
             * </ul>
             */
            Map<String, String> options
    ) {
    }

}

