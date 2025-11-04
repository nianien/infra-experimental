package com.ddm.chaos.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;

/**
 * 不可变配置项（record 版）
 * <p>
 * key: 配置键（非空）
 * defaultValue: 默认值（字符串）
 * variant: 变体 JSON 字符串，如 {"gray":"...", "blue":"..."}（各值也为字符串）
 * tags: lane 顺序（运行期固定，按传入数组使用，不做拷贝）
 * <p>
 * 生效值计算：firstNonNull(variant[tag1], variant[tag2], ...) else defaultValue
 * 在构造时计算并缓存到 resolvedValue；不做类型转换。
 */
public record ConfigItem(
        String key,
        String value,
        String variant,
        String[] tags,
        String resolvedValue
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 主构造：用于完整字段初始化（已计算好的 resolvedValue）
     */
    public ConfigItem {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (tags == null) {
            tags = new String[0];
        }
    }

    /**
     * 重载构造：自动计算 resolvedValue
     */
    public ConfigItem(String key, String value, String variant, String... tags) {
        this(key, value, variant, tags, resolve(value, variant, tags));
    }


    private static String resolve(String value, String variant, String[] tags) {
        String result = value;
        Map<String, String> vmap = parseVariants(variant);
        if (!vmap.isEmpty() && tags.length > 0) {
            for (String t : tags) {
                if (t == null || t.isBlank()) continue;
                String hit = vmap.get(t.trim());
                if (hit != null) {
                    result = hit;
                    break;
                }
            }
        }
        return result;
    }

    private static Map<String, String> parseVariants(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ignore) {
            return Collections.emptyMap();
        }
    }

}