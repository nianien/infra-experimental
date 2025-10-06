package com.ddm.argus.grpc;

import com.ddm.argus.grpc.TraceContext.TraceInfo;
import com.ddm.argus.utils.TraceparentUtils;
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
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP 入口链路追踪过滤器（仅 W3C 标准）：
 * - 只解析/回写 traceparent / tracestate
 * - lane 仅通过 tracestate 的 ctx 成员承载
 */
public class TraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);
    private static final String HDR_TRACEPARENT = "traceparent";
    private static final String HDR_TRACESTATE = "tracestate";

    private static final String[] SKIP_PATH_PREFIXES = new String[]{
            "/actuator/health", "/actuator/info",
            "/favicon", "/assets/", "/static/", "/public/", "/webjars/",
            "/css/", "/js/", "/images/", "/swagger", "/v3/api-docs"
    };

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        final String uri = request.getRequestURI();
        for (String p : SKIP_PATH_PREFIXES) if (uri.startsWith(p)) return true;
        return false;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        // 解析 tracestateIn
        final String tracestateIn = request.getHeader(HDR_TRACESTATE);
        TraceInfo info = TraceparentUtils.parse(
                request.getHeader(HDR_TRACEPARENT),
                request.getHeader(HDR_TRACESTATE));

        final Context ctx = Context.current()
                .withValue(TraceContext.CTX_TRACE_INFO, info);
        final Map<String, String> oldMdc = MDC.getCopyOfContextMap();
        setMdc(info);

        // 回写标准头
        response.setHeader(HDR_TRACEPARENT, TraceparentUtils.formatTraceparent(info.traceId(), info.spanId(), info.flags()));
        final String tracestateOut = TraceparentUtils.upsertLane(tracestateIn, info.lane());
        if (tracestateOut != null && !tracestateOut.isBlank()) {
            response.setHeader(HDR_TRACESTATE, tracestateOut);
        }

        if (log.isDebugEnabled()) {
            log.debug("HTTP {} {} traceId={} spanId={} flags={} parent={} lane={}",
                    request.getMethod(), request.getRequestURI(),
                    info.traceId(), info.spanId(), info.flags(), info.parentId(), info.lane());
        }

        final Context prev = ctx.attach();
        try {
            filterChain.doFilter(request, response);
            if (request.isAsyncStarted()) {
                request.getAsyncContext().addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent e) {
                    }

                    @Override
                    public void onTimeout(AsyncEvent e) {
                    }

                    @Override
                    public void onError(AsyncEvent e) {
                    }

                    @Override
                    public void onStartAsync(AsyncEvent e) {
                    }
                });
            }
        } finally {
            ctx.detach(prev);
            restoreMdc(oldMdc);
        }
    }

    private static void setMdc(TraceInfo info) {
        MDC.put(TraceContext.MDC_TRACE_ID, info.traceId());
        MDC.put(TraceContext.MDC_SPAN_ID, info.spanId());
        MDC.put(TraceContext.MDC_FLAGS, info.flags());
        String lane = info.lane();
        if (lane != null && !lane.isBlank()) {
            MDC.put(TraceContext.MDC_LANE, lane);
        } else {
            MDC.remove(TraceContext.MDC_LANE);
        }
        MDC.put("traceparent", TraceparentUtils.formatTraceparent(info.traceId(), info.spanId(), info.flags()));
    }

    private static void restoreMdc(Map<String, String> old) {
        if (old != null) MDC.setContextMap(old);
        else MDC.clear();
    }
}