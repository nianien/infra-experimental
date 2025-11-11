package com.ddm.chaos.defined;

/**
 * 配置引用，仅标识配置的逻辑主键。
 * <p>
 * 包含 namespace、group、key 三个字段，用于唯一标识一条配置项。
 * 不包含值、类型、默认值等信息，仅作为配置项的坐标/引用。
 *
 * @author liyifei
 */
public record ConfRef(String namespace, String group, String key) {
}

