package com.ddm.argus.utils;


import com.ddm.argus.grpc.TraceContext.TraceInfo;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TraceparentUtils {

    private TraceparentUtils() {
    }

    /**
     * W3C trace-flags 默认：sampled=1 （01）
     */
    public static final String DEFAULT_FLAGS = "01";

    private static final SecureRandom RNG = new SecureRandom();


    /**
     * 一次性解析/初始化：从 traceparent 与 tracestate 产出本次调用的 TraceInfo。
     * - 有上游：沿用 traceId / parentId / flags；总是生成新的 spanId
     * - 无上游：新建 traceId，parentId=null，flags=DEFAULT_FLAGS；总是生成新的 spanId
     * - lane：从 tracestate 的 ctx vendor 成员解析（ctx=lane:<v>），无则为 null
     */
    public static TraceInfo parse(String traceparent, String tracestate) {
        String[] parsed = parseTraceparent(traceparent);

        String traceId = (parsed != null && parsed.length >= 4 && isValidTraceId(parsed[1])) ? parsed[1] : generateTraceId();
        String parentId = (parsed != null && parsed.length >= 4 && isValidSpanId(parsed[2])) ? parsed[2] : null;
        String flags = (parsed != null && parsed.length >= 4 && isValidFlags(parsed[3])) ? parsed[3] : DEFAULT_FLAGS;
        String spanId = generateSpanId();

        String lane = getLane(tracestate);

        return new TraceInfo(traceId, parentId, spanId, flags, lane);
    }

    /**
     * 解析 W3C traceparent：返回数组 {version, traceId, parentId, flags}；非法时返回 null。
     */
    public static String[] parseTraceparent(String traceparent) {
        if (traceparent == null) return null;
        String tp = traceparent.trim();
        if (tp.isEmpty()) return null;

        // 格式：version-traceId-parentId-flags
        String[] parts = tp.split("-");
        if (parts.length != 4) return null;

        String ver = parts[0];
        String traceId = parts[1];
        String parentId = parts[2];
        String flags = parts[3];

        if (!isValidVersion(ver)) return null;
        if (!isValidTraceId(traceId)) return null;
        if (!isValidSpanId(parentId)) return null;
        if (!isValidFlags(flags)) return null;

        return parts;
    }

    /**
     * 格式化 W3C traceparent：version 固定 "00"。
     */
    public static String formatTraceparent(String traceId, String spanId, String flags) {
        String f = (flags == null || !isValidFlags(flags)) ? DEFAULT_FLAGS : flags;
        return "00-" + traceId + "-" + spanId + "-" + f;
    }

    /**
     * 生成 32 hex 的 trace-id（16 bytes）。
     */
    public static String generateTraceId() {
        byte[] buf = new byte[16];
        RNG.nextBytes(buf);
        return CommonUtils.toHex(buf);
    }

    /**
     * 生成 16 hex 的 span-id（8 bytes）。
     */
    public static String generateSpanId() {
        byte[] buf = new byte[8];
        RNG.nextBytes(buf);
        return CommonUtils.toHex(buf);
    }

    /**
     * 从 tracestate 解析指定 vendor 的全部 key:value 到 Map（按出现顺序保留）。
     * 例如：tracestate="ctx=lane:blue;env:prod, other=foo:bar"
     * getVendorMap(..., "ctx") => {lane=blue, env=prod}
     *
     * @param tracestate 原始 tracestate，可为 null/空
     * @param vendor     目标 vendor 名（如 "ctx"）
     * @return 不可变 Map；未找到或为空返回空 Map
     */
    public static Map<String, String> getVendorMap(String tracestate, String vendor) {
        if (tracestate == null || tracestate.isBlank() || vendor == null || vendor.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        String targetValue = null;
        for (String member : tracestate.split(",")) {
            String m = member.trim();
            if (m.isEmpty()) continue;
            int eq = m.indexOf('=');
            if (eq <= 0) continue;
            String v = m.substring(0, eq).trim();
            String val = m.substring(eq + 1).trim();
            if (vendor.equals(v)) {
                targetValue = val;
                break;
            }
        }
        if (targetValue == null || targetValue.isBlank()) {
            return java.util.Collections.emptyMap();
        }

        Map<String, String> map = new LinkedHashMap<>();
        for (String kv : targetValue.split(";")) {
            String s = kv.trim();
            if (s.isEmpty()) continue;
            int c = s.indexOf(':');
            if (c <= 0) continue;
            String k = s.substring(0, c).trim();
            String val = s.substring(c + 1).trim();
            if (!k.isEmpty() && !val.isEmpty()) {
                map.put(k, val);
            }
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    /**
     * 从 tracestate 按 vendor+key 读取单个值；不存在返回 null。
     */
    public static String getVendorValue(String tracestate, String vendor, String key) {
        if (key == null || key.isBlank()) return null;
        Map<String, String> m = getVendorMap(tracestate, vendor);
        return m.get(key);
    }

    /**
     * 兼容包装：读取 lane（等价于 getVendorValue(ts, "ctx", "lane")）
     */
    public static String getLane(String tracestate) {
        return getVendorValue(tracestate, "ctx", "lane");
    }

    /**
     * 通用版本：在 tracestate 中 upsert 指定 vendor 的 key:value 集合。
     * <ul>
     *     <li>仅修改目标 vendor，其它 vendor 原样保留。</li>
     *     <li>value == null 或空字符串会删除对应 key。</li>
     *     <li>若 vendor 最终无任何键，则整个 vendor 成员不会写入。</li>
     *     <li>目标 vendor 放在最前，其它 vendor 按原顺序保留。</li>
     * </ul>
     *
     * @param tracestate 原始 tracestate 字符串
     * @param vendor     vendor 名称，如 "ctx"、"app"、"rojo"
     * @param updates    要更新或删除的 key:value 映射（null/空值表示删除该键）
     * @return 更新后的 tracestate
     */
    public static String upsertVendorKV(String tracestate, String vendor, Map<String, String> updates) {
        if (vendor == null || vendor.isBlank()) return tracestate;

        String existingValue = null;
        List<String> others = new ArrayList<>();

        // 解析 vendor 成员
        if (tracestate != null && !tracestate.isBlank()) {
            for (String member : tracestate.split(",")) {
                String m = member.trim();
                if (m.isEmpty()) continue;
                int eq = m.indexOf('=');
                if (eq <= 0) {
                    others.add(m);
                    continue;
                }
                String v = m.substring(0, eq).trim();
                String val = m.substring(eq + 1).trim();
                if (vendor.equals(v)) {
                    existingValue = val;
                } else {
                    others.add(m);
                }
            }
        }

        // 解析现有 vendor 的键值
        Map<String, String> map = new LinkedHashMap<>();
        if (existingValue != null && !existingValue.isBlank()) {
            for (String kv : existingValue.split(";")) {
                String s = kv.trim();
                if (s.isEmpty()) continue;
                int c = s.indexOf(':');
                if (c <= 0) continue;
                String k = s.substring(0, c).trim();
                String v = s.substring(c + 1).trim();
                if (!k.isEmpty() && !v.isEmpty()) {
                    map.put(k, v);
                }
            }
        }

        // 应用更新（空值 => 删除）
        if (updates != null && !updates.isEmpty()) {
            for (Map.Entry<String, String> e : updates.entrySet()) {
                String key = e.getKey();
                String val = e.getValue();
                if (key == null || key.isBlank()) continue;
                if (val == null || val.isBlank()) {
                    map.remove(key);
                } else {
                    map.put(key.trim(), val.trim());
                }
            }
        }

        // 拼接结果
        String vendorValue = CommonUtils.joinKv(map);
        List<String> result = new ArrayList<>();
        if (!vendorValue.isBlank()) {
            result.add(vendor + "=" + vendorValue);
        }
        result.addAll(others);
        return String.join(", ", result);
    }

    /**
     * 兼容旧调用：仅更新 lane。
     */
    public static String upsertLane(String tracestate, String lane) {
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("lane", lane); // null = 删除 lane
        return upsertVendorKV(tracestate, "ctx", updates);
    }
    /* -------------------------------------------------------
     * Internals / validation
     * ------------------------------------------------------- */

    private static boolean isValidVersion(String v) {
        // W3C 目前版本 00；但为了前向兼容，仅校验为2位hex
        return v != null && v.length() == 2 && CommonUtils.isHex(v);
    }

    private static boolean isValidTraceId(String s) {
        // 32 hex，且不能全零
        return s != null && s.length() == 32 && CommonUtils.isHex(s) && !CommonUtils.isAllZero(s);
    }

    private static boolean isValidSpanId(String s) {
        // 16 hex，且不能全零
        return s != null && s.length() == 16 && CommonUtils.isHex(s) && !CommonUtils.isAllZero(s);
    }

    private static boolean isValidFlags(String s) {
        // 2 hex
        return s != null && s.length() == 2 && CommonUtils.isHex(s);
    }


}