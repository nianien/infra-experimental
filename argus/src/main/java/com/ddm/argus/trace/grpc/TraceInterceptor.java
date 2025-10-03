package com.ddm.argus.trace.grpc;

import com.ddm.argus.trace.TraceContext;
import com.ddm.argus.trace.TraceContextUtil;
import io.grpc.*;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class TraceInterceptor implements ClientInterceptor, ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TraceInterceptor.class);

    private static final Metadata.Key<String> TRACEPARENT_KEY =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TRACESTATE_KEY =
            Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER); // optional passthrough

    /* =================== Client (outbound) =================== */
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        // 复用当前 traceId（Context -> MDC），没有就新建；每次出站调用新建 spanId
        final String existingTrace = TraceContext.CTX_TRACE_ID.get() != null
                ? TraceContext.CTX_TRACE_ID.get()
                : MDC.get(TraceContext.MDC_TRACE_ID);

        final String traceId = (existingTrace != null) ? existingTrace : TraceContextUtil.generateTraceId();
        final String spanId = TraceContextUtil.generateSpanId(); // client span per-call
        final String flags = (TraceContext.CTX_FLAGS.get() != null)
                ? TraceContext.CTX_FLAGS.get()
                : TraceContextUtil.DEFAULT_FLAGS;

        final Context ctx = Context.current()
                .withValue(TraceContext.CTX_TRACE_ID, traceId)
                .withValue(TraceContext.CTX_SPAN_ID, spanId)
                .withValue(TraceContext.CTX_FLAGS, flags);

        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                final String traceparent = TraceContextUtil.formatTraceparent(traceId, spanId, flags);
                headers.put(TRACEPARENT_KEY, traceparent);

                // === 可选：tracestate 透传（若你在 MDC/Context 里维护了它） ===
                // final String tracestate = MDC.get("tracestate");
                // if (tracestate != null) headers.put(TRACESTATE_KEY, tracestate);

                if (log.isDebugEnabled()) {
                    log.debug("Client -> sending traceparent={}, method={}", traceparent, method.getFullMethodName());
                }

                Listener<RespT> wrapped = new SimpleForwardingClientCallListener<RespT>(responseListener) {
                    @Override
                    public void onHeaders(Metadata h) {
                        withMdc(ctx, traceId, spanId, flags, () -> super.onHeaders(h));
                    }

                    @Override
                    public void onMessage(RespT message) {
                        withMdc(ctx, traceId, spanId, flags, () -> super.onMessage(message));
                    }

                    @Override
                    public void onReady() {
                        withMdc(ctx, traceId, spanId, flags, super::onReady);
                    }

                    @Override
                    public void onClose(Status status, Metadata t) {
                        withMdc(ctx, traceId, spanId, flags, () -> super.onClose(status, t));
                    }
                };

                Context prev = ctx.attach();
                try {
                    super.start(wrapped, headers);
                } finally {
                    ctx.detach(prev);
                }
            }
        };
    }

    /* =================== Server (inbound) =================== */
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        final String incoming = headers.get(TRACEPARENT_KEY);
        final String[] parsed = TraceContextUtil.parseTraceparent(incoming);

        final String traceId;
        final String parentSpanId;   // 上游 span（父）
        final String flags;
        final String serverSpanId;   // 本服务新建的 server span（当前）

        if (parsed != null) {
            traceId = parsed[1];
            parentSpanId = parsed[2];
            flags = parsed[3];
            serverSpanId = TraceContextUtil.generateSpanId(); // ★ 新建当前 server span
            if (log.isDebugEnabled()) {
                log.debug("Server <- received traceparent={}, method={}", incoming, call.getMethodDescriptor().getFullMethodName());
            }
        } else {
            traceId = TraceContextUtil.generateTraceId();
            parentSpanId = null;
            flags = TraceContextUtil.DEFAULT_FLAGS;
            serverSpanId = TraceContextUtil.generateSpanId(); // ★ 仍需新建当前 server span
            if (log.isDebugEnabled()) {
                log.debug("Server <- missing/invalid traceparent, generated traceId={}, spanId={}, method={}",
                        traceId, serverSpanId, call.getMethodDescriptor().getFullMethodName());
            }
        }

        Context ctx = Context.current()
                .withValue(TraceContext.CTX_TRACE_ID, traceId)
                .withValue(TraceContext.CTX_SPAN_ID, serverSpanId)
                .withValue(TraceContext.CTX_FLAGS, flags);
        // === 可选：如果你在 TraceContext 定义了 CTX_PARENT_SPAN_ID，这里也写入 ===
        // ctx = ctx.withValue(TraceContext.CTX_PARENT_SPAN_ID, parentSpanId);

        ServerCall.Listener<ReqT> delegate = Contexts.interceptCall(ctx, call, headers, next);

        return new SimpleForwardingServerCallListener<ReqT>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                withMdc(ctx, traceId, serverSpanId, flags, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                withMdc(ctx, traceId, serverSpanId, flags, super::onHalfClose);
            }

            @Override
            public void onReady() {
                withMdc(ctx, traceId, serverSpanId, flags, super::onReady);
            }

            @Override
            public void onComplete() {
                withMdc(ctx, traceId, serverSpanId, flags, super::onComplete);
            }

            @Override
            public void onCancel() {
                withMdc(ctx, traceId, serverSpanId, flags, super::onCancel);
            }
        };
    }

    /* =================== MDC helper: backup & restore =================== */
    private static void withMdc(Context ctx, String traceId, String spanId, String flags, Runnable r) {
        final Context prev = ctx.attach();

        // 备份旧值
        final String oldTrace = MDC.get(TraceContext.MDC_TRACE_ID);
        final String oldSpan = MDC.get(TraceContext.MDC_SPAN_ID);
        final String oldFlags = MDC.get(TraceContext.MDC_FLAGS);
        final String oldTP = MDC.get("traceparent");

        // 设置当前值（含完整 traceparent，便于日志一把打印）
        MDC.put(TraceContext.MDC_TRACE_ID, traceId);
        MDC.put(TraceContext.MDC_SPAN_ID, spanId);
        MDC.put(TraceContext.MDC_FLAGS, flags);
        MDC.put("traceparent", TraceContextUtil.formatTraceparent(traceId, spanId, flags));

        try {
            r.run();
        } finally {
            // 恢复旧值（而不是直接 remove）——避免清空上层方法的 MDC
            restoreMdc(TraceContext.MDC_TRACE_ID, oldTrace);
            restoreMdc(TraceContext.MDC_SPAN_ID, oldSpan);
            restoreMdc(TraceContext.MDC_FLAGS, oldFlags);
            restoreMdc("traceparent", oldTP);
            ctx.detach(prev);
        }
    }

    private static void restoreMdc(String key, String oldVal) {
        if (oldVal == null) MDC.remove(key);
        else MDC.put(key, oldVal);
    }
}