package com.ddm.argus.trace.http;

import com.ddm.argus.trace.TraceContext;
import com.ddm.argus.trace.TraceContextUtil;
import io.grpc.Context;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * HTTP -> 业务入口的链路追踪过滤器（Spring OncePerRequestFilter 版本）
 * - 优先解析入站 traceparent；无则生成新的 traceId
 * - 本服务始终新建一个 server spanId
 * - 同步 gRPC Context 与 MDC；响应头回写 traceparent / X-Trace-Id / X-Span-Id
 * - 跳过静态资源/健康检查
 */
@Component
@Order(1)
public class TraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    // 标准/W3C 头
    private static final String HDR_TRACEPARENT = "traceparent";
    private static final String HDR_TRACESTATE = "tracestate";
    // 兼容头
    private static final String HDR_X_TRACE_ID = "X-Trace-Id";
    private static final String HDR_X_SPAN_ID = "X-Span-Id";

    // 静态资源/健康检查路径前缀
    private static final Set<String> SKIP_PATH_PREFIXES = Set.of(
            "/actuator/health", "/actuator/info",
            "/favicon", "/assets/", "/static/", "/public/", "/webjars/",
            "/css/", "/js/", "/images/", "/swagger", "/v3/api-docs"
    );

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        return SKIP_PATH_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true; // 避免异步分派时重复过滤
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        // === 1) 解析 traceparent ===
        String incoming = request.getHeader(HDR_TRACEPARENT);
        String[] parsed = TraceContextUtil.parseTraceparent(incoming);

        String traceId = parsed != null ? parsed[1] : TraceContextUtil.generateTraceId();
        String parentId = parsed != null ? parsed[2] : null;
        String flags = parsed != null ? parsed[3] : TraceContextUtil.DEFAULT_FLAGS;
        String spanId = TraceContextUtil.generateSpanId();

        // === 2) gRPC Context 注入 ===
        Context ctx = Context.current()
                .withValue(TraceContext.CTX_TRACE_ID, traceId)
                .withValue(TraceContext.CTX_SPAN_ID, spanId)
                .withValue(TraceContext.CTX_FLAGS, flags)
                .withValue(TraceContext.CTX_PARENT_SPAN_ID, parentId);

        // === 3) MDC 设置 ===
        Map<String, String> oldMdc = MDC.getCopyOfContextMap();
        setMdc(traceId, spanId, flags);

        // === 4) 回写响应头 ===
        String traceparent = TraceContextUtil.formatTraceparent(traceId, spanId, flags);
        response.setHeader(HDR_TRACEPARENT, traceparent);
        if (request.getHeader(HDR_TRACESTATE) != null) {
            response.setHeader(HDR_TRACESTATE, request.getHeader(HDR_TRACESTATE));
        }
        response.setHeader(HDR_X_TRACE_ID, traceId);
        response.setHeader(HDR_X_SPAN_ID, spanId);

        debug("HTTP {} {} traceId={} spanId={} flags={} parent={}",
                request.getMethod(), request.getRequestURI(), traceId, spanId, flags, parentId);

        // === 5) 执行链路 ===
        Context prev = ctx.attach();
        try {
            filterChain.doFilter(request, response);

            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent e) {
                        debug("HTTP async completed traceId={}", traceId);
                    }

                    @Override
                    public void onTimeout(AsyncEvent e) {
                        log.warn("HTTP async timeout traceId={}", traceId);
                    }

                    @Override
                    public void onError(AsyncEvent e) {
                        log.warn("HTTP async error traceId={}", traceId, e.getThrowable());
                    }

                    @Override
                    public void onStartAsync(AsyncEvent e) { /* ignore */ }
                });
            }
        } finally {
            ctx.detach(prev);
            restoreMdc(oldMdc);
            debug("HTTP completed traceId={}", traceId);
        }
    }

    /* ===== Helpers ===== */

    private static void setMdc(String traceId, String spanId, String flags) {
        MDC.put(TraceContext.MDC_TRACE_ID, traceId);
        MDC.put(TraceContext.MDC_SPAN_ID, spanId);
        MDC.put(TraceContext.MDC_FLAGS, flags);
        MDC.put("traceparent", TraceContextUtil.formatTraceparent(traceId, spanId, flags));
    }

    private static void restoreMdc(Map<String, String> old) {
        if (old != null) MDC.setContextMap(old);
        else MDC.clear();
    }

    private static void debug(String msg, Object... args) {
        if (log.isDebugEnabled()) log.debug(msg, args);
    }
}