package com.ddm.argus.grpc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * argus.grpc.*
 * 统一配置 gRPC 名称解析和负载均衡策略。
 */
@ConfigurationProperties(prefix = "argus.grpc")
public class GrpcProperties {

    private final Loadbalance loadbalance = new Loadbalance();
    private final Resolver resolver = new Resolver();

    public Loadbalance getLoadbalance() {
        return loadbalance;
    }

    public Resolver getResolver() {
        return resolver;
    }

    // ====== 子配置类 ======

    /**
     * argus.grpc.loadbalance.*
     */
    public static class Loadbalance {
        /**
         * 全局负载均衡策略，默认 lane_round_robin
         */
        private String policy = "lane_round_robin";

        public String getPolicy() {
            return policy;
        }

        public void setPolicy(String policy) {
            this.policy = policy;
        }
    }

    /**
     * argus.grpc.resolver.*
     */
    public static class Resolver {
        /**
         * 名称解析刷新间隔（如 10s、500ms、1m）
         */
        private String refreshInterval = "10s";

        public String getRefreshInterval() {
            return refreshInterval;
        }

        public void setRefreshInterval(String refreshInterval) {
            this.refreshInterval = refreshInterval;
        }
    }
}