package com.ddm.argus.autoconfigure;

import com.ddm.argus.conditional.ConditionalOnEnvironmentVariable;
import com.ddm.argus.ecs.EcsInstanceProperties;
import com.ddm.argus.ecs.EcsRegistrar;
import com.ddm.argus.grpc.CloudMapNameResolverProvider;
import com.ddm.argus.grpc.GrpcProperties;
import io.grpc.NameResolverRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 仅在 ECS 环境启用：ECS 元数据注册、Cloud Map 解析器。
 */
@AutoConfiguration
@EnableConfigurationProperties({EcsInstanceProperties.class, GrpcProperties.class})
@ConditionalOnEnvironmentVariable(name = "ECS_CONTAINER_METADATA_URI_V4")
public class EcsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EcsAutoConfiguration.class);

    @Bean
    public EcsRegistrar laneRegistrar(EcsInstanceProperties ecsInstance) {
        return new EcsRegistrar(ecsInstance);
    }

    /**
     * 注册 CloudMapNameResolverProvider(支持 cloud:///协议)
     */
    @Bean
    public CloudMapNameResolverProvider cloudMapNameResolverProvider(
            GrpcProperties resolverProps, EcsInstanceProperties ecsProps) {
        var nrp = new CloudMapNameResolverProvider(ecsProps.regionId(), resolverProps.getResolver().getRefreshInterval());
        NameResolverRegistry.getDefaultRegistry().register(nrp);
        log.info("==>[Argus] Registered CloudMap NameResolverProvider: scheme=cloud");
        return nrp;
    }
}