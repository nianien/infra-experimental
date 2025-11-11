package com.ddm.chaos.config;

import com.ddm.chaos.defined.ConfDesc;
import com.ddm.chaos.provider.ConfItem;
import com.ddm.chaos.utils.Converters;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 不可变配置项（record 版）
 * <p>
 * <ul>
 *   <li><strong>key</strong>：配置键（非空）</li>
 *   <li><strong>value</strong>：默认值（字符串）</li>
 *   <li><strong>variant</strong>：变体 JSON 字符串，如 {"gray":"...", "blue":"..."}（各值也为字符串）</li>
 *   <li><strong>tags</strong>：标签顺序（运行期固定，按传入数组使用，不做拷贝）</li>
 *   <li><strong>resolvedValue</strong>：已解析的生效值（字符串），在构造时计算并缓存</li>
 * </ul>
 * <p>
 * <strong>生效值计算规则：</strong>firstNonNull(variant[tag1], variant[tag2], ...) else value
 * <p>在构造时计算并缓存到 resolvedValue；不做类型转换。
 */
@Data
public class ConfigData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object NULL = new Object();
    private ConfItem item;
    private String resolvedValue;
    private Map<ConfDesc, Object> resolvedValues = new ConcurrentHashMap<>();

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

    @SuppressWarnings("unchecked")
    public <T> T getValue(ConfDesc desc) {
        var res = resolvedValues.computeIfAbsent(desc, key -> {
            try {
                var cast = Converters.cast(resolvedValue, key.type());
                if (cast != null) {
                    return cast;
                }
            } catch (Exception e) {
            }
            return NULL;
        });
        return res == NULL ? (T)desc.defaultValue() : (T) res;
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