package com.ddm.argus.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;

public final class TraceContextUtil {
    // W3C Trace Context 固定长度：traceId=16字节(32hex), spanId=8字节(16hex)
    public static final int TRACE_ID_HEX_LEN = 32;
    public static final int SPAN_ID_HEX_LEN = 16;

    public static final String VERSION = "00";
    // 采样位，最低位1=sampled；可按需换成"00"或策略决定
    public static final String DEFAULT_FLAGS = "01";

    private static final SecureRandom RAND = new SecureRandom();

    private TraceContextUtil() {
    }

    public static String generateTraceId() {
        byte[] b = new byte[16];
        RAND.nextBytes(b);
        return toHex(b);
    }

    public static String generateSpanId() {
        byte[] b = new byte[8];
        RAND.nextBytes(b);
        return toHex(b);
    }

    public static String formatTraceparent(final String traceId, final String spanId, String flags) {
        Objects.requireNonNull(traceId);
        Objects.requireNonNull(spanId);
        if (flags == null || flags.length() != 2) flags = DEFAULT_FLAGS;
        return String.format("%s-%s-%s-%s", VERSION, traceId, spanId, flags)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 解析 traceparent，返回 [version, traceId, parentId, flags]；不合法返回 null
     */
    public static String[] parseTraceparent(String traceparent) {
        if (traceparent == null) return null;
        String[] parts = traceparent.trim().split("-");
        if (parts.length != 4) return null;
        if (!VERSION.equals(parts[0])) return null;
        if (!isValidHex(parts[1], TRACE_ID_HEX_LEN)) return null;
        if (!isValidHex(parts[2], SPAN_ID_HEX_LEN)) return null;
        if (!isValidHex(parts[3], 2)) return null;
        return parts;
    }

    private static boolean isValidHex(String s, int len) {
        if (s == null || s.length() != len) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') ||
                    (c >= 'a' && c <= 'f') ||
                    (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    private static String toHex(byte[] b) {
        char[] out = new char[b.length * 2];
        final char[] HEX = "0123456789abcdef".toCharArray();
        for (int i = 0, j = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * 获取当前 traceId，优先从 gRPC Context 取，其次从 MDC 取
     */
    public static String getCurrentTraceId() {
        String traceId = TraceContext.CTX_TRACE_ID.get();
        if (traceId == null) {
            traceId = MDC.get(TraceContext.MDC_TRACE_ID);
        }
        return traceId;
    }

    /**
     * 获取当前 spanId，优先从 gRPC Context 取，其次从 MDC 取
     */
    public static String getCurrentSpanId() {
        String spanId = TraceContext.CTX_SPAN_ID.get();
        if (spanId == null) {
            spanId = MDC.get(TraceContext.MDC_SPAN_ID);
        }
        return spanId;
    }

    /**
     * 获取当前 traceFlags
     */
    public static String getCurrentFlags() {
        String flags = TraceContext.CTX_FLAGS.get();
        if (flags == null) {
            flags = MDC.get(TraceContext.MDC_FLAGS);
        }
        return flags;
    }
}