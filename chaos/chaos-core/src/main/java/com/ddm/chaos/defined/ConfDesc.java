package com.ddm.chaos.defined;

import com.ddm.chaos.utils.TypeRef;

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


    public <T> ConfDesc(ConfRef ref, T defaultValue, TypeRef<T> typeRef) {
        this(ref, defaultValue, typeRef.getType());
    }


}
