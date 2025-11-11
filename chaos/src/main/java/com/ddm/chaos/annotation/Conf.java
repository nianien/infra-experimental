package com.ddm.chaos.annotation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Indexed;

import java.lang.annotation.*;

/**
 * {@code @Conf} — 组合注解：等价于 {@code @Autowired} + {@code @Qualifier("key")}，
 * 并允许为配置项声明一个默认值（{@code defaultValue}）。
 *
 * <p>语义：
 * <ul>
 *   <li>{@code key}：配置中心中的完整键名，并透传给 {@code @Qualifier.defaultValue}</li>
 *   <li>{@code defaultValue}：当配置中心未找到该 {@code key} 时使用的默认值（可选）</li>
 *   <li>{@code required}：是否为必需依赖；{@code false} 表示注入可为空或走 fallback</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>{@code
 * // 指定键名与默认值
 * @Conf(key = "demo.timeout", defaultValue = "3000")
 * private Supplier<Integer> timeout;
 *
 * // 只指定键名（无默认值）
 * @Conf(key = "demo.title")
 * private Supplier<String> title;
 * }</pre>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Autowired
@Indexed
public @interface Conf {

    /**
     * 配置项
     *
     * @return
     */
    String key() default "";

    /**
     * 命名空间
     */
    String namespace() default "";


    /**
     * 配置分组
     */
    String group() default "";

    /**
     * 未命中该 {@code key} 时使用的默认值（可选）。
     */
    String defaultValue() default "";


}