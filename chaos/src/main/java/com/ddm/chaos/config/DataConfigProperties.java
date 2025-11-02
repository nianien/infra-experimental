package com.ddm.chaos.config;

import com.ddm.chaos.provider.DataProvider.ProviderConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * chaos.config.* 配置绑定类
 * <p>
 * 对应 YAML:
 * chaos:
 * config:
 * provider:
 * type: jdbc
 * namespace: com.ddm
 * group: cfd
 * tags: [ "gray", "hotfix" ]
 * config:
 * url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
 * username: sa
 * password: ""
 * init_sql: "true"
 * ttl: 30S
 */
@ConfigurationProperties(prefix = "chaos.config")
public record DataConfigProperties(

        /** 配置提供者定义 */
        ProviderConfig provider,

        /** 全局配置缓存 TTL */
        @DurationUnit(ChronoUnit.SECONDS)
        Duration ttl
) {
}