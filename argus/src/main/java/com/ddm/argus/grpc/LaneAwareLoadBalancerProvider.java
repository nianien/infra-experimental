package com.ddm.argus.grpc;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LaneAwareLoadBalancerProvider extends LoadBalancerProvider {
    private static final Logger log = LoggerFactory.getLogger(LaneAwareLoadBalancerProvider.class);

    /**
     * 策略名，用于 service config: {"loadBalancingPolicy":"lane_round_robin"}
     */
    public static final String POLICY = "lane_round_robin";

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public String getPolicyName() {
        return POLICY;
    }

    @Override
    public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
        log.info("==>[argus] provider engaged: {}", getPolicyName()); // 看到这句说明策略被选中
        return new LaneAwareRoundRobinLoadBalancer(helper);
    }
}