package com.ddm.chaos.supplier;

import com.ddm.chaos.annotation.Conf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * 支持 @Conf / @Qualifier 的 Supplier<T> 动态注入解析器。
 * - 构造器或字段注入时，若参数类型为 Supplier<T> 且标注 @Conf，
 * 则在依赖解析阶段直接返回 Supplier 实例（不注册、不代理）。
 */
public class DataSupplierResolver extends ContextAnnotationAutowireCandidateResolver {

    private static final Logger log = LoggerFactory.getLogger(DataSupplierResolver.class);
    private final DefaultListableBeanFactory beanFactory;
    private volatile DataSupplierFactory factory;

    public DataSupplierResolver(DefaultListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }


    @Override
    @Nullable
    public Object getSuggestedValue(DependencyDescriptor desc) {
        // 1) 保留父类能力（@Value/SpEL 等）
        Object suggested = super.getSuggestedValue(desc);
        if (suggested != null) return suggested;

        // 2) 仅处理 Supplier<T>
        if (!Supplier.class.isAssignableFrom(desc.getDependencyType())) return null;

        // 3) 解析 key（兼容构造参数/字段）
        String key = resolveKeyFromDescriptor(desc);
        if (key == null || key.isBlank()) return null;

        // 4) 解析泛型 T
        Class<?> targetType = resolveSupplierGeneric(desc);
        if (targetType == null) return null;

        // 5) 拿工厂；若此时尚未可用，则返回惰性 Supplier 以避免时序问题
        DataSupplierFactory f = getFactoryOrNull();
        if (f == null) {
            return (Supplier<?>) () -> {
                DataSupplierFactory late = getFactoryOrNull();
                if (late == null) return null;
                @SuppressWarnings({"rawtypes", "unchecked"})
                Supplier<?> s = late.getSupplier(key, (Class) targetType);
                return (s != null) ? s.get() : null;
            };
        }

        // 6) 工厂可用：直接返回已实例化的 Supplier（首选），否则返回惰性代理
        @SuppressWarnings({"rawtypes", "unchecked"})
        Supplier<?> s = f.getSupplier(key, (Class) targetType);
        if (s != null) return s;

        return (Supplier<?>) () -> {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Supplier<?> real = f.getSupplier(key, (Class) targetType);
            return (real != null) ? real.get() : null;
        };
    }

    /**
     * 从 DependencyDescriptor 解析 @Conf/@Qualifier 的 key；兼容构造参数与字段
     */
    @Nullable
    private static String resolveKeyFromDescriptor(DependencyDescriptor desc) {
        AnnotatedElement element = desc.getAnnotatedElement();
        if (element == null) return null;

        // 构造参数 / 方法参数：从参数注解数组里找
        if (element instanceof Constructor<?> ctor) {
            int idx = desc.getMethodParameter().getParameterIndex();
            if (idx >= 0) {
                for (Annotation ann : ctor.getParameterAnnotations()[idx]) {
                    if (ann instanceof com.ddm.chaos.annotation.Conf c) return c.key();
                    if (ann instanceof org.springframework.beans.factory.annotation.Qualifier q && !q.value().isBlank())
                        return q.value();
                }
            }
            return null;
        }
        if (element instanceof Method m) {
            int idx = desc.getMethodParameter().getParameterIndex();
            if (idx >= 0) {
                for (Annotation ann : m.getParameterAnnotations()[idx]) {
                    if (ann instanceof com.ddm.chaos.annotation.Conf c) return c.key();
                    if (ann instanceof org.springframework.beans.factory.annotation.Qualifier q && !q.value().isBlank())
                        return q.value();
                }
            }
            return null;
        }

        // 字段：直接合并查找
        Conf conf =
                AnnotatedElementUtils.findMergedAnnotation(element, com.ddm.chaos.annotation.Conf.class);
        if (conf != null) return conf.key();

        org.springframework.beans.factory.annotation.Qualifier q =
                AnnotatedElementUtils.findMergedAnnotation(element, org.springframework.beans.factory.annotation.Qualifier.class);
        return (q != null && !q.value().isBlank()) ? q.value() : null;
    }

    /**
     * 解析 Supplier<T> 的 T
     */
    @Nullable
    private static Class<?> resolveSupplierGeneric(DependencyDescriptor desc) {
        ResolvableType rt = desc.getResolvableType();
        return (rt != null) ? rt.getGeneric(0).resolve() : null;
    }

    /**
     * 工厂可为空，避免初始化时序死锁
     */
    @Nullable
    private DataSupplierFactory getFactoryOrNull() {
        try {
            return beanFactory.getBeanProvider(DataSupplierFactory.class).getIfAvailable();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private DataSupplierFactory getFactory() {
        DataSupplierFactory f = factory;
        if (f == null) {
            synchronized (this) {
                f = factory;
                if (f == null) {
                    f = beanFactory.getBean(DataSupplierFactory.class);
                    factory = f;
                }
            }
        }
        return f;
    }
}