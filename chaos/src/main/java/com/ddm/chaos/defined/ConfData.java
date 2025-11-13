package com.ddm.chaos.defined;

import com.ddm.chaos.utils.Converters;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置数据，包含已解析的配置值和类型化缓存。
 * <p>
 * 该类负责：
 * <ul>
 *   <li>根据标签（tags）解析配置的生效值（从 variants 中匹配或使用默认 value）</li>
 *   <li>缓存类型转换后的配置值，避免重复转换</li>
 *   <li>提供类型安全的配置值获取方法</li>
 * </ul>
 *
 * <p><strong>生效值计算规则：</strong>
 * <ol>
 *   <li>遍历 tags 数组，在 variants JSON 中查找匹配的标签值</li>
 *   <li>如果找到匹配的标签值，返回该值</li>
 *   <li>如果未找到，返回默认的 value</li>
 * </ol>
 *
 * <p><strong>类型转换缓存：</strong>
 * 相同配置引用和类型的转换结果会被缓存，提高性能。
 *
 * @author liyifei
 * @since 1.0
 */
public final class ConfData {

    private static final Logger log = LoggerFactory.getLogger(ConfData.class);

    /**
     * JSON 解析器，用于解析 variants JSON 字符串
     */
    private static final ObjectMapper JSON = new ObjectMapper();
    
    /**
     * NULL 占位符，用于区分 null 值和未转换的值
     */
    private static final Object NULL = new Object();

    /**
     * 已解析的生效值（字符串形式）
     */
    private final String resolvedValue;
    
    /**
     * 类型化值的缓存，键为 CacheKey（ConfRef + Type），值为转换后的对象
     */
    private final Map<CacheKey, Object> resolvedValues = new ConcurrentHashMap<>();

    /**
     * 从 ConfItem 构造配置数据。
     *
     * @param item 配置项，包含 value 和 variants
     * @param tags 标签数组，用于匹配 variants 中的值
     */
    public ConfData(ConfItem item, String[] tags) {
        this(item.value(), item.variants(), tags);
    }

    /**
     * 从原始值构造配置数据。
     *
     * @param value    默认值
     * @param variants 变体配置 JSON 字符串，格式如 {"gray":"value1", "blue":"value2"}
     * @param tags     标签数组，按顺序匹配 variants
     */
    public ConfData(String value, String variants, String[] tags) {
        this.resolvedValue = resolve(value, variants, tags);
    }

    /**
     * 解析配置的生效值。
     * <p>根据 tags 顺序在 variants 中查找匹配值，如果未找到则返回默认 value。
     *
     * @param value    默认值
     * @param variants 变体配置 JSON 字符串
     * @param tags     标签数组
     * @return 解析后的生效值
     */
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
        CacheKey cacheKey = new CacheKey(desc.ref(), desc.type());
        Object res = resolvedValues.computeIfAbsent(cacheKey, key -> {
            try {
                Object cast = Converters.cast(resolvedValue, key.type());
                if (cast != null) {
                    log.trace("Successfully converted config value for {} to type {}", 
                            desc.ref(), key.type().getTypeName());
                    return cast;
                } else {
                    log.debug("Failed to convert config value for {} to type {}, value is null", 
                            desc.ref(), key.type().getTypeName());
                }
            } catch (Exception e) {
                log.warn("Type conversion failed for {} to type {}, will use default value. " +
                        "Resolved value: '{}', error: {} ({})", 
                        desc.ref(), key.type().getTypeName(), resolvedValue, 
                        e.getClass().getSimpleName(), e.getMessage());
            }
            return NULL;
        });
        
        if (res == NULL) {
            log.trace("Using default value for {} (type: {})", desc.ref(), desc.type().getTypeName());
            return (T) desc.defaultValue();
        }
        return (T) res;
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
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            // 解析失败时返回空 Map，不抛出异常，但记录警告日志
            log.warn("Failed to parse variants JSON: '{}', error: {} ({}). Will use empty variants map.", 
                    json, e.getClass().getSimpleName(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 缓存键，用于标识类型化配置值的缓存槽位。
     * <p>
     * 由配置引用（ConfRef）和目标类型（Type）组成，确保相同配置项和类型的转换结果共享同一个缓存槽位。
     */
    record CacheKey(ConfRef ref, Type type) {
        /**
         * 构造缓存键。
         *
         * @param ref  配置引用，不能为 null
         * @param type 目标类型，不能为 null
         */
        public CacheKey(ConfRef ref, Type type) {
            this.ref = Objects.requireNonNull(ref, "ref cannot be null");
            this.type = Objects.requireNonNull(type, "type cannot be null");
        }

        /**
         * 判断两个缓存键是否相等。
         * <p>比较 ref 和 type.getTypeName()。
         *
         * @param o 要比较的对象
         * @return 如果相等返回 true
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey k)) return false;
            return Objects.equals(ref, k.ref) && Objects.equals(typeName(type), typeName(k.type));
        }

        /**
         * 计算哈希码。
         * <p>基于 ref 和 type.getTypeName()。
         *
         * @return 哈希码
         */
        @Override
        public int hashCode() {
            return Objects.hash(ref, typeName(type));
        }

        /**
         * 获取类型的名称字符串。
         *
         * @param t 类型
         * @return 类型名称，如果为 null 则返回 "null"
         */
        private static String typeName(Type t) {
            return (t == null) ? "null" : t.getTypeName();
        }

    }

}