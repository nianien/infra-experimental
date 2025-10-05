// src/main/java/com/ddm/hermes/aws/starter/LaneAutoConfiguration.java
package com.ddm.hermes.aws.autoconfigure;

import com.ddm.hermes.aws.EcsInstance;
import com.ddm.hermes.aws.EcsInstanceProperties;
import com.ddm.hermes.aws.LaneRegistrar;
import com.ddm.hermes.aws.conditional.ConditionalOnEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(EcsInstanceProperties.class)
@ConditionalOnEnvironmentVariable(name = "ECS_CONTAINER_METADATA_URI_V4")
public class LaneAutoConfiguration {

    @Bean
    public EcsInstance ecsInstance(EcsInstanceProperties p) {
        return new EcsInstance(
                p.getClusterArn(), p.getTaskArn(), p.getTaskId(), p.getRegionId(), p.getMetaBase(),
                p.getServiceName(), p.getServiceArn(), p.getTaskDefArn(),
                p.getContainerName(), p.getContainerPort(), p.getLane()
        );
    }

    @Bean
    public LaneRegistrar laneRegistrar(EcsInstance ecsInstance) {
        return new LaneRegistrar(ecsInstance);
    }
}