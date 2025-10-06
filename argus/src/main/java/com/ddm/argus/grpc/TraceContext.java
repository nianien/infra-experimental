package com.ddm.argus.grpc;

import com.ddm.argus.utils.TraceparentUtils;
import io.grpc.Context;

/**
 * TraceContext —— 定义跨进程传播的 gRPC Context 键（以及日志 MDC 键）。
 * W3C 标准：traceparent / tracestate
 * 自定义扩展：仅在 tracestate 的 vendor 成员 ctx 中表达 lane（ctx=lane:<v>）
 */
public final class TraceContext {

    /**
     * 统一封装 Trace 上下文（W3C traceparent + tracestate 扩展）
     */
    public record TraceInfo(String traceId, String parentId, String spanId, String flags, String lane) {
        /**
         * 从当前 TraceInfo 派生一个新的下游 TraceInfo：
         * - 沿用 traceId / flags / lane
         * - parentId = 当前 spanId
         * - 新生成 spanId
         */
        public TraceInfo nextHop() {
            return new TraceInfo(
                    this.traceId,
                    this.spanId, // parent = 当前 span
                    TraceparentUtils.generateSpanId(),
                    this.flags,
                    this.lane
            );
        }

        /**
         * 当上下文中不存在 TraceInfo 时，创建新的根 TraceInfo。
         */
        public static TraceInfo root(String lane) {
            String traceId = TraceparentUtils.generateTraceId();
            String spanId = TraceparentUtils.generateSpanId();
            return new TraceInfo(traceId, null, spanId, TraceparentUtils.DEFAULT_FLAGS, lane);
        }
    }

    private TraceContext() {
    }

    // ===== MDC keys（日志 %X{traceId} %X{spanId} %X{lane}） =====
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_FLAGS = "traceFlags";
    public static final String MDC_LANE = "lane";

    // 仅一个 Context Key
    public static final Context.Key<TraceInfo> CTX_TRACE_INFO = Context.key("trace.info");
}