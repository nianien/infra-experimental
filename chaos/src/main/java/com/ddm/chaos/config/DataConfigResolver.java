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
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.util.function.Supplier;

/**
 * 支持 @Conf / @Qualifier 的 Supplier<T> 动态注入解析器。
 * - 仅拦截 Supplier<T> / Supplier<?> 注入点；
 * - 解析 @Conf/@Qualifier → TypedKey，从 DataConfigFactory 获取 Supplier；
 * - factory 未就绪时返回惰性 Supplier，避免初始化时序问题；
 * - 不做类型转换与运行时类型校验：存啥取啥（Supplier<?> 语义）。
 */
public class DataConfigResolver extends ContextAnnotationAutowireCandidateResolver {

    private static final Logger log = LoggerFactory.getLogger(DataConfigResolver.class);

    private final DefaultListableBeanFactory beanFactory;

    public DataConfigResolver(DefaultListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    @Nullable
    public Object getSuggestedValue(DependencyDescriptor desc) {
        // 1) 保留父类能力（@Value / SpEL）
        Object suggested = super.getSuggestedValue(desc);
        if (suggested != null) return suggested;

        // 2) 仅处理 Supplier<T>
        if (!Supplier.class.isAssignableFrom(desc.getDependencyType())) return null;

        // 3) 解析泛型 T；不可解析则用 Object 作为通配（支持 Supplier<?>）
        Class<?> targetType = resolveSupplierGeneric(desc);

        // 4) 解析 @Conf/@Qualifier → TypedKey（参数/字段通用）
        TypedKey key = resolveKey(desc, targetType);
        if (key == null) return null;

        // 5) 取工厂；未就绪则返回惰性 Supplier
        DataConfigFactory factory = getFactory();
        if (factory == null) {
            return (Supplier<?>) () -> {
                DataConfigFactory late = getFactory();
                if (late == null) return null;
                Supplier<?> s = late.getSupplier(key);
                return (s != null) ? s.get() : null;
            };
        }

        // 6) 工厂可用：优先返回真实 Supplier，否则返回惰性代理
        Supplier<?> s = factory.getSupplier(key);
        if (s != null) return (Supplier<?>) s::get;

        return (Supplier<?>) () -> {
            Supplier<?> real = factory.getSupplier(key);
            return (real != null) ? real.get() : null;
        };
    }


    /**
     * 解析 Supplier<T> 的 T；解析不到返回 null（调用方用 Object 兜底）。
     */
    @Nullable
    private static Class<?> resolveSupplierGeneric(DependencyDescriptor desc) {
        ResolvableType rt = desc.getResolvableType();
        return (rt != null) ? rt.getGeneric(0).resolve() : null;
    }

    /**
     * 统一解析注入点上的 @Conf/@Qualifier（参数优先，其次字段）。
     */
    @Nullable
    private static TypedKey resolveKey(DependencyDescriptor desc, Class<?> targetType) {
        // —— 参数注入路径（构造器 / 方法参数）
        if (desc.getMethodParameter() != null && desc.getMethodParameter().getParameterIndex() >= 0) {
            TypedKey tk = resolveOnParameter(desc, targetType);
            if (tk != null) return tk;
        }
        // —— 字段注入路径
        AnnotatedElement element = desc.getAnnotatedElement();
        return (element != null) ? resolveMergedOn(element, targetType) : null;
    }

    /**
     * 参数注入解析：先读原生矩阵，再走统一的“合并注解”解析（避免重复代码）。
     */
    @Nullable
    private static TypedKey resolveOnParameter(DependencyDescriptor desc, Class<?> targetType) {
        try {
            int idx = desc.getMethodParameter().getParameterIndex();
            Executable exec = desc.getMethodParameter().getExecutable(); // Spring 5.3+/6
            if (exec == null || idx < 0 || idx >= exec.getParameterCount()) return null;

            // 1) 原生注解矩阵（对构造器最稳）
            Annotation[][] matrix = exec.getParameterAnnotations();
            if (idx < matrix.length) {
                TypedKey tk = resolveFromAnnotations(matrix[idx], targetType);
                if (tk != null) return tk;
            }

            // 2) 统一“合并注解”解析（包含直挂与元注解）
            Parameter p = exec.getParameters()[idx];
            return resolveMergedOn(p, targetType);
        } catch (Throwable e) {
            if (log.isDebugEnabled()) {
                log.debug("Failed to resolve parameter annotations on {}", desc.getMethodParameter(), e);
            }
            return null;
        }
    }

    /**
     * 在任意 AnnotatedElement 上做“合并注解”解析（支持元注解）。
     */
    @Nullable
    private static TypedKey resolveMergedOn(AnnotatedElement element, Class<?> targetType) {
        Conf c = AnnotatedElementUtils.findMergedAnnotation(element, Conf.class);
        if (c != null) return new TypedKey(c.key(), c.defaultValue(), targetType);

        Qualifier q = AnnotatedElementUtils.findMergedAnnotation(element, Qualifier.class);
        if (q != null && notBlank(q.value())) {
            return new TypedKey(q.value(), null, targetType);
        }
        return null;
    }

    /**
     * 从“原生注解数组”解析 @Conf/@Qualifier（不展开元注解）。
     */
    @Nullable
    private static TypedKey resolveFromAnnotations(Annotation[] anns, Class<?> targetType) {
        if (anns == null || anns.length == 0) return null;
        for (Annotation ann : anns) {
            if (ann instanceof Conf c) {
                return new TypedKey(c.key(), c.defaultValue(), targetType);
            }
            if (ann instanceof Qualifier q && notBlank(q.value())) {
                return new TypedKey(q.value(), null, targetType);
            }
        }
        return null;
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 工厂可为空，避免初始化时序死锁。
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