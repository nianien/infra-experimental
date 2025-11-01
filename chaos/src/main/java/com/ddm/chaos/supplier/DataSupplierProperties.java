package com.ddm.chaos.supplier;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * 动态 Supplier 的配置属性类。
 * 
 * <p>该类使用 Spring Boot 的 {@code @ConfigurationProperties} 注解，自动绑定配置文件中
 * {@code chaos.supplier} 前缀下的配置项。
 * 
 * <p><strong>配置示例（application.yml）：</strong>
 * <pre>{@code
 * chaos:
 *   supplier:
 *     ttl: 60s                    # 缓存刷新间隔，支持 60s、5m、1h 等格式，0 表示禁用自动刷新
 *     provider: com.ddm.chaos.provider.jdbc.JdbcDataProvider  # DataProvider 实现类全限定名
 *     config:                     # DataProvider 初始化参数
 *       url: jdbc:mysql://localhost:3306/config_center
 *       username: root
 *       password: root
 *       groups: default,prod      # 可选的配置组列表（逗号分隔）
 * }</pre>
 * 
 * <p><strong>字段说明：</strong>
 * <ul>
 *   <li>{@code ttl}：缓存刷新间隔，使用 Duration 格式（如 60s、5m、1h），默认 60 秒。
 *       设置为 0 或负数表示禁用自动刷新，仅在启动时加载一次。</li>
 *   <li>{@code provider}：DataProvider 实现类的全限定名，通过 SPI 机制加载。
 *       必须通过 META-INF/services/com.ddm.chaos.provider.DataProvider 文件注册。</li>
 *   <li>{@code config}：传递给 DataProvider 的初始化参数 Map，具体参数由 DataProvider 实现决定。</li>
 * </ul>
 * 
 * <p><strong>使用方式：</strong>
 * <pre>{@code
 * @ConfigurationProperties(prefix = "chaos.supplier")
 * @Bean
 * public DataSupplierProperties properties() {
 *     return new DataSupplierProperties(...);
 * }
 * }</pre>
 * 
 * @author liyifei
 * @since 1.0
 * @see org.springframework.boot.context.properties.ConfigurationProperties
 */
@ConfigurationProperties(prefix = "chaos.supplier")
public record DataSupplierProperties(
        /**
         * 缓存刷新间隔（Duration 格式，例如 60s、5m、1h）。
         * 
         * <p>支持以下格式：
         * <ul>
         *   <li>纯数字：默认单位为秒，如 {@code 60} 表示 60 秒</li>
         *   <li>带单位：如 {@code 100ms}、{@code 5s}、{@code 10m}、{@code 1h}、{@code 2d}</li>
         *   <li>ISO-8601：如 {@code PT10S}、{@code PT1H30M}</li>
         * </ul>
         * 
         * <p>默认值为 60 秒。设置为 0 或负数表示禁用自动刷新，仅在启动时加载一次配置。
         */
        Duration ttl,

        /**
         * DataProvider 实现类全限定名。
         * 
         * <p>该类必须：
         * <ul>
         *   <li>实现 {@link com.ddm.chaos.provider.DataProvider} 接口</li>
         *   <li>通过 SPI 机制注册（META-INF/services/com.ddm.chaos.provider.DataProvider）</li>
         * </ul>
         * 
         * <p>示例：{@code com.ddm.chaos.provider.jdbc.JdbcDataProvider}
         */
        String provider,

        /**
         * Provider 初始化参数 Map。
         * 
         * <p>该 Map 会传递给 DataProvider 的 {@link com.ddm.chaos.provider.DataProvider#initialize(Map)} 方法。
         * 具体需要哪些参数由 DataProvider 实现决定。
         * 
         * <p>常见参数示例：
         * <ul>
         *   <li>JDBC 数据源：url、username、password、groups 等</li>
         *   <li>HTTP API：baseUrl、apiKey、timeout 等</li>
         *   <li>Redis：host、port、password、database 等</li>
         * </ul>
         */
        Map<String, String> config
) {
    /**
     * 构造函数，提供默认值以确保配置缺失时也能正常工作。
     * 
     * <p>默认值策略：
     * <ul>
     *   <li>{@code ttl}：如果为 null，默认设置为 60 秒</li>
     *   <li>{@code config}：如果为 null，默认设置为空 Map</li>
     * </ul>
     */
    public DataSupplierProperties {
        // 提供默认值：防止 YAML 未配置时为 null
        if (ttl == null) {
            ttl = Duration.ofSeconds(60);
        }
        if (config == null) {
            config = Map.of();
        }
    }

    /**
     * 返回对象的字符串表示，用于日志输出和调试。
     * 
     * @return 包含所有字段值的字符串表示
     */
    @Override
    public String toString() {
        return "DataSupplierProperties{" +
                "ttl=" + ttl +
                ", provider='" + provider + '\'' +
                ", config=" + config +
                '}';
    }
}
