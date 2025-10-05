package com.ddm.argus.trace.grpc;

import com.ddm.argus.trace.TraceContext;
import com.ddm.argus.trace.TraceContextUtil;
import io.grpc.*;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

@GrpcGlobalServerInterceptor
@GrpcGlobalClientInterceptor
public class TraceInterceptor implements ClientInterceptor, ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TraceInterceptor.class);

    private static final Metadata.Key<String> TRACEPARENT_KEY =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    /* =================== Client (outbound) =================== */
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        // 复用当前 traceId（Context -> MDC），没有就新建；每次出站调用新建 spanId
        final String traceId = firstNonNull(
                TraceContext.CTX_TRACE_ID.get(),
                MDC.get(TraceContext.MDC_TRACE_ID),
                TraceContextUtil.generateTraceId());

        final String spanId = TraceContextUtil.generateSpanId();
        final String flags = firstNonNull(
                TraceContext.CTX_FLAGS.get(),
                TraceContextUtil.DEFAULT_FLAGS);

        final Context ctx = Context.current()
                .withValue(TraceContext.CTX_TRACE_ID, traceId)
                .withValue(TraceContext.CTX_SPAN_ID, spanId)
                .withValue(TraceContext.CTX_FLAGS, flags);

        return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                final String traceparent = TraceContextUtil.formatTraceparent(traceId, spanId, flags);
                headers.put(TRACEPARENT_KEY, traceparent);

                debug("Client -> sending traceparent={}, method={}", traceparent, method.getFullMethodName());

                Listener<RespT> wrapped = new SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onHeaders(Metadata h) {
                        runWithMdc(ctx, traceId, spanId, flags, () -> super.onHeaders(h));
                    }

                    @Override
                    public void onMessage(RespT msg) {
                        runWithMdc(ctx, traceId, spanId, flags, () -> super.onMessage(msg));
                    }

                    @Override
                    public void onReady() {
                        runWithMdc(ctx, traceId, spanId, flags, super::onReady);
                    }

                    @Override
                    public void onClose(Status s, Metadata t) {
                        runWithMdc(ctx, traceId, spanId, flags, () -> super.onClose(s, t));
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

        String[] parsed = TraceContextUtil.parseTraceparent(headers.get(TRACEPARENT_KEY));

        final String traceId = parsed != null ? parsed[1] : TraceContextUtil.generateTraceId();
        final String parentId = parsed != null ? parsed[2] : null;
        final String flags = parsed != null ? parsed[3] : TraceContextUtil.DEFAULT_FLAGS;
        final String spanId = TraceContextUtil.generateSpanId();

        debug("Server <- {} traceId={}, spanId={}, method={}",
                parsed != null ? "received" : "generated",
                traceId, spanId, call.getMethodDescriptor().getFullMethodName());

        Context ctx = Context.current()
                .withValue(TraceContext.CTX_TRACE_ID, traceId)
                .withValue(TraceContext.CTX_SPAN_ID, spanId)
                .withValue(TraceContext.CTX_FLAGS, flags)
                .withValue(TraceContext.CTX_PARENT_SPAN_ID, parentId);

        ServerCall.Listener<ReqT> delegate = Contexts.interceptCall(ctx, call, headers, next);

        return new SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT msg) {
                runWithMdc(ctx, traceId, spanId, flags, () -> super.onMessage(msg));
            }

            @Override
            public void onHalfClose() {
                runWithMdc(ctx, traceId, spanId, flags, super::onHalfClose);
            }

            @Override
            public void onReady() {
                runWithMdc(ctx, traceId, spanId, flags, super::onReady);
            }

            @Override
            public void onComplete() {
                runWithMdc(ctx, traceId, spanId, flags, super::onComplete);
            }

            @Override
            public void onCancel() {
                runWithMdc(ctx, traceId, spanId, flags, super::onCancel);
            }
        };
    }

    /* =================== Helpers =================== */

    private static void runWithMdc(Context ctx, String traceId, String spanId, String flags, Runnable task) {
        Context prev = ctx.attach();
        Map<String, String> old = backupMdc();

        MDC.put(TraceContext.MDC_TRACE_ID, traceId);
        MDC.put(TraceContext.MDC_SPAN_ID, spanId);
        MDC.put(TraceContext.MDC_FLAGS, flags);
        MDC.put("traceparent", TraceContextUtil.formatTraceparent(traceId, spanId, flags));

        try {
            task.run();
        } finally {
            restoreMdc(old);
            ctx.detach(prev);
        }
    }

    private static Map<String, String> backupMdc() {
        Map<String, String> map = new HashMap<>();
        map.put(TraceContext.MDC_TRACE_ID, MDC.get(TraceContext.MDC_TRACE_ID));
        map.put(TraceContext.MDC_SPAN_ID, MDC.get(TraceContext.MDC_SPAN_ID));
        map.put(TraceContext.MDC_FLAGS, MDC.get(TraceContext.MDC_FLAGS));
        map.put("traceparent", MDC.get("traceparent"));
        return map;
    }

    private static void restoreMdc(Map<String, String> old) {
        old.forEach((k, v) -> {
            if (v == null) MDC.remove(k);
            else MDC.put(k, v);
        });
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    private static void debug(String msg, Object... args) {
        if (log.isDebugEnabled()) log.debug(msg, args);
    }
}