package com.ddm.hermes.aws;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;

import java.time.Duration;

public final class AwsClientFactory {
    private AwsClientFactory() {}
    private static final ClientOverrideConfiguration CFG =
            ClientOverrideConfiguration.builder()
                    .apiCallTimeout(Duration.ofSeconds(6))
                    .build();

    public static EcsClient ecs(Region region) {
        return EcsClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(CFG)
                .build();
    }

    public static ServiceDiscoveryClient serviceDiscovery(Region region) {
        return ServiceDiscoveryClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(CFG)
                .build();
    }
}