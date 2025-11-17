package com.ddm.argus.ecs;

import com.ddm.argus.utils.EcsUtils;
import com.ddm.argus.utils.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.model.*;
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceRequest;
import software.amazon.awssdk.services.servicediscovery.model.RegisterInstanceResponse;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 应用启动完成后，将当前任务实例注册到 AWS Cloud Map。
 * 依赖于从 ECS 任务与服务中获取到的元数据（私网 IP、端口、lane 等）。
 */
public class EcsRegistrar implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(EcsRegistrar.class);


    private final EcsInstanceProperties ins;

    public EcsRegistrar(EcsInstanceProperties ins) {
        this.ins = ins;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (ins.clusterArn() == null || ins.taskArn() == null ||
                ins.serviceName() == null || ins.containerName() == null ||
                ins.containerPort() == null || ins.regionId() == null ||
                ins.lane() == null || ins.lane().isBlank()) {
            log.info("==>[argus] LaneRegistrar: incomplete ecs.instance or missing lane, skip registration.");
            return;
        }

        var region = Region.of(ins.regionId());
        try (var ecs = EcsUtils.ecs(region);
             var sd = EcsUtils.serviceDiscovery(region)) {

            Service svc = EcsUtils.getService(ecs, ins.clusterArn(), ins.serviceName()).orElse(null);
            if (svc == null) {
                log.warn("==>[argus] LaneRegistrar: service not found. Skip.");
                return;
            }

            String registryArn = svc.serviceRegistries().stream()
                    .map(ServiceRegistry::registryArn)
                    .filter(a -> a != null && !a.isBlank())
                    .findFirst().orElse(null);
            if (registryArn == null) {
                log.warn("==>[argus] LaneRegistrar: no CloudMap binding. Skip.");
                return;
            }
            String serviceId = registryArn.substring(registryArn.lastIndexOf('/') + 1);

            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(EcsConstants.CM_ATTR_IPV4, ins.containerHost());
            attrs.put(EcsConstants.CM_ATTR_PORT, String.valueOf(ins.containerPort()));
            attrs.put(EcsConstants.CM_ATTR_LANE, ins.lane());

            Optional<RegisterInstanceResponse> resp = Retry.get(
                    () -> sd.registerInstance(RegisterInstanceRequest.builder()
                            .serviceId(serviceId)
                            .instanceId(ins.taskId())
                            .attributes(attrs)
                            .build()),
                    r -> r != null && r.sdkHttpResponse().isSuccessful(),
                    10,
                    Duration.ofMillis(1000)
            );

            if (resp.isEmpty()) {
                log.warn("==>[argus] LaneRegistrar: registerInstance failed after retries. Skip.");
                return;
            }

            log.info("==>[argus] LaneRegistrar OK. serviceId={}, instanceId={}, ip={}, port={}, lane={}, region={}",
                    serviceId, ins.taskId(), ins.containerHost(), ins.containerPort(), ins.lane(), region.id());
        } catch (Exception e) {
            log.warn("==>[argus] LaneRegistrar failed: {}", e.getMessage(), e);
        }
    }



}