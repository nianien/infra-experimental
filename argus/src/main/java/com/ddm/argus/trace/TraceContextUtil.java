package com.ddm.argus.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public final class TraceContextUtil {

    // === W3C Trace Context 固定规范 ===
    public static final int TRACE_ID_HEX_LEN = 32; // 16字节 -> 32 hex
    public static final int SPAN_ID_HEX_LEN = 16;  // 8字节 -> 16 hex
    public static final String VERSION = "00";
    public static final String DEFAULT_FLAGS = "01"; // 最低位1=sampled

    private static final SecureRandom RAND = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TraceContextUtil() {
    }

    /* ===== ID 生成 ===== */
    public static String generateTraceId() {
        return toHex(randomBytes(16));
    }

    public static String generateSpanId() {
        return toHex(randomBytes(8));
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RAND.nextBytes(b);
        return b;
    }

    private static String toHex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0, j = 0; i < b.length; i++) {
            int v = b[i] & 0xFF;
            out[j++] = HEX[v >>> 4];
            out[j++] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /* ===== traceparent 格式化/解析 ===== */
    public static String formatTraceparent(String traceId, String spanId, String flags) {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(spanId, "spanId");
        if (flags == null || flags.length() != 2) flags = DEFAULT_FLAGS;

        return String.join("-", VERSION, traceId, spanId, flags)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 解析 traceparent，返回 [version, traceId, parentId, flags]；不合法返回 null
     */
    public static String[] parseTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return null;
        String[] parts = traceparent.trim().split("-");
        if (parts.length != 4) return null;

        if (!VERSION.equals(parts[0])) return null;
        if (!isValidHex(parts[1], TRACE_ID_HEX_LEN)) return null;
        if (!isValidHex(parts[2], SPAN_ID_HEX_LEN)) return null;
        if (!isValidHex(parts[3], 2)) return null;

        return Arrays.copyOf(parts, 4);
    }

    private static boolean isValidHex(String s, int len) {
        if (s == null || s.length() != len) return false;
        for (int i = 0; i < len; i++) {
            if (Character.digit(s.charAt(i), 16) == -1) return false;
        }
        return true;
    }

    /* ===== 获取当前上下文中的 trace 信息 ===== */
    public static String getCurrentTraceId() {
        return firstNonNull(TraceContext.CTX_TRACE_ID.get(), MDC.get(TraceContext.MDC_TRACE_ID));
    }

    public static String getCurrentSpanId() {
        return firstNonNull(TraceContext.CTX_SPAN_ID.get(), MDC.get(TraceContext.MDC_SPAN_ID));
    }

    public static String getCurrentFlags() {
        return firstNonNull(TraceContext.CTX_FLAGS.get(), MDC.get(TraceContext.MDC_FLAGS));
    }

    /* ===== util ===== */
    private static String firstNonNull(String v1, String v2) {
        return (v1 != null) ? v1 : v2;
    }
}