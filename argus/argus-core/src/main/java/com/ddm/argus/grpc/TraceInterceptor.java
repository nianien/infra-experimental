package com.ddm.argus.grpc;

import com.ddm.argus.ecs.EcsConstants;
import com.ddm.argus.grpc.TraceContext.TraceInfo;
import com.ddm.argus.utils.TraceparentUtils;
import io.grpc.*;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

@GrpcGlobalClientInterceptor
@GrpcGlobalServerInterceptor
public class TraceInterceptor implements ClientInterceptor, ServerInterceptor {

    /* =================== Client (outbound) =================== */
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {

        // 1) 取当前 TraceInfo；若无则新建（root）
        TraceInfo current = TraceContext.CTX_TRACE_INFO.get();
        TraceInfo nextHop = (current != null) ? current.nextHop() : TraceInfo.root(null);

        // 2) 将新的 TraceInfo 写入 gRPC Context
        Context ctx = Context.current().withValue(TraceContext.CTX_TRACE_INFO, nextHop);

        // ⭐ 关键：把 TraceInfo 也塞进 CallOptions，供 LB 读取
        CallOptions withTrace = callOptions.withOption(TraceContext.CALL_OPT_TRACE_INFO, nextHop);

        return new SimpleForwardingClientCall<>(next.newCall(method, withTrace)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                // 3) 写出站头：traceparent / tracestate
                headers.put(MetadataKeys.TRACEPARENT,
                        TraceparentUtils.formatTraceparent(nextHop.traceId(), nextHop.spanId(), nextHop.flags()));

                String tracestateIn = headers.get(MetadataKeys.TRACESTATE);

                // 用空串表示“删除 lane”（upsertVendorKV 对空串/ null 都会删除该键）
                String tracestateOut = (nextHop.lane() == null || nextHop.lane().isBlank())
                        ? TraceparentUtils.upsertVendorKV(tracestateIn, "ctx", Map.of(EcsConstants.TAG_LANE, ""))
                        : TraceparentUtils.upsertVendorKV(tracestateIn, "ctx", Map.of(EcsConstants.TAG_LANE, nextHop.lane()));

                if (tracestateOut != null && !tracestateOut.isBlank()) {
                    headers.put(MetadataKeys.TRACESTATE, tracestateOut);
                }

                // 4) 包装 Listener：回调阶段挂 MDC
                Listener<RespT> wrapped = new SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onHeaders(Metadata h) {
                        withMdc(ctx, nextHop, () -> super.onHeaders(h));
                    }

                    @Override
                    public void onMessage(RespT m) {
                        withMdc(ctx, nextHop, () -> super.onMessage(m));
                    }

                    @Override
                    public void onReady() {
                        withMdc(ctx, nextHop, super::onReady);
                    }

                    @Override
                    public void onClose(Status s, Metadata t) {
                        withMdc(ctx, nextHop, () -> super.onClose(s, t));
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

        // 1) 从入站头解析 TraceInfo（无则生成；内部会新建 spanId）
        TraceInfo parsed = TraceparentUtils.parse(
                headers.get(MetadataKeys.TRACEPARENT),
                headers.get(MetadataKeys.TRACESTATE));

        // 2) 写入 Context，供后续 Handler/下游使用
        Context ctx = Context.current().withValue(TraceContext.CTX_TRACE_INFO, parsed);

        ServerCall.Listener<ReqT> delegate = Contexts.interceptCall(ctx, call, headers, next);

        // 3) 在回调阶段挂 MDC
        return new SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT msg) {
                withMdc(ctx, parsed, () -> super.onMessage(msg));
            }

            @Override
            public void onHalfClose() {
                withMdc(ctx, parsed, super::onHalfClose);
            }

            @Override
            public void onReady() {
                withMdc(ctx, parsed, super::onReady);
            }

            @Override
            public void onComplete() {
                withMdc(ctx, parsed, super::onComplete);
            }

            @Override
            public void onCancel() {
                withMdc(ctx, parsed, super::onCancel);
            }
        };
    }

    /* =================== Helpers =================== */

    private static void withMdc(Context ctx, TraceInfo info, Runnable r) {
        Context prev = ctx.attach();
        Map<String, String> old = backupMdc();
        try {
            MDC.put(TraceContext.MDC_TRACE_ID, info.traceId());
            MDC.put(TraceContext.MDC_SPAN_ID, info.spanId());
            MDC.put(TraceContext.MDC_FLAGS, info.flags());
            if (info.lane() == null || info.lane().isBlank()) {
                MDC.remove(TraceContext.MDC_LANE);
            } else {
                MDC.put(TraceContext.MDC_LANE, info.lane());
            }
            r.run();
        } finally {
            restoreMdc(old);
            ctx.detach(prev);
        }
    }

    private static Map<String, String> backupMdc() {
        Map<String, String> m = MDC.getCopyOfContextMap();
        return (m != null) ? new HashMap<>(m) : new HashMap<>();
    }

    private static void restoreMdc(Map<String, String> old) {
        if (old != null) MDC.setContextMap(old);
        else MDC.clear();
    }
}