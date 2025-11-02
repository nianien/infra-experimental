package com.ddm.chaos.annotation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Indexed;

import java.lang.annotation.*;

/**
 * 组合注解：等价于 @Autowired + @Qualifier("key")，
 * 并允许为配置项声明一个默认值（defaultValue）。
 * <p>
 * 用法示例：
 * <pre>
 * @Conf(key = "demo.timeout", value = "3000")
 * private Supplier<Integer> timeout;
 *
 * // 简写形式
 * @Conf("demo.title")   // 默认值为空字符串
 * private Supplier<String> title;
 * </pre>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Autowired
@Qualifier
@Indexed
public @interface Conf {

    /**
     * 配置项 key，对应配置中心中的完整键名。
     * 会透传给 @Qualifier("key")。
     */
    @AliasFor(annotation = Qualifier.class, attribute = "value")
    String key() default "";

    /**
     * 当配置中心未找到该 key 时使用的默认值。
     * 等价于 defaultValue，用 value 这个短名方便写。
     */
    String value() default "";

    /**
     * 是否为必需依赖；false 表示注入可为空或走 fallback。
     * 透传到 @Autowired.required。
     */
    @AliasFor(annotation = Autowired.class, attribute = "required")
    boolean required() default true;
}