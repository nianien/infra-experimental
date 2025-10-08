package com.ddm.argus.utils;

import java.util.Map;

/**
 * @author : liyifei
 * @created : 2025/10/6, Monday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
public class CommonUtils {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') ||
                    (c >= 'a' && c <= 'f') ||
                    (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    public static boolean isAllZero(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != '0') return false;
        return true;
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            out[j++] = HEX[b >>> 4];
            out[j++] = HEX[b & 0x0F];
        }
        return new String(out);
    }

    public static String joinKv(Map<String, String> map) {
        if (map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(';');
            sb.append(e.getKey()).append(':').append(e.getValue());
            first = false;
        }
        return sb.toString();
    }


    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }


    public static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (!isBlank(v)) return v;
        return null;
    }


    public static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static Integer toInt(String s) {
        try {
            return (s == null || s.isBlank()) ? null : Integer.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }


    public static boolean isTrue(String val) {
        if (val == null) return false;
        switch (val.trim().toLowerCase()) {
            case "true":
            case "1":
            case "yes":
            case "on":
                return true;
            default:
                return false;
        }
    }
}
