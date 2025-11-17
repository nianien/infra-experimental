package com.ddm.argus.autoconfigure;

import com.ddm.argus.grpc.GrpcProperties;
import com.ddm.argus.grpc.LaneLoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import net.devh.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelConfigurer;
import net.devh.boot.grpc.client.config.GrpcChannelProperties;
import net.devh.boot.grpc.client.config.GrpcChannelsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.URI;

@AutoConfiguration
@AutoConfigureBefore(GrpcClientAutoConfiguration.class) // 确保在创建 Channel 前注册
@EnableConfigurationProperties(GrpcProperties.class)
public class GrpcLbAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GrpcLbAutoConfiguration.class);

    /**
     * 提前注册 Lane 感知负载均衡器（与是否在 ECS 无关）
     */
    @Bean
    public LaneLoadBalancerProvider laneAwareLoadBalancerProvider() {
        var p = new LaneLoadBalancerProvider();
        LoadBalancerRegistry.getDefaultRegistry().register(p);
        log.info("==>[Argus] Registered gRPC LoadBalancerProvider: {}", p.getPolicyName());
        return p;
    }

    @Bean
    public GrpcChannelConfigurer globalLoadBalancerConfigurer(GrpcChannelsProperties props) {
        return (builder, clientName) -> {
            GrpcChannelProperties ch = (clientName != null) ? props.getChannel(clientName) : null;
            URI uri = (ch != null) ? ch.getAddress() : null;
            String scheme = (uri != null) ? uri.getScheme() : null;
            log.info("==>[argus] client={} target={} scheme={}", clientName, uri, scheme);
            if ("cloud".equalsIgnoreCase(scheme)) {
                // cloud:/// → 只用 lane_round_robin
                builder.disableServiceConfigLookUp();  // 防止 TXT/serviceConfig 覆盖
                builder.defaultLoadBalancingPolicy("lane_round_robin");
            } else {
                // dns:/// 或直连，走默认（不强塞，保持最简单）
                // 如需明确：builder.defaultLoadBalancingPolicy("round_robin");
            }
        };
    }
}