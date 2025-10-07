package com.ddm.argus.autoconfigure;

import com.ddm.argus.grpc.GrpcProperties;
import com.ddm.argus.grpc.LaneLoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
    public LaneLoadBalancerProvider laneAwareLoadBalancerProvider() {
        var p = new LaneLoadBalancerProvider();
        LoadBalancerRegistry.getDefaultRegistry().register(p);
        log.info("==>[Argus] Registered gRPC LoadBalancerProvider: {}", p.getPolicyName());
        return p;
    }

    @Bean
    public GrpcChannelConfigurer globalLoadBalancerConfigurer(GrpcProperties props) {
        return (builder, clientName) -> {
            // 只允许 lane_round_robin
            String policy = props.getLoadbalance().getPolicy();
            if (policy == null || policy.isBlank()) {
                policy = LaneLoadBalancerProvider.POLICY; // "lane_round_robin"
            }
            if (!LaneLoadBalancerProvider.POLICY.equals(policy)) {
                throw new IllegalArgumentException(
                        "Only '" + LaneLoadBalancerProvider.POLICY + "' is allowed (no fallback). Got: " + policy);
            }
            // 必须已注册，否则直接失败，避免静默回退打乱泳道
            if (io.grpc.LoadBalancerRegistry.getDefaultRegistry().getProvider(policy) == null) {
                throw new IllegalStateException(
                        "LB policy '" + policy + "' is not registered. Refusing to fallback.");
            }

            // 禁用 NameResolver/DNS TXT 下发的 service config，避免被覆盖
            builder.disableServiceConfigLookUp();

            // 只配置 lane_round_robin（没有任何回退项）
            builder.defaultServiceConfig(java.util.Map.of(
                    "loadBalancingConfig",
                    java.util.List.of(java.util.Map.of(LaneLoadBalancerProvider.POLICY, java.util.Map.of()))
            ));
        };
    }
}