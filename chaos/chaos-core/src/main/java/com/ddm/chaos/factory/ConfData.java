package com.ddm.chaos.factory;

import com.ddm.chaos.provider.ConfItem;
import com.ddm.chaos.resolver.ConfDesc;
import com.ddm.chaos.resolver.ConfRef;
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
 * 配置数据是配置中心的核心数据类，负责：
 * <ul>
 *   <li>根据泳道标识（profiles）解析配置的生效值（从 variants 中匹配或使用默认 value）</li>
 *   <li>缓存类型转换后的配置值，避免重复转换，提高性能</li>
 *   <li>提供类型安全的配置值获取方法</li>
 * </ul>
 *
 * <p><strong>生效值计算规则：</strong>
 * <ol>
 *   <li>遍历 profiles 数组，在 variants JSON 中查找匹配的泳道标识</li>
 *   <li>如果找到匹配的泳道值，返回该值作为配置的生效值</li>
 *   <li>如果所有泳道都未匹配，返回默认的 value</li>
 * </ol>
 *
 * <p><strong>类型转换缓存：</strong>
 * 相同配置引用（{@link ConfRef}）和目标类型（{@link Type}）的转换结果会被缓存。
 * 缓存键由配置引用和类型组成（{@link CacheKey}），确保相同配置项和类型的转换结果共享同一个缓存槽位。
 *
 * <p><strong>线程安全：</strong>
 * 该类是线程安全的，可以在多线程环境下并发使用。
 *
 * @author liyifei
 * @see ConfRef
 * @see ConfItem
 * @see ConfDesc
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
     * 从配置项构造配置数据。
     * <p>
     * 根据泳道标识（profiles）从配置项的 variants 中解析出最终的生效值。
     *
     * @param item     配置项，包含默认值和泳道覆盖配置，不能为 null
     * @param profiles 泳道标识数组，按优先级顺序排列，用于匹配 variants 中的值，不能为 null
     */
    public ConfData(ConfItem item, String[] profiles) {
        this(item.value(), item.variants(), profiles);
    }

    /**
     * 从原始值构造配置数据。
     * <p>
     * 根据泳道标识（profiles）从 variants JSON 中解析出最终的生效值。
     *
     * @param value    默认配置值，当没有匹配的泳道配置时使用，不能为 null
     * @param variants 泳道覆盖配置 JSON 字符串，格式如 {"gray":"value1", "blue":"value2"}，可以为 null 或空字符串
     * @param profiles 泳道标识数组，按优先级顺序排列，用于匹配 variants 中的值，不能为 null
     */
    public ConfData(String value, String variants, String[] profiles) {
        this.resolvedValue = resolve(value, variants, profiles);
    }

    /**
     * 解析配置的生效值。
     * <p>
     * 根据泳道标识（profiles）的顺序在 variants JSON 中查找匹配的泳道值。
     * 如果找到匹配的泳道值，返回该值；如果未找到，返回默认的 value。
     *
     * @param value    默认配置值，当没有匹配的泳道配置时使用
     * @param variants 泳道覆盖配置 JSON 字符串，格式如 {"gray":"value1", "blue":"value2"}
     * @param profiles 泳道标识数组，按优先级顺序排列
     * @return 解析后的生效值，如果未找到匹配的泳道值则返回默认 value
     */
    private static String resolve(String value, String variants, String[] profiles) {
        Map<String, String> vmap = parseVariants(variants);
        if (!vmap.isEmpty() && profiles.length > 0) {
            for (String t : profiles) {
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
     * <p>
     * 根据配置描述符（{@link ConfDesc}）中指定的目标类型，将已解析的配置值（字符串形式）转换为目标类型。
     * 转换结果会被缓存，相同配置引用和类型的后续调用会直接返回缓存值。
     *
     * <p><strong>转换规则：</strong>
     * <ul>
     *   <li>如果转换成功，返回转换后的值</li>
     *   <li>如果转换失败或值为 null，返回配置描述符中的默认值（{@link ConfDesc#defaultValue()}）</li>
     * </ul>
     *
     * <p><strong>类型转换：</strong>
     * 类型转换通过 {@link Converters#cast(String, Type)} 实现，支持基本类型、包装类型、字符串、JSON 对象等。
     *
     * @param <T>  目标类型的泛型参数
     * @param desc 配置描述符，包含配置引用、目标类型和默认值信息，不能为 null
     * @return 转换后的配置值，如果转换失败或值为 null 则返回配置描述符中的默认值
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
     * 解析泳道覆盖配置 JSON 字符串。
     * <p>
     * 将 JSON 字符串解析为 Map，其中键为泳道标识，值为对应的配置值。
     * 如果 JSON 字符串为 null、空字符串或解析失败，返回空 Map（不会抛出异常）。
     *
     * @param json 泳道覆盖配置 JSON 字符串，格式如 {"gray":"value1", "blue":"value2"}，可以为 null 或空字符串
     * @return 解析后的 Map，键为泳道标识，值为配置值。如果解析失败或为空则返回空 Map（不会为 null）
     */
    private static Map<String, String> parseVariants(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {
            });
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
     * 缓存键由配置引用（{@link ConfRef}）和目标类型（{@link Type}）组成，
     * 确保相同配置项和类型的转换结果共享同一个缓存槽位。
     *
     * <p><strong>相等性判断：</strong>
     * 两个缓存键相等当且仅当：
     * <ul>
     *   <li>配置引用（ref）相等</li>
     *   <li>目标类型的名称（type.getTypeName()）相等</li>
     * </ul>
     *
     * <p><strong>使用场景：</strong>
     * 在 {@link ConfData#getValue(ConfDesc)} 方法中，使用缓存键来查找或存储类型转换后的配置值，
     * 避免对相同配置项和类型进行重复的类型转换。
     *
     * @param ref  配置引用，标识要缓存的配置项，不能为 null
     * @param type 目标类型，标识配置值的类型，不能为 null
     */
    record CacheKey(ConfRef ref, Type type) {
        /**
         * 构造缓存键。
         * <p>
         * 验证参数不为 null，如果为 null 则抛出 {@link NullPointerException}。
         *
         * @param ref  配置引用，不能为 null
         * @param type 目标类型，不能为 null
         * @throws NullPointerException 如果 ref 或 type 为 null
         */
        public CacheKey(ConfRef ref, Type type) {
            this.ref = Objects.requireNonNull(ref, "ref required");
            this.type = Objects.requireNonNull(type, "type required");
        }

        /**
         * 判断两个缓存键是否相等。
         * <p>
         * 比较配置引用（ref）和目标类型的名称（type.getTypeName()）。
         * 如果两个缓存键的 ref 相等且 type.getTypeName() 相等，则认为两个缓存键相等。
         *
         * @param o 要比较的对象
         * @return 如果相等返回 true，否则返回 false
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey k)) return false;
            return Objects.equals(ref, k.ref) && Objects.equals(typeName(type), typeName(k.type));
        }

        /**
         * 计算哈希码。
         * <p>
         * 基于配置引用（ref）和目标类型的名称（type.getTypeName()）计算哈希码。
         *
         * @return 哈希码
         */
        @Override
        public int hashCode() {
            return Objects.hash(ref, typeName(type));
        }

        /**
         * 获取类型的名称字符串。
         * <p>
         * 如果类型为 null，返回 "null"；否则返回 {@link Type#getTypeName()}。
         *
         * @param t 类型，可以为 null
         * @return 类型名称，如果类型为 null 则返回 "null"
         */
        private static String typeName(Type t) {
            return (t == null) ? "null" : t.getTypeName();
        }
    }

}