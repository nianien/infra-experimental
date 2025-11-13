package com.ddm.chaos.defined;

/**
 * 配置项，表示从数据源（如数据库）读取的完整配置数据。
 * <p>
 * 包含配置的完整信息：
 * <ul>
 *   <li><strong>namespace</strong>：命名空间，用于隔离不同应用的配置</li>
 *   <li><strong>group</strong>：配置分组，用于组织相关配置</li>
 *   <li><strong>key</strong>：配置键，唯一标识一个配置项</li>
 *   <li><strong>value</strong>：默认值，字符串形式</li>
 *   <li><strong>variants</strong>：变体配置 JSON 字符串，格式如 {"gray":"value1", "blue":"value2"}，用于不同标签的配置覆盖</li>
 * </ul>
 *
 * <p>该 record 通常由 {@link com.ddm.chaos.provider.DataProvider} 从数据源加载并返回。
 *
 * @author liyifei
 * @see ConfRef
 * @see ConfData
 * @since 1.0
 */
public record ConfItem(String namespace, String group, String key, String value, String variants) {
}