package com.ddm.chaos.defined;

import com.ddm.chaos.utils.Converters;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置项内容
 */
public final class ConfData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object NULL = new Object();

    private final String resolvedValue;
    private final Map<CacheKey, Object> resolvedValues = new ConcurrentHashMap<>();

    public ConfData(ConfItem item, String[] tags) {
        this(item.value(), item.variants(), tags);
    }

    public ConfData(String value, String variants, String[] tags) {
        this.resolvedValue = resolve(value, variants, tags);
    }


    private static String resolve(String value, String variants, String[] tags) {
        Map<String, String> vmap = parseVariants(variants);
        if (!vmap.isEmpty() && tags.length > 0) {
            for (String t : tags) {
                if (t == null || t.isBlank()) continue;
                String hit = vmap.get(t.trim());
                if (hit != null) {
                    return hit;
                }
            }
        }
        return value;
    }

    /**
     * 获取类型化的配置值。
     * <p>如果转换失败或值为 null，则返回默认值。
     *
     * @param <T>  目标类型
     * @param desc 配置描述符，包含类型和默认值信息
     * @return 转换后的配置值，如果转换失败则返回默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(ConfDesc desc) {
        Object res = resolvedValues.computeIfAbsent(new CacheKey(desc.ref(), desc.type()), key -> {
            try {
                Object cast = Converters.cast(resolvedValue, key.type());
                if (cast != null) {
                    return cast;
                }
            } catch (Exception e) {
                // 转换失败，使用默认值
            }
            return NULL;
        });
        return res == NULL ? (T) desc.defaultValue() : (T) res;
    }

    /**
     * 解析变体配置 JSON 字符串。
     *
     * @param json JSON 字符串，格式如 {"gray":"value1", "blue":"value2"}
     * @return 解析后的 Map，如果解析失败或为空则返回空 Map
     */
    private static Map<String, String> parseVariants(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            // 解析失败时返回空 Map，不抛出异常
            return Collections.emptyMap();
        }
    }

    record CacheKey(ConfRef ref, Type type) {
        public CacheKey(ConfRef ref, Type type) {
            this.ref = Objects.requireNonNull(ref, "ref cannot be null");
            this.type = Objects.requireNonNull(type, "type cannot be null");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey k)) return false;
            return Objects.equals(ref, k.ref) && Objects.equals(typeName(type), typeName(k.type));
        }

        @Override
        public int hashCode() {
            return Objects.hash(ref, typeName(type));
        }

        private static String typeName(Type t) {
            return (t == null) ? "null" : t.getTypeName();
        }

    }

}