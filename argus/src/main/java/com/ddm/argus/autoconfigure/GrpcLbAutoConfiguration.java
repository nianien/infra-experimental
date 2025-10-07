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

import java.util.List;
import java.util.Map;

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
    public GrpcChannelConfigurer globalLoadBalancerConfigurer() {
        return (builder, clientName) -> {
            // gRPC会自动设置 target（dns:/// 或 cloud:///）
            String target = builder.toString().toLowerCase();
            //当地址为"cloud:///"协议时,采取lane_round_robin策略, 其他情况使用框架默认策略
            if (target.contains("cloud:///")) {
                // cloud:/// → lane_round_robin
                builder.disableServiceConfigLookUp();
                builder.defaultServiceConfig(Map.of(
                        "loadBalancingConfig",
                        List.of(Map.of("lane_round_robin", Map.of()))
                ));
            }
        };
    }
}