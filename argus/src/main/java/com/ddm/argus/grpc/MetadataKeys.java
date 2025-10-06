package com.ddm.argus.grpc;

import io.grpc.Metadata;

/**
 * gRPC Metadata 头键（仅 W3C 标准）：
 * - traceparent
 * - tracestate
 */
public final class MetadataKeys {
    private MetadataKeys() {
    }

    public static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    public static final Metadata.Key<String> TRACESTATE =
            Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);
}