package com.ddm.argus.ecs;

import com.ddm.argus.utils.Retry;
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
        if (ins.getClusterArn() == null || ins.getTaskArn() == null ||
                ins.getServiceName() == null || ins.getContainerName() == null ||
                ins.getContainerPort() == null || ins.getRegionId() == null ||
                ins.getLane() == null || ins.getLane().isBlank()) {
            log.info("LaneRegistrar: incomplete ecs.instance or missing lane, skip registration.");
            return;
        }

        var region = Region.of(ins.getRegionId());
        try (var ecs = EcsUtils.ecs(region);
             var sd = EcsUtils.serviceDiscovery(region)) {

            String ip = Retry.get(
                    () -> EcsUtils.getPrivateIp(ecs, ins.getClusterArn(), ins.getTaskArn(), ins.getContainerName()).orElse(null),
                    v -> v != null && !v.isBlank(),
                    30, 1000
            );
            if (ip == null) {
                log.warn("LaneRegistrar: cannot get private IP. Skip.");
                return;
            }

            Service svc = EcsUtils.getService(ecs, ins.getClusterArn(), ins.getServiceName()).orElse(null);
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

            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put(EcsConstants.CM_ATTR_IPV4, ip);
            attrs.put(EcsConstants.CM_ATTR_PORT, String.valueOf(ins.getContainerPort()));
            attrs.put(EcsConstants.CM_ATTR_LANE, ins.getLane());

            var resp = Retry.get(
                    () -> sd.registerInstance(RegisterInstanceRequest.builder()
                            .serviceId(serviceId)
                            .instanceId(ins.getTaskId())
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
                    serviceId, ins.getTaskId(), ip, ins.getContainerPort(), ins.getLane(), region.id());
        } catch (Exception e) {
            log.warn("LaneRegistrar failed: {}", e.getMessage(), e);
        }
    }
}