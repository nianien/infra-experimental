package com.ddm.chaos.config;

import com.ddm.chaos.annotation.Conf;
import com.ddm.chaos.defined.ConfDesc;
import com.ddm.chaos.defined.ConfRef;
import com.ddm.chaos.utils.Converters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.core.ResolvableType;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.function.Supplier;

import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;

/**
 * 配置注入解析器，支持 {@code @Conf} 注解的 Supplier&lt;T&gt; 动态注入。
 * <p>
 * 该解析器扩展了 Spring 的 {@code ContextAnnotationAutowireCandidateResolver}，
 * 用于在 Spring Bean 注入时解析 {@code @Conf} 注解，并创建配置 Supplier。
 *
 * <p><strong>功能特性：</strong>
 * <ul>
 *   <li>仅拦截 {@code Supplier<T>} / {@code Supplier<?>} 类型的注入点</li>
 *   <li>解析 {@code @Conf} 注解，提取配置引用、类型和默认值</li>
 *   <li>从 {@link ConfigFactory} 获取配置 Supplier</li>
 *   <li>支持惰性加载，避免初始化时序问题（factory 未就绪时返回惰性 Supplier）</li>
 *   <li>支持字段注入和参数注入（构造器参数、方法参数）</li>
 * </ul>
 *
 * <p><strong>使用示例：</strong>
 * <pre>{@code
 * @Component
 * public class MyService {
 *     @Conf(namespace = "com.chaos", group = "cfd", key = "timeout", defaultValue = "30s")
 *     private Supplier<Duration> timeout;
 * }
 * }</pre>
 *
 * @author liyifei
 * @see Conf
 * @see ConfigFactory
 * @since 1.0
 */
public class ConfigResolver extends ContextAnnotationAutowireCandidateResolver {

    private static final Logger log = LoggerFactory.getLogger(ConfigResolver.class);

    /**
     * Spring Bean 工厂，用于获取 ConfigFactory 实例
     */
    private final DefaultListableBeanFactory beanFactory;

    /**
     * 构造配置解析器。
     *
     * @param beanFactory Spring Bean 工厂，不能为 null
     */
    public ConfigResolver(DefaultListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    @Nullable
    public Object getSuggestedValue(@NonNull DependencyDescriptor desc) {
        // 1) 保留父类能力（@Value / SpEL）
        Object suggested = super.getSuggestedValue(desc);
        if (suggested != null) return suggested;

        // 2) 仅处理 Supplier<T>
        if (!Supplier.class.isAssignableFrom(desc.getDependencyType())) return null;

        // 3) 解析泛型 T；不可解析则用 Object 作为通配（支持 Supplier<?>）
        Type targetType = resolveSupplierGeneric(desc);
        // 4) 解析 @Conf 注解
        Conf conf = resolveKey(desc);
        if (conf == null) {
            log.trace("No @Conf annotation found on dependency: {}", desc);
            return null;
        }

        // 验证配置引用是否完整
        if (conf.key() == null || conf.key().isBlank()) {
            log.warn("Invalid @Conf annotation: key is empty on {}", desc);
            return null;
        }

        return getLazySupplier(conf, targetType);
    }


    /**
     * 获取惰性 Supplier，延迟获取工厂实例。
     * <p>
     * 使用双重检查锁定模式，确保线程安全且性能优化。
     *
     * @param <T>  目标类型
     * @param conf 配置注解
     * @param type 配置类型
     * @return Supplier 实例
     */
    private <T> Supplier<T> getLazySupplier(Conf conf, Type type) {
        // 统一惰性 Supplier 实现
        ConfRef ref = new ConfRef(conf.namespace(), conf.group(), conf.key());
        Object value = Converters.cast(conf.defaultValue(), type);
        ConfDesc desc = new ConfDesc(ref, value, type);
        return new Supplier<>() {
            private volatile Supplier<T> delegate;

            @Override
            public T get() {
                Supplier<T> d = delegate;
                if (d != null) {
                    return getValueSafely(d, desc);
                }

                synchronized (this) {
                    d = delegate;
                    if (d != null) {
                        return getValueSafely(d, desc);
                    }

                    ConfigFactory factory = getFactory();
                    if (factory == null) {
                        log.warn("ConfigFactory not available, returning default value for {}", desc.ref());
                        return getDefaultValue(desc);
                    }

                    try {
                        Supplier<?> real = factory.createSupplier(desc);
                        if (real == null) {
                            log.warn("ConfigFactory returned null supplier for {}, returning default value", desc.ref());
                            return getDefaultValue(desc);
                        }

                        @SuppressWarnings("unchecked")
                        Supplier<T> typed = (Supplier<T>) real;
                        delegate = typed;
                        return getValueSafely(typed, desc);
                    } catch (Exception e) {
                        log.error("Failed to create supplier for {}, returning default value", desc.ref(), e);
                        return getDefaultValue(desc);
                    }
                }
            }
        };
    }

    /**
     * 安全地获取配置值，如果失败则返回默认值。
     *
     * @param <T>      目标类型
     * @param supplier 配置 Supplier
     * @param desc     配置描述符
     * @return 配置值或默认值
     */
    private <T> T getValueSafely(Supplier<T> supplier, ConfDesc desc) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.error("Failed to get config value for {}, returning default value", desc.ref(), e);
            return getDefaultValue(desc);
        }
    }

    /**
     * 获取默认值。
     *
     * @param <T>  目标类型
     * @param desc 配置描述符
     * @return 默认值
     */
    @SuppressWarnings("unchecked")
    private <T> T getDefaultValue(ConfDesc desc) {
        return (T) desc.defaultValue();
    }

    /**
     * 解析 Supplier<T> 的 T；解析不到返回 null（调用方用 Object 兜底）。
     */
    @Nullable
    private static Type resolveSupplierGeneric(DependencyDescriptor desc) {
        ResolvableType rt = desc.getResolvableType();
        if (rt == null) return null;

        // 确保按 Supplier 视角解析
        ResolvableType asSupplier = rt.as(Supplier.class);
        if (asSupplier == ResolvableType.NONE) return null;

        // 关键：getType() 拿到的是 java.lang.reflect.Type（可能是 ParameterizedType）
        return asSupplier.getGeneric(0).getType();
    }

    /**
     * 统一解析注入点上的 @Conf 注解（参数优先，其次字段）。
     *
     * @param desc 依赖描述符
     * @return Conf 注解实例，如果解析失败返回 null
     */
    @Nullable
    private static Conf resolveKey(DependencyDescriptor desc) {
        // 参数注入（构造器 / 方法参数）
        if (desc.getMethodParameter() != null && desc.getMethodParameter().getParameterIndex() >= 0) {
            Conf conf = resolveOnParameter(desc);
            if (conf != null) {
                return conf;
            }
        }
        // 字段注入
        AnnotatedElement element = desc.getAnnotatedElement();
        return (element != null) ? findMergedAnnotation(element, Conf.class) : null;
    }

    /**
     * 参数注入解析：先读原生矩阵，再走统一的"合并注解"解析（避免重复代码）。
     *
     * @param desc 依赖描述符
     * @return Conf 注解实例，如果解析失败返回 null
     */
    @Nullable
    private static Conf resolveOnParameter(DependencyDescriptor desc) {
        try {
            int idx = desc.getMethodParameter().getParameterIndex();
            Executable exec = desc.getMethodParameter().getExecutable(); // Spring 5.3+/6
            if (exec == null || idx < 0 || idx >= exec.getParameterCount()) {
                return null;
            }
            // 1) 原生注解矩阵（对构造器最稳）
            Annotation[][] matrix = exec.getParameterAnnotations();
            if (idx < matrix.length) {
                Conf conf = resolveFromAnnotations(matrix[idx]);
                if (conf != null) {
                    return conf;
                }
            }
            // 2) 统一“合并注解”解析（包含直挂与元注解）
            Parameter p = exec.getParameters()[idx];
            Conf conf = findMergedAnnotation(p, Conf.class);
            if (conf != null) {
                return conf;
            }
        } catch (Exception e) {
            log.debug("Failed to resolve parameter annotations on {}, will try field injection",
                    desc.getMethodParameter(), e);
        } catch (Error e) {
            // 对于 Error（如 OutOfMemoryError），记录错误并重新抛出
            log.error("Error while resolving parameter annotations on {}",
                    desc.getMethodParameter(), e);
            throw e;
        }
        return null;
    }


    /**
     * 从"原生注解数组"解析 @Conf 注解（不展开元注解）。
     *
     * @param anns 注解数组
     * @return Conf 注解实例，如果解析失败返回 null
     */
    @Nullable
    private static Conf resolveFromAnnotations(Annotation[] anns) {
        if (anns == null || anns.length == 0) return null;
        for (Annotation ann : anns) {
            if (ann instanceof Conf c) {
                return c;
            }
        }
        return null;
    }


    /**
     * 获取配置工厂实例。
     * <p>
     * 工厂可能为空，避免初始化时序死锁。如果获取失败，记录调试日志但不抛出异常。
     *
     * @return 配置工厂实例，如果不可用则返回 null
     */
    @Nullable
    private ConfigFactory getFactory() {
        try {
            return beanFactory.getBeanProvider(ConfigFactory.class).getIfAvailable();
        } catch (Exception e) {
            log.debug("Failed to get ConfigFactory from bean factory, may not be initialized yet", e);
            return null;
        } catch (Error e) {
            // 对于 Error（如 OutOfMemoryError），记录警告但不捕获
            log.error("Error while getting ConfigFactory", e);
            throw e;
        }
    }


}