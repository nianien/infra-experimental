package com.ddm.argus.autoconfigure;

import com.ddm.argus.ecs.EcsRegistrar;
import com.ddm.argus.ecs.EcsInstanceProperties;
import com.ddm.argus.conditional.ConditionalOnEnvironmentVariable;
import com.ddm.argus.grpc.GrpcProperties;
import com.ddm.argus.grpc.HybridDnsNameResolverProvider;
import com.ddm.argus.grpc.LaneAwareLoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolverRegistry;
import net.devh.boot.grpc.client.channelfactory.GrpcChannelConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * 自动装配：当环境中存在 {@code ECS_CONTAINER_METADATA_URI_V4} 时，
 * 启用基于 ECS 元数据与 Cloud Map 的服务注册/发现集成。
 */
@AutoConfiguration
@EnableConfigurationProperties({
        EcsInstanceProperties.class,
        GrpcProperties.class
})
@ConditionalOnEnvironmentVariable(name = "ECS_CONTAINER_METADATA_URI_V4")
public class EcsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EcsAutoConfiguration.class);

    @Bean
    public EcsRegistrar laneRegistrar(EcsInstanceProperties ecsInstance) {
        return new EcsRegistrar(ecsInstance);
    }

    /**
     * 注册 Cloud Map 解析器
     */
    @Bean
    public HybridDnsNameResolverProvider hybridDnsNameResolverProvider(
            GrpcProperties resolverProps, EcsInstanceProperties ecsProps) {
        var p = new HybridDnsNameResolverProvider(resolverProps, ecsProps);
        NameResolverRegistry.getDefaultRegistry().register(p);
        log.info("==>[Argus] Registered CloudMap NameResolverProvider");
        return p;
    }

    /**
     * 注册 Lane 感知负载均衡器
     */
    @Bean
    public LaneAwareLoadBalancerProvider laneAwareLoadBalancerProvider() {
        var p = new LaneAwareLoadBalancerProvider();
        LoadBalancerRegistry.getDefaultRegistry().register(p);
        log.info("==>[Argus] Registered gRPC LoadBalancerProvider: {}", p.getPolicyName());
        return p;
    }

    /**
     * 负载均衡策略配置（默认 lane_round_robin）
     */
    @Bean
    public GrpcChannelConfigurer globalLoadBalancerConfigurer(GrpcProperties props) {
        String policy = props.getLoadbalance().getPolicy();
        if (policy == null || policy.isBlank()) {
            policy = LaneAwareLoadBalancerProvider.POLICY;
        }
        log.info("==>[Argus] gRPC load-balancing policy = {}", policy);
        var pm = Map.of("loadBalancingPolicy", policy);
        return (builder, clientName) ->
                builder.defaultServiceConfig(pm);
    }
}