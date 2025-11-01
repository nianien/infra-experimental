package com.ddm.chaos.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用类型转换工具类。
 * 
 * <p>提供智能类型转换功能，支持将任意对象转换为指定类型，包括：
 * <ul>
 *   <li>基础数值类型（byte, short, int, long, float, double, BigInteger, BigDecimal）</li>
 *   <li>时间类型（Duration, Instant, LocalDate, LocalDateTime, OffsetDateTime, ZonedDateTime, Date）</li>
 *   <li>JSON 对象/数组的反序列化</li>
 *   <li>字符串类型</li>
 * </ul>
 * 
 * <p><strong>使用示例：</strong>
 * <pre>{@code
 * // 数值转换
 * Integer value = Converters.cast("123", Integer.class);  // 123
 * Long epoch = Converters.cast("1699000000000", Long.class);
 * 
 * // 时间类型转换
 * Duration duration = Converters.cast("60s", Duration.class);  // 60秒
 * Instant instant = Converters.cast("2023-11-01T10:00:00Z", Instant.class);
 * LocalDate date = Converters.cast("2023-11-01", LocalDate.class);
 * 
 * // JSON 反序列化
 * MyObject obj = Converters.cast("{\"name\":\"test\"}", MyObject.class);
 * }</pre>
 * 
 * <p><strong>特性：</strong>
 * <ul>
 *   <li>自动处理 null 值，返回 null</li>
 *   <li>类型已匹配时直接返回，避免不必要的转换</li>
 *   <li>支持多种时间格式（ISO-8601、epoch 时间戳等）</li>
 *   <li>支持 Duration 的多种格式（纯数字、带单位、ISO-8601）</li>
 *   <li>转换失败时返回 null，不会抛出异常</li>
 * </ul>
 * 
 * @author liyifei
 * @since 1.0
 */
public final class Converters {
    
    private static final Logger log = LoggerFactory.getLogger(Converters.class);
    
    /**
     * 线程安全的 JSON 对象映射器，用于反序列化 JSON 字符串。
     * 配置为忽略未知属性，提高容错性。
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
    /**
     * Duration 解析正则表达式。
     * 匹配格式：纯数字（默认秒）或 数字+单位（ms/s/m/h/d）
     * 示例：60、100ms、5s、10m、1h、2d
     */
    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "^([+-]?\\d+(?:\\.\\d+)?)(ms|s|m|h|d)?$", Pattern.CASE_INSENSITIVE);
    
    /**
     * Epoch 时间戳正则表达式（纯数字，支持正负号）。
     */
    private static final Pattern EPOCH_PATTERN = Pattern.compile("^[+-]?\\d+$");
    
    /**
     * ISO 日期格式正则表达式（yyyy-MM-dd）。
     */
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    
    /**
     * 日期时间格式正则表达式（支持空格或 T 分隔符，可带毫秒）。
     */
    private static final Pattern DATE_TIME_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?$");
    
    /**
     * 私有构造函数，防止实例化。
     */
    private Converters() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
    
    /**
     * 将原始值转换为指定类型。
     * 
     * <p>转换策略（按顺序尝试）：
     * <ol>
     *   <li>如果 raw 为 null，直接返回 null</li>
     *   <li>如果 raw 已经是目标类型，直接转换返回</li>
     *   <li>将 raw 转换为字符串后，根据目标类型进行相应转换：
     *     <ul>
     *       <li>基础数值类型：解析字符串为对应数值</li>
     *       <li>时间类型：解析 ISO-8601、epoch 时间戳等格式</li>
     *       <li>JSON 对象/数组：使用 Jackson 反序列化</li>
     *       <li>其他类型：尝试直接类型转换</li>
     *     </ul>
     *   </li>
     * </ol>
     * 
     * @param <T> 目标类型
     * @param raw 原始值，可以为任意类型
     * @param type 目标类型的 Class 对象
     * @return 转换后的对象，如果转换失败返回 null
     * @throws IllegalArgumentException 如果 type 为 null
     */
    public static <T> T cast(Object raw, Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Target type cannot be null");
        }
        if (raw == null) {
            return null;
        }
        
        // 如果已经是目标类型，直接转换返回
        if (type.isInstance(raw)) {
            return type.cast(raw);
        }
        
        final String str = String.valueOf(raw).trim();
        if (str.isEmpty()) {
            return null;
        }
        
        try {
            // 基础数值类型
            T numericResult = convertNumericType(str, type);
            if (numericResult != null) {
                return numericResult;
            }
            
            // 时间类型
            T timeResult = convertTimeType(str, type);
            if (timeResult != null) {
                return timeResult;
            }
            
            // JSON 对象/数组反序列化
            T jsonResult = convertJsonType(str, type);
            if (jsonResult != null) {
                return jsonResult;
            }
            
            // 兜底：尝试直接类型转换（通常用于字符串）
            if (type == String.class) {
                return type.cast(str);
            }
            // 其他类型无法直接转换
            return null;
            
        } catch (ClassCastException | IllegalArgumentException e) {
            log.debug("Type conversion failed: raw={}, targetType={}", str, type.getSimpleName(), e);
            return null;
        }
    }
    
    /**
     * 转换基础数值类型。
     */
    @SuppressWarnings("unchecked")
    private static <T> T convertNumericType(String str, Class<T> type) {
        try {
            if (type == String.class) {
                return (T) str;
            } else if (type == Byte.class || type == byte.class) {
                return (T) Byte.valueOf(str);
            } else if (type == Short.class || type == short.class) {
                return (T) Short.valueOf(str);
            } else if (type == Integer.class || type == int.class) {
                return (T) Integer.valueOf(str);
            } else if (type == Long.class || type == long.class) {
                return (T) Long.valueOf(str);
            } else if (type == Float.class || type == float.class) {
                return (T) Float.valueOf(str);
            } else if (type == Double.class || type == double.class) {
                return (T) Double.valueOf(str);
            } else if (type == BigInteger.class) {
                return (T) new BigInteger(str);
            } else if (type == BigDecimal.class) {
                return (T) new BigDecimal(str);
            }
        } catch (NumberFormatException e) {
            log.debug("Numeric conversion failed: str={}, type={}", str, type.getSimpleName());
        }
        return null;
    }
    
    /**
     * 转换时间类型。
     */
    @SuppressWarnings("unchecked")
    private static <T> T convertTimeType(String str, Class<T> type) {
        try {
            if (type == Duration.class) {
                Duration duration = parseDuration(str);
                return duration != null ? (T) duration : null;
            } else if (type == Instant.class) {
                Instant instant = parseInstant(str);
                return instant != null ? (T) instant : null;
            } else if (type == LocalDate.class) {
                LocalDate localDate = parseLocalDate(str);
                return localDate != null ? (T) localDate : null;
            } else if (type == LocalDateTime.class) {
                LocalDateTime localDateTime = parseLocalDateTime(str);
                return localDateTime != null ? (T) localDateTime : null;
            } else if (type == OffsetDateTime.class) {
                OffsetDateTime offsetDateTime = parseOffsetDateTime(str);
                return offsetDateTime != null ? (T) offsetDateTime : null;
            } else if (type == ZonedDateTime.class) {
                ZonedDateTime zonedDateTime = parseZonedDateTime(str);
                return zonedDateTime != null ? (T) zonedDateTime : null;
            } else if (type == java.util.Date.class) {
                Instant instant = parseInstant(str);
                return instant != null ? (T) java.util.Date.from(instant) : null;
            }
        } catch (Exception e) {
            log.debug("Time type conversion failed: str={}, type={}", str, type.getSimpleName(), e);
        }
        return null;
    }
    
    /**
     * 转换 JSON 类型（对象或数组）。
     */
    private static <T> T convertJsonType(String str, Class<T> type) {
        // 判断是否为 JSON 格式（对象或数组）
        boolean isJsonObject = str.startsWith("{") && str.endsWith("}");
        boolean isJsonArray = str.startsWith("[") && str.endsWith("]");
        
        if (!isJsonObject && !isJsonArray) {
            return null;
        }
        
        try {
            return JSON.readValue(str, type);
        } catch (JsonProcessingException e) {
            log.debug("JSON parse failed: str={}, type={}", str, type.getSimpleName(), e);
            return null;
        }
    }
    
    /* ===================== 时间类型解析方法 ===================== */
    
    /**
     * 解析 Duration 字符串。
     * 
     * <p>支持的格式：
     * <ul>
     *   <li><strong>纯数字</strong>：默认单位为秒，如 {@code "60"} → 60秒</li>
     *   <li><strong>带单位</strong>：
     *     <ul>
     *       <li>{@code "100ms"} → 100毫秒</li>
     *       <li>{@code "5s"} → 5秒</li>
     *       <li>{@code "10m"} → 10分钟</li>
     *       <li>{@code "1h"} → 1小时</li>
     *       <li>{@code "2d"} → 2天</li>
     *     </ul>
     *   </li>
     *   <li><strong>ISO-8601</strong>：如 {@code "PT10S"}、{@code "PT1H30M"}</li>
     * </ul>
     * 
     * @param str 待解析的字符串，支持多种格式
     * @return 解析成功的 Duration 对象，失败返回 null
     */
    private static Duration parseDuration(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        
        try {
            String input = str.trim();
            
            // 优先尝试 ISO-8601 格式（以 "P" 开头）
            if (input.startsWith("P")) {
                return Duration.parse(input);
            }
            
            // 使用正则匹配数值+单位格式
            Matcher matcher = DURATION_PATTERN.matcher(input);
            if (matcher.matches()) {
                double value = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2);
                String normalizedUnit = (unit != null) ? unit.toLowerCase() : "";
                
                return switch (normalizedUnit) {
                    case "ms" -> Duration.ofMillis((long) value);
                    case "s", "" -> Duration.ofMillis((long) (value * 1000)); // 无单位或秒
                    case "m" -> Duration.ofMinutes((long) value);
                    case "h" -> Duration.ofHours((long) value);
                    case "d" -> Duration.ofDays((long) value);
                    default -> null;
                };
            }
            
            // 如果正则不匹配，尝试 ISO-8601 格式（可能是不标准的格式）
            try {
                return Duration.parse(input);
            } catch (Exception ignored) {
                // ISO-8601 解析也失败，返回 null
            }
            
        } catch (Exception e) {
            log.debug("Failed to parse duration: {}", str, e);
        }
        return null;
    }
    
    /**
     * 解析 Instant 字符串。
     * 
     * <p>支持的格式：
     * <ul>
     *   <li><strong>ISO-8601</strong>：如 {@code "2023-11-01T10:00:00Z"}</li>
     *   <li><strong>Epoch 时间戳</strong>：
     *     <ul>
     *       <li>13位数字 → 毫秒时间戳</li>
     *       <li>10位数字 → 秒时间戳</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * @param str 待解析的字符串
     * @return 解析成功的 Instant 对象，失败返回 null
     */
    private static Instant parseInstant(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        
        try {
            String input = str.trim();
            
            // 尝试解析 epoch 时间戳（纯数字）
            if (EPOCH_PATTERN.matcher(input).matches()) {
                long epoch = Long.parseLong(input);
                // 根据数字长度判断：13位及以上为毫秒，否则为秒
                return (input.length() >= 13) 
                        ? Instant.ofEpochMilli(epoch) 
                        : Instant.ofEpochSecond(epoch);
            }
            
            // 尝试 ISO-8601 格式
            return Instant.parse(input);
            
        } catch (Exception e) {
            log.debug("Failed to parse instant: {}", str, e);
            return null;
        }
    }
    
    /**
     * 解析 LocalDate 字符串。
     * 
     * <p>支持的格式：
     * <ul>
     *   <li><strong>ISO 日期格式</strong>：{@code "2023-11-01"}</li>
     *   <li><strong>Epoch 时间戳</strong>：自动转换为 UTC 日期</li>
     * </ul>
     * 
     * @param str 待解析的字符串
     * @return 解析成功的 LocalDate 对象，失败返回 null
     */
    private static LocalDate parseLocalDate(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        
        try {
            String input = str.trim();
            
            // 尝试 ISO 日期格式（yyyy-MM-dd）
            if (ISO_DATE_PATTERN.matcher(input).matches()) {
                return LocalDate.parse(input, DateTimeFormatter.ISO_LOCAL_DATE);
            }
            
            // 尝试从 epoch 时间戳推断
            Instant instant = parseInstant(input);
            if (instant != null) {
                return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toLocalDate();
            }
            
        } catch (Exception e) {
            log.debug("Failed to parse local date: {}", str, e);
        }
        return null;
    }
    
    /**
     * 解析 LocalDateTime 字符串。
     * 
     * <p>支持的格式：
     * <ul>
     *   <li><strong>ISO 日期时间格式</strong>：{@code "2023-11-01T10:00:00"} 或 {@code "2023-11-01 10:00:00"}</li>
     *   <li><strong>Epoch 时间戳</strong>：自动转换为 UTC 本地时间</li>
     * </ul>
     * 
     * @param str 待解析的字符串
     * @return 解析成功的 LocalDateTime 对象，失败返回 null
     */
    private static LocalDateTime parseLocalDateTime(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        
        try {
            String input = str.trim();
            
            // 尝试日期时间格式（支持空格或 T 分隔符，可带毫秒）
            if (DATE_TIME_PATTERN.matcher(input).matches()) {
                String normalized = input.replace(' ', 'T');
                return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            
            // 尝试从 epoch 时间戳推断
            Instant instant = parseInstant(input);
            if (instant != null) {
                return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            }
            
        } catch (Exception e) {
            log.debug("Failed to parse local date time: {}", str, e);
        }
        return null;
    }
    
    /**
     * 解析 OffsetDateTime 字符串。
     * 
     * <p>支持的格式：
     * <ul>
     *   <li><strong>ISO 偏移日期时间格式</strong>：{@code "2023-11-01T10:00:00+08:00"}</li>
     *   <li><strong>Epoch 时间戳</strong>：转换为 UTC 偏移时间</li>
     * </ul>
     * 
     * @param str 待解析的字符串
     * @return 解析成功的 OffsetDateTime 对象，失败返回 null
     */
    private static OffsetDateTime parseOffsetDateTime(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        
        try {
            String input = str.trim();
            
            // 先尝试标准 ISO 偏移日期时间格式
            try {
                return OffsetDateTime.parse(input, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception ignored) {
                // 继续尝试其他格式
            }
            
            // 尝试从 epoch 时间戳推断（使用 UTC 偏移）
            Instant instant = parseInstant(input);
            if (instant != null) {
                return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
            }
            
        } catch (Exception e) {
            log.debug("Failed to parse offset date time: {}", str, e);
        }
        return null;
    }
    
    /**
     * 解析 ZonedDateTime 字符串。
     * 
     * <p>支持的格式：
     * <ul>
     *   <li><strong>ISO 时区日期时间格式</strong>：{@code "2023-11-01T10:00:00+08:00[Asia/Shanghai]"}</li>
     *   <li><strong>Epoch 时间戳</strong>：转换为 UTC 时区时间</li>
     * </ul>
     * 
     * @param str 待解析的字符串
     * @return 解析成功的 ZonedDateTime 对象，失败返回 null
     */
    private static ZonedDateTime parseZonedDateTime(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        
        try {
            String input = str.trim();
            
            // 先尝试标准 ISO 时区日期时间格式
            try {
                return ZonedDateTime.parse(input, DateTimeFormatter.ISO_ZONED_DATE_TIME);
            } catch (Exception ignored) {
                // 继续尝试其他格式
            }
            
            // 尝试从 epoch 时间戳推断（使用 UTC 时区）
            Instant instant = parseInstant(input);
            if (instant != null) {
                return ZonedDateTime.ofInstant(instant, ZoneOffset.UTC);
            }
            
        } catch (Exception e) {
            log.debug("Failed to parse zoned date time: {}", str, e);
        }
        return null;
    }
}
