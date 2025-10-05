package com.ddm.hermes.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.ServiceRegistry;
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceRequest;

import java.util.LinkedHashMap;
import java.util.Map;

public class LaneRegistrar implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(LaneRegistrar.class);

    private static final String ATTR_IPV4 = "AWS_INSTANCE_IPV4";
    private static final String ATTR_PORT = "AWS_INSTANCE_PORT";
    private static final String ATTR_LANE = "lane";

    private final EcsInstance ins;

    public LaneRegistrar(EcsInstance ins) {
        this.ins = ins;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // lane 为空不上报
        if (ins.clusterArn() == null || ins.taskArn() == null ||
                ins.serviceName() == null || ins.containerName() == null ||
                ins.containerPort() == null || ins.regionId() == null ||
                ins.lane() == null || ins.lane().isBlank()) {
            log.info("LaneRegistrar: incomplete ecs.instance or missing lane, skip registration.");
            return;
        }

        var region = Region.of(ins.regionId());
        try (var ecs = AwsClientFactory.ecs(region);
             var sd = AwsClientFactory.serviceDiscovery(region)) {

            String ip = Retry.get(
                    () -> EcsMetadataService.getPrivateIp(ecs, ins.clusterArn(), ins.taskArn(), ins.containerName()).orElse(null),
                    v -> v != null && !v.isBlank(),
                    30, 1000
            );
            if (ip == null) {
                log.warn("LaneRegistrar: cannot get private IP. Skip.");
                return;
            }

            Service svc = EcsMetadataService.getService(ecs, ins.clusterArn(), ins.serviceName()).orElse(null);
            if (svc == null) {
                log.warn("LaneRegistrar: service not found. Skip.");
                return;
            }

            String registryArn = svc.serviceRegistries().stream()
                    .map(ServiceRegistry::registryArn)
                    .filter(a -> a != null && !a.isBlank())
                    .findFirst().orElse(null);
            if (registryArn == null) {
                log.warn("LaneRegistrar: no CloudMap binding. Skip.");
                return;
            }
            String serviceId = registryArn.substring(registryArn.lastIndexOf('/') + 1);

            // ✅ 保留常量写法
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(ATTR_IPV4, ip);
            attrs.put(ATTR_PORT, String.valueOf(ins.containerPort()));
            attrs.put(ATTR_LANE, ins.lane());

            var resp = Retry.get(
                    () -> sd.registerInstance(RegisterInstanceRequest.builder()
                            .serviceId(serviceId)
                            .instanceId(ins.taskId())
                            .attributes(attrs)
                            .build()),
                    r -> r != null && r.sdkHttpResponse().isSuccessful(),
                    10, 1000
            );

            if (resp == null) {
                log.warn("LaneRegistrar: registerInstance failed after retries. Skip.");
                return;
            }

            log.info("LaneRegistrar OK. serviceId={}, instanceId={}, ip={}, port={}, lane={}, region={}",
                    serviceId, ins.taskId(), ip, ins.containerPort(), ins.lane(), region.id());
        } catch (Exception e) {
            log.warn("LaneRegistrar failed: {}", e.getMessage(), e);
        }
    }
}