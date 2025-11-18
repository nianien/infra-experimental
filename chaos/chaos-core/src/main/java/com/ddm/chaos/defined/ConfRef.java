package com.ddm.chaos.defined;

/**
 * 配置引用，用于唯一标识一个配置项的逻辑主键。
 * <p>
 * 配置引用由三个字段组成：
 * <ul>
 *   <li><strong>namespace</strong>：命名空间，用于隔离不同应用的配置</li>
 *   <li><strong>group</strong>：配置分组，用于组织相关配置</li>
 *   <li><strong>key</strong>：配置键，在命名空间和分组内唯一标识一个配置项</li>
 * </ul>
 *
 * <p>配置引用不包含配置值、类型、默认值等数据信息，仅作为配置项的坐标/引用。
 * 通过配置引用可以从配置中心获取对应的配置数据（{@link ConfItem}）。
 *
 * <p><strong>唯一性规则：</strong>
 * 在同一个命名空间和分组内，配置键必须唯一。即 (namespace, group, key) 三元组唯一标识一个配置项。
 *
 * <p><strong>使用场景：</strong>
 * <ul>
 *   <li>作为配置项的查找键，从数据源获取配置数据</li>
 *   <li>作为缓存的键，标识类型化配置值的缓存槽位</li>
 *   <li>作为配置描述符（{@link ConfDesc}）的一部分，描述需要注入的配置</li>
 * </ul>
 *
 * @param namespace 命名空间名称，不能为 null
 * @param group     配置分组名称，不能为 null
 * @param key       配置键名称，不能为 null
 * @author liyifei
 * @see ConfItem
 * @see ConfDesc
 * @see ConfData
 * @since 1.0
 */
public record ConfRef(String namespace, String group, String key) {
}

