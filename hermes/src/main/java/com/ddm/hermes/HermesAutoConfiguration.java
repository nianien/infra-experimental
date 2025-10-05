package com.ddm.hermes;

import com.ddm.hermes.aws.LaneBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hermes Spring Boot Auto Configuration
 * <p>
 * 自动配置 AWS ECS 服务发现组件：
 * - EnvBootstrap: 在 ECS 环境下自动注册服务到 AWS Service Discovery
 * <p>
 * 仅在 ECS_CONTAINER_METADATA_URI_V4 环境变量存在时启用
 */
@Configuration
@ConditionalOnProperty(name = "ECS_CONTAINER_METADATA_URI_V4", matchIfMissing = false)
public class HermesAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HermesAutoConfiguration.class);

    public HermesAutoConfiguration() {
        log.info("Hermes: HermesAutoConfiguration constructor called");
    }

    /**
     * AWS ECS Service Discovery Bootstrap Bean
     */
    @Bean
    @ConditionalOnMissingBean
    public LaneBootstrap laneBootstrap() {
        log.info("Hermes: Auto-configuring LaneDetectBootstrap for AWS ECS service discovery");
        return new LaneBootstrap();
    }
}
