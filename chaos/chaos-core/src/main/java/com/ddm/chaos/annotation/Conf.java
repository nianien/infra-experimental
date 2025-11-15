package com.ddm.chaos.annotation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Indexed;

import java.lang.annotation.*;

/**
 * {@code @Conf} — 配置注入注解，用于将配置中心中的配置项注入到 Spring Bean 中。
 * <p>
 * 该注解等价于 {@code @Autowired}，并支持通过命名空间、分组和键名定位配置项。
 * 注入的目标类型必须是 {@code Supplier<T>}，其中 T 是配置值的类型。
 *
 * <p><strong>配置定位：</strong>
 * 配置项通过三个维度定位：
 * <ul>
 *   <li><strong>namespace</strong>：命名空间，用于隔离不同应用的配置</li>
 *   <li><strong>group</strong>：配置分组，用于组织相关配置</li>
 *   <li><strong>key</strong>：配置键，唯一标识一个配置项</li>
 * </ul>
 *
 * <p><strong>默认值：</strong>
 * 如果配置中心中未找到指定的配置项，或配置值转换失败，将使用 {@code defaultValue}。
 * 如果未指定默认值，则返回 null。
 *
 * <p><strong>使用示例：</strong>
 * <pre>{@code
 * // 完整配置（命名空间 + 分组 + 键名 + 默认值）
 * @Conf(namespace = "chaos", group = "cfd", key = "timeout", defaultValue = "30s")
 * private Supplier<Duration> timeout;
 *
 * // 只指定键名和默认值
 * @Conf(key = "demo.timeout", defaultValue = "3000")
 * private Supplier<Integer> timeout;
 *
 * // 只指定键名（无默认值）
 * @Conf(key = "demo.title")
 * private Supplier<String> title;
 * }</pre>
 *
 * @author liyifei
 * @since 1.0
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Autowired
@Indexed
public @interface Conf {

    /**
     * 配置键，唯一标识一个配置项。
     * <p>
     * 例如："timeout"、"app.name"、"database.url" 等。
     *
     * @return 配置键，默认为空字符串
     */
    String key() default "";

    /**
     * 命名空间，用于隔离不同应用的配置。
     * <p>
     * 例如："chaos"、"com.ddm" 等。
     *
     * @return 命名空间，默认为空字符串
     */
    String namespace() default "";

    /**
     * 配置分组，用于组织相关配置。
     * <p>
     * 例如："cfd"、"payment"、"order" 等。
     *
     * @return 配置分组，默认为空字符串
     */
    String group() default "";

    /**
     * 默认值，当配置中心未找到该配置项或配置值转换失败时使用。
     * <p>
     * 默认值会按照目标类型进行转换。例如：
     * <ul>
     *   <li>目标类型为 {@code Integer}，默认值为 "3000" → 转换为 3000</li>
     *   <li>目标类型为 {@code Duration}，默认值为 "30s" → 转换为 30 秒</li>
     * </ul>
     *
     * @return 默认值字符串，默认为空字符串（表示无默认值）
     */
    String defaultValue() default "";

}