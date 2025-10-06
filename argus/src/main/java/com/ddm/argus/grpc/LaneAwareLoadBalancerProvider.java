package com.ddm.argus.grpc;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;

public final class LaneAwareLoadBalancerProvider extends LoadBalancerProvider {

    /** 策略名，用于 service config: {"loadBalancingPolicy":"lane_round_robin"} */
    public static final String POLICY = "lane_round_robin";

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 5; // 和 round_robin 同级
    }

    @Override
    public String getPolicyName() {
        return POLICY;
    }

    @Override
    public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
        return new LaneAwareRoundRobinLoadBalancer(helper);
    }
}