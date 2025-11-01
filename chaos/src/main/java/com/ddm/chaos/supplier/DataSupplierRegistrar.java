package com.ddm.chaos.supplier;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
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
public class DataSupplierRegistrar
        implements InstantiationAwareBeanPostProcessor, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(DataSupplierRegistrar.class);

    private final DefaultListableBeanFactory beanFactory;
    private final ObjectProvider<DataSupplierFactory> factoryProvider;

    public DataSupplierRegistrar(DefaultListableBeanFactory beanFactory,
                                 ObjectProvider<DataSupplierFactory> factoryProvider) {
        this.beanFactory = beanFactory;
        this.factoryProvider = factoryProvider;
    }

    /**
     * 确保本处理器优先于 CommonAnnotationBeanPostProcessor 执行
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public PropertyValues postProcessProperties(
            @NonNull PropertyValues pvs,
            @NonNull Object bean,
            @NonNull String beanName) throws BeansException {

        for (Field f : allFields(bean.getClass())) {
            // 只处理 Supplier<T> 字段
            if (!Supplier.class.isAssignableFrom(f.getType())) continue;
            // 忽略 static 字段
            if (Modifier.isStatic(f.getModifiers())) continue;

            // 解析 key：@Qualifier > @Resource
            String key = resolveKey(f);
            if (isBlank(key)) continue;

            // 解析泛型 T
            Class<?> target = ResolvableType.forField(f).getGeneric(0).resolve();
            if (target == null) {
                log.debug("Skip field {}.{}: cannot resolve Supplier<T> generic parameter",
                        f.getDeclaringClass().getSimpleName(), f.getName());
                continue;
            }

            // 约定：按名注入 → beanName == key
            String supplierBeanName = key;

            // 如果已经有同名 Bean，检查类型是否为 Supplier；否则跳过（交给后续流程）
            if (beanFactory.containsBean(supplierBeanName)) {
                Class<?> existingType = beanFactory.getType(supplierBeanName);
                if (existingType != null && !Supplier.class.isAssignableFrom(existingType)) {
                    log.warn("Bean '{}' exists but type {} is not Supplier; field {}.{} may fail to inject",
                            supplierBeanName, existingType.getSimpleName(),
                            f.getDeclaringClass().getSimpleName(), f.getName());
                }
                continue;
            }

            // 懒取工厂（只有确实需要注册时才获取）
            DataSupplierFactory factory = factoryProvider.getIfAvailable();
            if (factory == null) {
                throw new IllegalStateException(
                        "DataSupplierFactory not available while wiring field '%s.%s' with key '%s'"
                                .formatted(f.getDeclaringClass().getSimpleName(), f.getName(), key));
            }

            @SuppressWarnings({"rawtypes", "unchecked"})
            Supplier<?> supplier = factory.getSupplier(key, (Class) target);
            if (supplier == null) {
                log.warn("Factory returned null Supplier for key='{}', type='{}'; skip registering",
                        key, target.getSimpleName());
                continue;
            }

            beanFactory.registerSingleton(supplierBeanName, supplier);
            log.debug("Registered Supplier bean '{}' for key='{}', type='{}'", 
                    supplierBeanName, key, target.getSimpleName());
        }

        // 交回正常注入流程
        return pvs;
    }

    /* -------------------- helpers -------------------- */

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 解析注解上的 key：@Qualifier > @Resource
     */
    private static String resolveKey(Field field) {
        Qualifier q = field.getAnnotation(Qualifier.class);
        if (q != null && !q.value().isBlank()) return q.value();

        Resource r = field.getAnnotation(Resource.class);
        if (r != null && !r.name().isBlank()) return r.name();

        return null;
    }

    /**
     * 递归收集继承链上的所有字段（含父类）
     */
    private static List<Field> allFields(Class<?> type) {
        List<Field> list = new ArrayList<>();
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) list.add(f);
            c = c.getSuperclass();
        }
        return list;
    }
}

