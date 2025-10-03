package com.ddm.argus.trace;

import io.grpc.Context;

public final class TraceContext {
    private TraceContext() {}

    // MDC keys（用于日志输出 %X{traceId} %X{spanId}）
    public static final String MDC_TRACE_ID  = "traceId";
    public static final String MDC_SPAN_ID   = "spanId";
    public static final String MDC_FLAGS     = "traceFlags";

    // gRPC Context keys
    public static final Context.Key<String> CTX_TRACE_ID = Context.key("w3c-trace-id");
    public static final Context.Key<String> CTX_SPAN_ID  = Context.key("w3c-span-id");
    public static final Context.Key<String> CTX_FLAGS    = Context.key("w3c-trace-flags");
}