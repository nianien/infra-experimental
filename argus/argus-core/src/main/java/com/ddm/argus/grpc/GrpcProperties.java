package com.ddm.argus.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * gRPC 统一配置
 */
@ConfigurationProperties(prefix = "argus.grpc")
public class GrpcProperties {

    private final Resolver resolver = new Resolver();


    public Resolver getResolver() {
        return resolver;
    }

    /**
     * argus.grpc.resolver.*
     */
    public static class Resolver {
        /**
         * 名称解析刷新间隔（如 10s、500ms、1m）
         */
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration refreshInterval = Duration.ofSeconds(30);

        public Duration getRefreshInterval() {
            return refreshInterval;
        }

        public void setRefreshInterval(Duration refreshInterval) {
            this.refreshInterval = refreshInterval;
        }
    }
}