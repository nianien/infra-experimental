package com.ddm.chaos.config;

import com.ddm.chaos.defined.ConfDesc;
import com.ddm.chaos.defined.ConfSlot;
import com.ddm.chaos.provider.ConfItem;
import com.ddm.chaos.utils.Converters;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 不可变配置项。
 * <p>
 * <ul>
 *   <li><strong>item</strong>：配置项原始数据（不可变）</li>
 *   <li><strong>resolvedValue</strong>：已解析的生效值（字符串），在构造时计算并缓存</li>
 *   <li><strong>resolvedValues</strong>：类型化值的缓存（线程安全）</li>
 * </ul>
 * <p>
 * <strong>生效值计算规则：</strong>firstNonNull(variant[tag1], variant[tag2], ...) else value
 * <p>在构造时计算并缓存到 resolvedValue；不做类型转换。
 */
public final class ConfigData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object NULL = new Object();

    private final ConfItem item;
    private final String resolvedValue;
    private final Map<ConfSlot, Object> resolvedValues = new ConcurrentHashMap<>();

    public ConfigData(ConfItem item, String[] tags) {
        this.item = item;
        this.resolvedValue = resolve(tags);
    }


    private String resolve(String[] tags) {
        String result = item.value();
        Map<String, String> vmap = parseVariants(item.variant());
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
        Object res = resolvedValues.computeIfAbsent(desc.slot(), key -> {
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


}