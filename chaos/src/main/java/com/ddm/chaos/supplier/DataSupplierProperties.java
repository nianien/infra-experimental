package com.ddm.chaos.supplier;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * DataSupplierProperties (record 版本)
 *
 * <p>对应 YAML：
 * chaos:
 * supplier:
 * ttl: 60s
 * provider: com.ddm.chaos.JdbcDataProvider
 * config:
 * url: jdbc:mysql://localhost:3306/config_center
 * username: root
 * password: root
 */
@ConfigurationProperties(prefix = "chaos.supplier")
public record DataSupplierProperties(
        /** 缓存刷新间隔（Duration 格式，例如 60s、5m、1h），默认 60 秒，0 表示禁用自动刷新。 */
        Duration ttl,

        /** DataProvider 实现类全限定名（通过 SPI 或反射加载） */
        String provider,

        /** Provider 初始化参数（如 url、username、password 等） */
        Map<String, String> config
) {
    public DataSupplierProperties {
        // 提供默认值：防止 YAML 未配置时为 null
        if (ttl == null) ttl = Duration.ofSeconds(60);
        if (config == null) config = Map.of();
    }


    @Override
    public String toString() {
        return "DataSupplierProperties{" +
                "ttl=" + ttl +
                ", provider='" + provider + '\'' +
                ", config=" + config +
                '}';
    }
}