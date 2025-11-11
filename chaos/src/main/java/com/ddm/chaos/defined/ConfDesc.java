package com.ddm.chaos.defined;

import com.ddm.chaos.utils.Converters;

import java.lang.reflect.Type;

/**
 * 配置描述符，表示开发者声明的配置注入信息。
 * <p>
 * 包含配置引用（{@code ConfRef}）、目标类型（{@code Type}）和默认值（{@code defaultValue}）。
 * 用于从注解（{@code @Conf}）中提取配置信息。
 *
 * @author liyifei
 */
public record ConfDesc(ConfRef ref, Object defaultValue, Type type) {

    /**
     * 使用字符串默认值构造配置描述符。
     * <p>会自动将字符串默认值转换为目标类型。
     *
     * @param ref          配置引用
     * @param defaultValue 默认值字符串
     * @param type         目标类型
     */
    public ConfDesc(ConfRef ref, String defaultValue, Type type) {
        this(ref, (Object) Converters.cast(defaultValue, type), type);
    }

}
