package com.ddm.chaos.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * @author : liyifei
 * @created : 2025/10/31, Friday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
public class Converters {
    /**
     * 线程安全的单例 ObjectMapper
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 智能类型转换 + JSON 反序列化；失败返回 null
     */
    public static <T> T cast(Object raw, Class<T> type) {
        if (raw == null) return null;
        if (type.isInstance(raw)) return type.cast(raw);

        final String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;

        try {
            // --- 基础与扩展数值类型 ---
            if (type == String.class) return type.cast(s);
            if (type == Byte.class || type == byte.class) return type.cast(Byte.valueOf(s));
            if (type == Short.class || type == short.class) return type.cast(Short.valueOf(s));
            if (type == Integer.class || type == int.class) return type.cast(Integer.valueOf(s));
            if (type == Long.class || type == long.class) return type.cast(Long.valueOf(s));
            if (type == Float.class || type == float.class) return type.cast(Float.valueOf(s));
            if (type == Double.class || type == double.class) return type.cast(Double.valueOf(s));
            if (type == BigInteger.class) return type.cast(new BigInteger(s));
            if (type == BigDecimal.class) return type.cast(new BigDecimal(s));

            // --- Duration ---
            if (type == Duration.class) return type.cast(parseDuration(s));

            // --- Java Time (优先 ISO-8601，其次 epoch) ---
            if (type == Instant.class) return type.cast(parseInstant(s));
            if (type == LocalDate.class) return type.cast(parseLocalDate(s));
            if (type == LocalDateTime.class) return type.cast(parseLocalDateTime(s));
            if (type == OffsetDateTime.class) return type.cast(parseOffsetDateTime(s));
            if (type == ZonedDateTime.class) return type.cast(parseZonedDateTime(s));
            if (type == java.util.Date.class) {
                Instant inst = parseInstant(s);
                return inst == null ? null : type.cast(java.util.Date.from(inst));
            }

            // --- JSON 对象/数组 ---
            if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) {
                try {
                    return JSON.readValue(s, type);
                } catch (JsonProcessingException e) {
                    System.err.println("[cast] JSON parse failed: " + e.getMessage());
                }
            }

            // --- 兜底：尝试直接强转字符串 ---
            return type.cast(s);
        } catch (Exception e) {
            System.err.println("[cast] convert failed: raw=" + s + " -> " + type.getSimpleName()
                    + ", error=" + e.getMessage());
            return null;
        }
    }

    /* ===================== helpers ===================== */

    /**
     * 支持：纯数字=秒；或带单位的简写（ms/s/m/h）；或 ISO-8601（PT10S）。
     */
    private static Duration parseDuration(String s) {
        try {
            String v = s.trim().toLowerCase();
            if (v.matches("^[+-]?\\d+(\\.\\d+)?$")) { // 纯数字→秒（支持小数）
                double seconds = Double.parseDouble(v);
                return Duration.ofMillis((long) (seconds * 1000L));
            }
            if (v.endsWith("ms")) return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
            if (v.endsWith("s")) return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
            if (v.endsWith("m")) return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1)));
            if (v.endsWith("h")) return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1)));
            // ISO-8601：PTnS/PTnM/PTnH/PTnHnMnS...
            return Duration.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Instant: 支持 ISO-8601；或 epoch 秒/毫秒（纯数字长度判断）
     */
    private static Instant parseInstant(String s) {
        try {
            String v = s.trim();
            if (v.matches("^[+-]?\\d+$")) { // epoch
                long n = Long.parseLong(v);
                // 粗略判断：13位≈毫秒，10位≈秒
                if (v.length() >= 13) return Instant.ofEpochMilli(n);
                return Instant.ofEpochSecond(n);
            }
            return Instant.parse(v); // ISO_INSTANT
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * LocalDate: 优先 ISO_LOCAL_DATE（yyyy-MM-dd），否则尝试从 Instant/epoch 推断为 UTC 日期
     */
    private static LocalDate parseLocalDate(String s) {
        try {
            String v = s.trim();
            if (v.matches("^\\d{4}-\\d{2}-\\d{2}$")) return LocalDate.parse(v, DateTimeFormatter.ISO_LOCAL_DATE);
            Instant inst = parseInstant(v);
            return (inst != null) ? LocalDateTime.ofInstant(inst, ZoneOffset.UTC).toLocalDate() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * LocalDateTime: 优先 ISO_LOCAL_DATE_TIME；否则从 Instant(epoch/ISO) 转为 UTC 本地时间
     */
    private static LocalDateTime parseLocalDateTime(String s) {
        try {
            String v = s.trim();
            // 带空格的常见格式做容错：yyyy-MM-dd HH:mm:ss
            if (v.matches("^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$")) {
                String normalized = v.replace(' ', 'T');
                return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            Instant inst = parseInstant(v);
            return (inst != null) ? LocalDateTime.ofInstant(inst, ZoneOffset.UTC) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * OffsetDateTime: 优先 ISO_OFFSET_DATE_TIME；否则从 Instant 构造 UTC 偏移
     */
    private static OffsetDateTime parseOffsetDateTime(String s) {
        try {
            String v = s.trim();
            // 先尝试标准格式
            try {
                return OffsetDateTime.parse(v, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception ignore) {
            }
            Instant inst = parseInstant(v);
            return (inst != null) ? OffsetDateTime.ofInstant(inst, ZoneOffset.UTC) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ZonedDateTime: 优先 ISO_ZONED_DATE_TIME；否则从 Instant + UTC 构造
     */
    private static ZonedDateTime parseZonedDateTime(String s) {
        try {
            String v = s.trim();
            try {
                return ZonedDateTime.parse(v, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            } catch (Exception ignore) {
            }
            Instant inst = parseInstant(v);
            return (inst != null) ? ZonedDateTime.ofInstant(inst, ZoneOffset.UTC) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
