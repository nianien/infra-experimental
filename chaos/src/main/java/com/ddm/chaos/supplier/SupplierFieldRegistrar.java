package com.ddm.chaos.supplier;

import jakarta.annotation.Resource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.ResolvableType;

import java.lang.reflect.Field;
import java.util.function.Supplier;

/**
 * 在属性注入前，扫描当前 bean 的字段并预注册缺失的 Supplier Bean。
 *
 * <p>该后处理器在 {@code CommonAnnotationBeanPostProcessor} 之前执行，扫描所有 bean 的字段：
 * <ul>
 *   <li>若字段类型是 {@code Supplier<T>}，且带 {@code @Resource(name=...)} 或 {@code @Qualifier}</li>
 *   <li>计算配置键（key）和目标类型（T）</li>
 *   <li>若容器缺少对应名字的 bean，则通过 {@link DataSupplierFactory} 生成并注册为单例</li>
 * </ul>
 *
 * <p>这样 {@code @Resource(name=...)} 的按名注入就能成功，因为 Bean 已经预先注册好了。
 *
 * <p><strong>工作原理：</strong>
 * <ol>
 *   <li>在 bean 实例化后、属性注入前，调用 {@code postProcessProperties}</li>
 *   <li>扫描 bean 的所有字段，查找 {@code Supplier<T>} 类型</li>
 *   <li>从 {@code @Qualifier} 或 {@code @Resource(name)} 注解中提取配置键</li>
 *   <li>从字段的泛型参数推断目标类型 T</li>
 *   <li>如果容器中还没有名为该 key 的 Bean，则通过 {@code DataSupplierFactory} 创建并注册</li>
 * </ol>
 *
 * <p><strong>支持的注解：</strong>
 * <ul>
 *   <li>{@code @Resource(name="key")}</li>
 *   <li>{@code @Qualifier("key")}</li>
 * </ul>
 *
 * <p>优先级：{@code @Qualifier} > {@code @Resource}
 *
 * @author liyifei
 * @see InstantiationAwareBeanPostProcessor
 * @see DataSupplierFactory
 * @since 1.0
 */
public class SupplierFieldRegistrar implements InstantiationAwareBeanPostProcessor {

    private final DefaultListableBeanFactory beanFactory;
    private final ObjectProvider<DataSupplierFactory> factoryProvider;

    /**
     * 构造函数。
     *
     * @param beanFactory     Spring Bean 工厂
     * @param factoryProvider DataSupplierFactory 的提供者（延迟获取）
     */
    public SupplierFieldRegistrar(DefaultListableBeanFactory beanFactory,
                                  ObjectProvider<DataSupplierFactory> factoryProvider) {
        this.beanFactory = beanFactory;
        this.factoryProvider = factoryProvider;
    }

    @Override
    public org.springframework.beans.PropertyValues postProcessProperties(
            org.springframework.beans.PropertyValues pvs, Object bean, String beanName) throws BeansException {

        Class<?> clazz = bean.getClass();
        for (Field f : clazz.getDeclaredFields()) {
            if (!Supplier.class.isAssignableFrom(f.getType())) {
                continue;
            }

            // 解析配置键：优先 @Qualifier，其次 @Resource(name)
            String key = resolveKey(f);
            if (key == null || key.isBlank()) {
                continue;
            }

            // 解析泛型参数 T
            ResolvableType rt = ResolvableType.forField(f);
            Class<?> target = rt.getGeneric(0).resolve();
            if (target == null) {
                continue;
            }

            // 约定：@Resource(name=...) 按名字注入 → beanName 必须等于 key
            String supplierBeanName = key;

            // 若容器中尚无该 bean，则注册单例 Supplier<?>（完全交给 Spring 托管）
            if (!beanFactory.containsBean(supplierBeanName)) {
                DataSupplierFactory factory = factoryProvider.getIfAvailable();
                if (factory == null) {
                    throw new IllegalStateException(
                            "DataSupplierFactory not available yet while wiring field: " +
                                    f.getDeclaringClass().getSimpleName() + "." + f.getName());
                }

                @SuppressWarnings({"rawtypes", "unchecked"})
                Supplier<?> supplier = factory.getSupplier(key, (Class) target);

                if (supplier != null) {
                    beanFactory.registerSingleton(supplierBeanName, supplier);
                }
            }
        }

        // 不篡改属性，让后续 @Resource/@Autowired 走正常流程
        return pvs;
    }

    /**
     * 从字段注解中解析配置键。
     *
     * <p>支持的注解：
     * <ul>
     *   <li>{@link Qualifier @Qualifier}：使用 value 属性作为配置键</li>
     *   <li>{@link Resource @Resource}：使用 name 属性作为配置键</li>
     * </ul>
     *
     * <p>优先级：{@code @Qualifier} > {@code @Resource}
     *
     * @param field 字段对象
     * @return 解析得到的配置键，如果无法解析则返回 null
     */
    private static String resolveKey(Field field) {
        // 优先检查 @Qualifier 注解
        Qualifier q = field.getAnnotation(Qualifier.class);
        if (q != null && !q.value().isBlank()) {
            return q.value();
        }

        // 其次检查 @Resource 注解
        Resource r = field.getAnnotation(Resource.class);
        if (r != null && !r.name().isBlank()) {
            return r.name();
        }

        return null;
    }
}

