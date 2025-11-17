package com.ddm.argus.utils;

import software.amazon.awssdk.services.ecs.model.Tag;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author : liyifei
 * @created : 2025/10/6, Monday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
public class CommonUtils {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 是否为 16 进制字符串（null/空返回 false）。 */
    public static boolean isHex(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) == -1) return false;
        }
        return true;
    }

    /** 是否全部为字符 '0'（null/空返回 false）。 */
    public static boolean isAllZero(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != '0') return false;
        return true;
    }

    /** 字节数组转 16 进制小写字符串。 */
    public static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        char[] out = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int b = Byte.toUnsignedInt(bytes[i]);
            out[j++] = HEX[b >>> 4];
            out[j++] = HEX[b & 0x0F];
        }
        return new String(out);
    }


    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public static boolean notBlank(String s) {
        return !isBlank(s);
    }

    public static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static Integer toInt(String s) {
        try {
            return (s == null || s.isBlank()) ? null : Integer.valueOf(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 常见真值解析：true/1/yes/on/y 均视为 true（大小写不敏感）。 */
    public static boolean isTrue(String val) {
        if (val == null) return false;
        switch (val.trim().toLowerCase(Locale.ROOT)) {
            case "true":
            case "1":
            case "yes":
            case "y":
            case "on":
                return true;
            default:
                return false;
        }
    }

    public static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (!isBlank(v)) return v;
        return null;
    }

    /** 用逗号分隔，去空白、去重、保持顺序。 */
    public static List<String> splitToUniqueList(String raw) {
        if (isBlank(raw)) return List.of();
        // 如果编译目标 < 16，可改为 Collectors.toList() 并再包一层不可变
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(CommonUtils::notBlank)
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    public static Optional<String> firstIgnoreCase(List<Tag> tags, String key) {
        if (tags == null || key == null) return Optional.empty();
        for (Tag t : tags) {
            if (t.key() != null && t.key().equalsIgnoreCase(key)) {
                final String v = t.value();
                if (notBlank(v)) return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public static void putIfNotNull(Map<String, Object> m, String k, Object v) {
        if (m == null || k == null || v == null) return;
        if (v instanceof String s && s.isBlank()) return;
        m.put(k, v);
    }
}