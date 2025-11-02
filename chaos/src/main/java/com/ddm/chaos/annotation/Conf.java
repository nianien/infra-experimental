package com.ddm.chaos.annotation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Indexed;

import java.lang.annotation.*;

/**
 * 组合注解：既能触发自动注入，又能携带 Qualifier 名称。
 * <p>
 * 该注解等价于 {@code @Autowired @Qualifier("key")}，但更简洁优雅。
 * <p>
 * <strong>使用示例：</strong>
 * <pre>
 * &#64;Component
 * public class DemoBean {
 *     &#64;Conf("demo.name")
 *     private Supplier&lt;String&gt; name;
 *
 *     &#64;Conf("demo.age")
 *     private Supplier&lt;Integer&gt; age;
 * }
 * </pre>
 * <p>
 * <strong>工作原理：</strong>
 * <ol>
 *   <li>{@code @Autowired} 触发 Spring 的依赖注入机制</li>
 *   <li>{@code @Qualifier} 携带配置键（key），供 {@code DataSupplierRegistrar} 解析</li>
 *   <li>组合注解会被 Spring 透明处理，两个语义都会生效</li>
 * </ol>
 * <p>
 * <strong>注意事项：</strong>
 * <ul>
 *   <li>不能与 {@code @Resource} 混用，因为走的是不同的注入机制</li>
 *   <li>必须配合 {@code DataSupplierRegistrar} 使用，用于动态注册 Supplier Bean</li>
 *   <li>支持字段注入和构造函数参数注入</li>
 * </ul>
 *
 * @author liyifei
 * @see org.springframework.beans.factory.annotation.Autowired
 * @see org.springframework.beans.factory.annotation.Qualifier
 * @since 1.0
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Autowired
@Qualifier
@Indexed
public @interface Conf {

    /**
     * 要注入的配置键。
     */
    @AliasFor(annotation = Qualifier.class, attribute = "value")
    String key();
}