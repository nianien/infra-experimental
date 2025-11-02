package com.ddm.chaos.config;

import com.ddm.chaos.annotation.Conf;
import com.ddm.chaos.config.DataConfigFactory.TypedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class DataConfigResolver extends ContextAnnotationAutowireCandidateResolver {

    private static final Logger log = LoggerFactory.getLogger(DataConfigResolver.class);
    private final DefaultListableBeanFactory beanFactory;
    private volatile DataConfigFactory factory;

    public DataConfigResolver(DefaultListableBeanFactory beanFactory) {
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

        // 3) 解析泛型 T
        Class<?> targetType = resolveSupplierGeneric(desc);
        if (targetType == null) return null;

        // 5) 解析 key（兼容构造参数/字段）
        TypedKey nv = resolveKey(desc, targetType);
        if (nv == null) return null;


        // 5) 拿工厂；若此时尚未可用，则返回惰性 Supplier 以避免时序问题
        DataConfigFactory f = getFactory();
        if (f == null) {
            return (Supplier<?>) () -> {
                DataConfigFactory late = getFactory();
                if (late == null) return null;
                @SuppressWarnings({"rawtypes", "unchecked"})
                Supplier<?> s = late.getSupplier(nv);
                return (s != null) ? s.get() : null;
            };
        }

        // 6) 工厂可用：直接返回已实例化的 Supplier（首选），否则返回惰性代理
        @SuppressWarnings({"rawtypes", "unchecked"})
        Supplier<?> s = f.getSupplier(nv);
        if (s != null) return s;

        return (Supplier<?>) () -> {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Supplier<?> real = f.getSupplier(nv);
            return (real != null) ? real.get() : null;
        };
    }

    /**
     * 从 DependencyDescriptor 解析 @Conf/@Qualifier 的 key；兼容构造参数与字段
     */
    @Nullable
    private static TypedKey resolveKey(DependencyDescriptor desc, Class type) {
        AnnotatedElement element = desc.getAnnotatedElement();
        if (element == null) return null;

        // 构造参数 / 方法参数：从参数注解数组里找
        if (element instanceof Constructor<?> ctor) {
            int idx = desc.getMethodParameter().getParameterIndex();
            if (idx >= 0) {
                for (Annotation ann : ctor.getParameterAnnotations()[idx]) {
                    if (ann instanceof Conf c) return new TypedKey(c.key(), c.value(), type);
                    if (ann instanceof Qualifier q && !q.value().isBlank())
                        return new TypedKey(q.value(), null, type);
                }
            }
            return null;
        }
        if (element instanceof Method m) {
            int idx = desc.getMethodParameter().getParameterIndex();
            if (idx >= 0) {
                for (Annotation ann : m.getParameterAnnotations()[idx]) {
                    if (ann instanceof Conf c) return new TypedKey(c.key(), c.value(), type);
                    if (ann instanceof Qualifier q && !q.value().isBlank())
                        return new TypedKey(q.value(), null, type);
                }
            }
            return null;
        }

        // 字段：直接合并查找
        Conf c =
                AnnotatedElementUtils.findMergedAnnotation(element, Conf.class);
        if (c != null) return new TypedKey(c.key(), c.value(), type);

        Qualifier q =
                AnnotatedElementUtils.findMergedAnnotation(element, Qualifier.class);
        return (q != null && !q.value().isBlank()) ? new TypedKey(q.value(), null, type) : null;
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
    private DataConfigFactory getFactory() {
        try {
            return beanFactory.getBeanProvider(DataConfigFactory.class).getIfAvailable();
        } catch (Throwable ignore) {
            return null;
        }
    }


}