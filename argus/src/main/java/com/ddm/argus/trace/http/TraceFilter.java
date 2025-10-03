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
import java.util.HashMap;
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
    private static final String HDR_TRACESTATE = "tracestate"; // 可选
    // 兼容头（便于人类/前端查看）
    private static final String HDR_X_TRACE_ID = "X-Trace-Id";
    private static final String HDR_X_SPAN_ID = "X-Span-Id";

    // 可按需扩展的“跳过滤路径”集合（前缀/完整匹配都可）
    private static final Set<String> SKIP_PATH_PREFIXES = Set.of(
            "/actuator/health", "/actuator/info",
            "/favicon", "/assets/", "/static/", "/public/", "/webjars/",
            "/css/", "/js/", "/images/", "/swagger", "/v3/api-docs"
    );

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        final String uri = request.getRequestURI();
        for (String p : SKIP_PATH_PREFIXES) {
            if (uri.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * 避免在异步分派阶段再执行一次过滤（默认就够用了）
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        // 1) 解析入站 traceparent；无则生成
        final String incomingTraceparent = request.getHeader(HDR_TRACEPARENT);
        final String[] parsed = TraceContextUtil.parseTraceparent(incomingTraceparent);

        final String traceId;
        final String parentSpanId; // 上游 span，只记录不用作“当前活动 span”
        final String flags;
        if (parsed != null) {
            traceId = parsed[1];
            parentSpanId = parsed[2];
            flags = parsed[3];
        } else {
            traceId = TraceContextUtil.generateTraceId();
            parentSpanId = null;
            flags = TraceContextUtil.DEFAULT_FLAGS;
        }

        // 2) 为本次 HTTP 请求新建 server span（当前活动 span）
        final String serverSpanId = TraceContextUtil.generateSpanId();

        // 3) 构建 gRPC Context（如你定义了 CTX_PARENT_SPAN_ID，可一并放入）
        Context ctx = Context.current()
                .withValue(TraceContext.CTX_TRACE_ID, traceId)
                .withValue(TraceContext.CTX_SPAN_ID, serverSpanId)
                .withValue(TraceContext.CTX_FLAGS, flags);
        // ctx = ctx.withValue(TraceContext.CTX_PARENT_SPAN_ID, parentSpanId);

        // 4) 备份并设置 MDC（含完整 traceparent，便于日志一把打印）
        final Map<String, String> oldMdc = MDC.getCopyOfContextMap();
        final Map<String, String> newMdc = (oldMdc == null) ? new HashMap<>() : new HashMap<>(oldMdc);
        newMdc.put(TraceContext.MDC_TRACE_ID, traceId);
        newMdc.put(TraceContext.MDC_SPAN_ID, serverSpanId);
        newMdc.put(TraceContext.MDC_FLAGS, flags);
        newMdc.put("traceparent", TraceContextUtil.formatTraceparent(traceId, serverSpanId, flags));
        MDC.setContextMap(newMdc);

        // 5) 回写响应头（标准 + 兼容）
        response.addHeader(HDR_TRACEPARENT, TraceContextUtil.formatTraceparent(traceId, serverSpanId, flags));
        if (request.getHeader(HDR_TRACESTATE) != null) {
            response.addHeader(HDR_TRACESTATE, request.getHeader(HDR_TRACESTATE)); // 透传（如需）
        }
        response.addHeader(HDR_X_TRACE_ID, traceId);
        response.addHeader(HDR_X_SPAN_ID, serverSpanId);

        if (log.isDebugEnabled()) {
            log.debug("HTTP {} {}  traceId={} spanId={} flags={} parent={}",
                    request.getMethod(), request.getRequestURI(), traceId, serverSpanId, flags, parentSpanId);
        }

        // 6) 执行过滤链（attach/detach 确保 gRPC Context 作用域正确）
        final Context prev = ctx.attach();
        try {
            filterChain.doFilter(request, response);

            // 若本请求启动了异步处理，可选：注册 AsyncListener 打一条完成/异常日志
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) {
                        if (log.isDebugEnabled()) log.debug("HTTP async completed traceId={}", traceId);
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {
                        log.warn("HTTP async timeout traceId={}", traceId);
                    }

                    @Override
                    public void onError(AsyncEvent event) {
                        log.warn("HTTP async error traceId={}", traceId, event.getThrowable());
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) { /* ignore */ }
                });
            }
        } finally {
            ctx.detach(prev);
            // 恢复 MDC（不要直接 clear，避免影响线程池后续请求）
            if (oldMdc != null) MDC.setContextMap(oldMdc);
            else MDC.clear();
            if (log.isDebugEnabled()) {
                log.debug("HTTP completed traceId={}", traceId);
            }
        }
    }
}