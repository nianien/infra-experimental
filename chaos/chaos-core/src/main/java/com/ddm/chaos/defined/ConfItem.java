package com.ddm.chaos.defined;

import com.ddm.chaos.provider.DataProvider;

/**
 * 配置项，表示从数据源（如数据库）读取的完整配置数据。
 * <p>
 * 配置项包含配置的完整信息，包括配置引用和配置值：
 * <ul>
 *   <li><strong>namespace</strong>：命名空间，用于隔离不同应用的配置</li>
 *   <li><strong>group</strong>：配置分组，用于组织相关配置</li>
 *   <li><strong>key</strong>：配置键，在命名空间和分组内唯一标识一个配置项</li>
 *   <li><strong>value</strong>：默认配置值，字符串形式。当没有匹配的泳道配置时使用此值</li>
 *   <li><strong>variants</strong>：泳道覆盖配置 JSON 字符串，格式如 {"gray":"value1", "blue":"value2"}。
 *       用于根据泳道标识（profiles）匹配不同的配置值</li>
 * </ul>
 *
 * <p><strong>数据来源：</strong>
 * 该 record 通常由 {@link DataProvider} 从数据源（如数据库、远程服务）加载并返回。
 *
 * <p><strong>配置解析：</strong>
 * 配置项可以通过 {@link ConfData} 根据泳道标识（profiles）解析出最终的生效值。
 * 解析规则：
 * <ol>
 *   <li>遍历 profiles 数组，在 variants JSON 中查找匹配的泳道标识</li>
 *   <li>如果找到匹配的泳道值，使用该值作为配置的生效值</li>
 *   <li>如果所有泳道都未匹配，使用默认的 value</li>
 * </ol>
 *
 * @param namespace 命名空间名称，不能为 null
 * @param group     配置分组名称，不能为 null
 * @param key       配置键名称，不能为 null
 * @param value     默认配置值，字符串形式，不能为 null
 * @param variants  泳道覆盖配置 JSON 字符串，可以为 null 或空字符串
 * @author liyifei
 * @see ConfRef
 * @see ConfData
 * @see DataProvider
 * @since 1.0
 */
public record ConfItem(String namespace, String group, String key, String value, String variants) {
}