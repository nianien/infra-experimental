package com.ddm.argus.grpc;

import io.grpc.Attributes;

/**
 * 通道 / 子通道上的静态属性键定义。
 * <p>用于存储服务实例的元信息，例如泳道、区域、权重等。</p>
 */
public final class ChannelAttributes {

    private ChannelAttributes() {
        // utility class
    }

    /**
     * 泳道属性键（lane），与 TraceContext / MetadataKeys 的 lane 一致。
     */
    public static final Attributes.Key<String> LANE =
            Attributes.Key.create("x.ctx.lane");
}