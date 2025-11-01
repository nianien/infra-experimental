package com.ddm.chaos.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Converters} 类的单元测试。
 * 
 * @author liyifei
 */
class ConvertersTest {

    @Test
    void testCastNullValue() {
        assertNull(Converters.cast(null, String.class));
        assertNull(Converters.cast(null, Integer.class));
        assertNull(Converters.cast(null, Long.class));
    }

    @Test
    void testCastAlreadyCorrectType() {
        String str = "test";
        assertEquals(str, Converters.cast(str, String.class));
        
        Integer num = 123;
        assertEquals(num, Converters.cast(num, Integer.class));
    }

    // ==================== 基础数值类型测试 ====================

    @Test
    void testCastToByte() {
        assertEquals(Byte.valueOf((byte) 123), Converters.cast("123", Byte.class));
        assertEquals((byte) 123, Converters.cast("123", byte.class));
    }

    @Test
    void testCastToShort() {
        assertEquals(Short.valueOf((short) 12345), Converters.cast("12345", Short.class));
        assertEquals((short) 12345, Converters.cast("12345", short.class));
    }

    @Test
    void testCastToInteger() {
        assertEquals(Integer.valueOf(123456), Converters.cast("123456", Integer.class));
        assertEquals(123456, Converters.cast("123456", int.class));
    }

    @Test
    void testCastToLong() {
        assertEquals(Long.valueOf(123456789L), Converters.cast("123456789", Long.class));
        assertEquals(123456789L, Converters.cast("123456789", long.class));
    }

    @Test
    void testCastToFloat() {
        Float result = Converters.cast("123.456", Float.class);
        assertNotNull(result);
        assertEquals(123.456f, result, 0.001f);
    }

    @Test
    void testCastToDouble() {
        Double result = Converters.cast("123.456", Double.class);
        assertNotNull(result);
        assertEquals(123.456, result, 0.001);
    }

    @Test
    void testCastToBigInteger() {
        BigInteger result = Converters.cast("12345678901234567890", BigInteger.class);
        assertNotNull(result);
        assertEquals(new BigInteger("12345678901234567890"), result);
    }

    @Test
    void testCastToBigDecimal() {
        BigDecimal result = Converters.cast("123.456789", BigDecimal.class);
        assertNotNull(result);
        assertEquals(new BigDecimal("123.456789"), result);
    }

    // ==================== Duration 类型测试 ====================

    @Test
    void testCastToDuration_NumberOnly() {
        Duration duration = Converters.cast("60", Duration.class);
        assertNotNull(duration);
        assertEquals(Duration.ofSeconds(60), duration);
    }

    @Test
    void testCastToDuration_WithMilliseconds() {
        Duration duration = Converters.cast("100ms", Duration.class);
        assertNotNull(duration);
        assertEquals(Duration.ofMillis(100), duration);
    }

    @Test
    void testCastToDuration_WithSeconds() {
        Duration duration = Converters.cast("30s", Duration.class);
        assertNotNull(duration);
        assertEquals(Duration.ofSeconds(30), duration);
    }

    @Test
    void testCastToDuration_WithMinutes() {
        Duration duration = Converters.cast("5m", Duration.class);
        assertNotNull(duration);
        assertEquals(Duration.ofMinutes(5), duration);
    }

    @Test
    void testCastToDuration_WithHours() {
        Duration duration = Converters.cast("2h", Duration.class);
        assertNotNull(duration);
        assertEquals(Duration.ofHours(2), duration);
    }

    @Test
    void testCastToDuration_WithDays() {
        Duration duration = Converters.cast("1d", Duration.class);
        assertNotNull(duration);
        assertEquals(Duration.ofDays(1), duration);
    }

    @Test
    void testCastToDuration_ISO8601() {
        Duration duration = Converters.cast("PT10S", Duration.class);
        assertNotNull(duration);
        assertEquals(Duration.ofSeconds(10), duration);
    }

    @Test
    void testCastToDuration_ISO8601Complex() {
        Duration duration = Converters.cast("PT1H30M", Duration.class);
        assertNotNull(duration);
        assertEquals(Duration.ofHours(1).plusMinutes(30), duration);
    }

    // ==================== Instant 类型测试 ====================

    @Test
    void testCastToInstant_ISO8601() {
        Instant instant = Converters.cast("2023-11-01T10:00:00Z", Instant.class);
        assertNotNull(instant);
        assertEquals(Instant.parse("2023-11-01T10:00:00Z"), instant);
    }

    @Test
    void testCastToInstant_EpochSeconds() {
        Instant instant = Converters.cast("1699000000", Instant.class);
        assertNotNull(instant);
        assertEquals(Instant.ofEpochSecond(1699000000L), instant);
    }

    @Test
    void testCastToInstant_EpochMilliseconds() {
        Instant instant = Converters.cast("1699000000000", Instant.class);
        assertNotNull(instant);
        assertEquals(Instant.ofEpochMilli(1699000000000L), instant);
    }

    // ==================== LocalDate 类型测试 ====================

    @Test
    void testCastToLocalDate_ISOFormat() {
        LocalDate date = Converters.cast("2023-11-01", LocalDate.class);
        assertNotNull(date);
        assertEquals(LocalDate.of(2023, 11, 1), date);
    }

    // ==================== LocalDateTime 类型测试 ====================

    @Test
    void testCastToLocalDateTime_ISOFormat() {
        LocalDateTime dateTime = Converters.cast("2023-11-01T10:00:00", LocalDateTime.class);
        assertNotNull(dateTime);
        assertEquals(LocalDateTime.of(2023, 11, 1, 10, 0, 0), dateTime);
    }

    @Test
    void testCastToLocalDateTime_WithSpace() {
        LocalDateTime dateTime = Converters.cast("2023-11-01 10:00:00", LocalDateTime.class);
        assertNotNull(dateTime);
        assertEquals(LocalDateTime.of(2023, 11, 1, 10, 0, 0), dateTime);
    }

    // ==================== Date 类型测试 ====================

    @Test
    void testCastToDate_ISO8601() {
        Date date = Converters.cast("2023-11-01T10:00:00Z", Date.class);
        assertNotNull(date);
        assertEquals(Date.from(Instant.parse("2023-11-01T10:00:00Z")), date);
    }

    // ==================== JSON 类型测试 ====================

    @Test
    void testCastToJsonObject() {
        String json = "{\"name\":\"test\",\"age\":30}";
        TestPojo pojo = Converters.cast(json, TestPojo.class);
        assertNotNull(pojo);
        assertEquals("test", pojo.name);
        assertEquals(30, pojo.age);
    }

    @Test
    void testCastToJsonArray() {
        String json = "[1,2,3]";
        Integer[] array = Converters.cast(json, Integer[].class);
        assertNotNull(array);
        assertEquals(3, array.length);
        assertArrayEquals(new Integer[]{1, 2, 3}, array);
    }

    // ==================== 错误处理测试 ====================

    @Test
    void testCastInvalidNumber() {
        assertNull(Converters.cast("not-a-number", Integer.class));
    }

    @Test
    void testCastEmptyString() {
        // 空字符串转换为 String 类型时，会直接返回（因为已经是 String 类型）
        // 但如果 trim 后为空，convertNumericType 中会直接返回原始字符串
        // 根据实际代码逻辑，空字符串会返回空字符串本身，这是预期的行为
        String result1 = Converters.cast("", String.class);
        assertNotNull(result1);
        assertTrue(result1.isEmpty());
        
        // 空白字符串会被 trim，但如果是 String 类型直接转换，会返回原始值
        String result2 = Converters.cast("   ", String.class);
        assertNotNull(result2);
        assertEquals("   ", result2); // 对于已经是 String 类型的，不会 trim
        
        // 非空字符串应该正常返回
        assertEquals("test", Converters.cast("test", String.class));
        
        // 但如果是其他类型，空字符串应该返回 null
        assertNull(Converters.cast("", Integer.class));
        assertNull(Converters.cast("   ", Integer.class));
    }

    @Test
    void testCastInvalidDuration() {
        assertNull(Converters.cast("invalid-duration", Duration.class));
    }

    // ==================== 辅助类 ====================

    /**
     * 测试用的 POJO 类，用于 JSON 反序列化测试。
     */
    public static class TestPojo {
        public String name;
        public int age;

        public TestPojo() {
        }

        public TestPojo(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }
}

