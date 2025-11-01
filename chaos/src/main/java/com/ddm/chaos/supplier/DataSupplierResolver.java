package com.ddm.chaos.supplier;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.ResolvableType;

import java.lang.reflect.AnnotatedElement;
import java.util.function.Supplier;

/**
 * 在依赖注入阶段，拦截 Supplier<T> + @Qualifier/@Resource(name=...)
 * 动态创建并注册同名 Supplier Bean，然后交回 Spring 正常装配流程。
 */
public class DataSupplierResolver extends ContextAnnotationAutowireCandidateResolver {

    private final DefaultListableBeanFactory beanFactory;
    private volatile DataSupplierFactory factory; // 懒加载一次缓存

    public DataSupplierResolver(DefaultListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object getSuggestedValue(DependencyDescriptor desc) {
        Object suggested = super.getSuggestedValue(desc);
        if (suggested != null) return suggested;

        // 仅处理 Supplier<T> 依赖
        if (!Supplier.class.isAssignableFrom(desc.getDependencyType())) return null;

        // 解析 key（@Qualifier / @Resource(name)）
        String key = resolveKey(desc.getAnnotatedElement());
        if (key == null || key.isEmpty()) return null;

        // 已有同名 Bean（可能已注册过），交回常规流程
        if (beanFactory.containsBean(key)) return null;

        // 推断泛型 T
        ResolvableType rt = desc.getResolvableType();
        Class<?> targetType = (rt != null) ? rt.getGeneric(0).resolve() : null;
        if (targetType == null) return null;

        // 懒加载工厂 → 生成 Supplier<T> → 注册为单例
        @SuppressWarnings({"rawtypes", "unchecked"})
        Supplier<?> supplier = getFactory().getSupplier(key, (Class) targetType);
        beanFactory.registerSingleton(key, supplier);

        // 返回 null：让容器按常规流程再去注入刚注册的 Bean
        return null;
    }

    private DataSupplierFactory getFactory() {
        DataSupplierFactory f = factory;
        if (f == null) {
            synchronized (this) {
                f = factory;
                if (f == null) {
                    f = beanFactory.getBeanProvider(DataSupplierFactory.class).getObject();
                    factory = f;
                }
            }
        }
        return f;
    }

    private static String resolveKey(AnnotatedElement element) {
        if (element == null) return null;
        Qualifier q = element.getAnnotation(Qualifier.class);
        if (q != null && !q.value().isEmpty()) return q.value();
        Resource r = element.getAnnotation(Resource.class);
        if (r != null && !r.name().isEmpty()) return r.name();
        return null;
    }
}