package com.ddm.argus.autoconfigure;

import com.ddm.argus.grpc.GrpcProperties;
import com.ddm.argus.grpc.LaneAwareLoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@AutoConfiguration
@AutoConfigureBefore(GrpcClientAutoConfiguration.class) // 确保在创建 Channel 前注册
@EnableConfigurationProperties(GrpcProperties.class)
public class GrpcLbAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GrpcLbAutoConfiguration.class);

    /**
     * 提前注册 Lane 感知负载均衡器（与是否在 ECS 无关）
     * 建议配合 SPI 同时存在，双保险
     */
    @Bean
    public LaneAwareLoadBalancerProvider laneAwareLoadBalancerProvider() {
        var p = new LaneAwareLoadBalancerProvider();
        LoadBalancerRegistry.getDefaultRegistry().register(p);
        log.info("==>[Argus] Registered gRPC LoadBalancerProvider: {}", p.getPolicyName());
        return p;
    }

    /**
     * 全局负载均衡策略（默认 lane_round_robin）。
     * 若策略未被注册（极端情况下），安全回退到 round_robin，避免启动失败。
     */
    @Bean
    public GrpcChannelConfigurer globalLoadBalancerConfigurer(GrpcProperties props) {
        return (builder, clientName) -> {
            String policy = props.getLoadbalance().getPolicy();
            if (policy == null || policy.isBlank()) {
                policy = LaneAwareLoadBalancerProvider.POLICY; // "lane_round_robin"
            }
            if (LoadBalancerRegistry.getDefaultRegistry().getProvider(policy) == null) {
                throw new IllegalStateException("gRPC LB policy '" + policy + "' is not registered. Refuse to fallback to 'round_robin' to protect lane isolation.");
            }
            builder.defaultServiceConfig(Map.of("loadBalancingPolicy", policy));
        };
    }
}