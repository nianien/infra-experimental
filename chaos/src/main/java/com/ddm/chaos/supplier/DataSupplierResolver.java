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
 * 动态 Supplier 依赖解析器。
 * 
 * <p>该解析器在 Spring 依赖注入阶段工作，能够：
 * <ul>
 *   <li>拦截 {@code Supplier<T>} 类型的依赖注入</li>
 *   <li>从 {@code @Qualifier} 或 {@code @Resource(name=...)} 注解中提取配置键</li>
 *   <li>动态创建对应的 Supplier Bean 并注册到 Spring 容器</li>
 *   <li>支持类型推断，自动将配置值转换为目标类型</li>
 * </ul>
 * 
 * <p><strong>使用示例：</strong>
 * <pre>{@code
 * @Component
 * public class MyService {
 *     // 方式 1：使用 @Qualifier 指定配置键
 *     @Autowired
 *     @Qualifier("app.name")
 *     private Supplier<String> appName;
 *     
 *     // 方式 2：使用 @Resource 指定配置键
 *     @Resource(name = "app.port")
 *     private Supplier<Integer> appPort;
 *     
 *     // 方式 3：支持自定义 POJO
 *     @Resource(name = "app.config")
 *     private Supplier<AppConfig> appConfig;
 *     
 *     public void useConfig() {
 *         String name = appName.get();      // 获取 String 类型配置
 *         Integer port = appPort.get();     // 获取 Integer 类型配置
 *         AppConfig config = appConfig.get(); // 获取 POJO 类型配置
 *     }
 * }
 * }</pre>
 * 
 * <p><strong>工作原理：</strong>
 * <ol>
 *   <li>Spring 容器在依赖注入时调用 {@link #getSuggestedValue(DependencyDescriptor)}</li>
 *   <li>检查依赖类型是否为 {@code Supplier<T>}</li>
 *   <li>从注解中提取配置键（key）</li>
 *   <li>从泛型参数推断目标类型（T）</li>
 *   <li>通过 {@link DataSupplierFactory} 获取对应的 Supplier</li>
 *   <li>将 Supplier 注册为单例 Bean</li>
 *   <li>返回 null，让 Spring 按常规流程注入已注册的 Bean</li>
 * </ol>
 * 
 * @author liyifei
 * @since 1.0
 * @see ContextAnnotationAutowireCandidateResolver
 * @see DataSupplierFactory
 */
public class DataSupplierResolver extends ContextAnnotationAutowireCandidateResolver {

    /**
     * Spring Bean 工厂，用于注册动态创建的 Supplier Bean。
     */
    private final DefaultListableBeanFactory beanFactory;
    
    /**
     * DataSupplierFactory 的懒加载缓存（使用 volatile 保证可见性）。
     */
    private volatile DataSupplierFactory factory;

    /**
     * 构造函数。
     * 
     * @param beanFactory Spring Bean 工厂实例
     */
    public DataSupplierResolver(DefaultListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * 获取建议的依赖值。
     * 
     * <p>该方法在 Spring 依赖注入阶段被调用，用于：
     * <ol>
     *   <li>检查是否为 {@code Supplier<T>} 类型的依赖</li>
     *   <li>从注解中解析配置键</li>
     *   <li>推断目标类型并动态创建 Supplier Bean</li>
     * </ol>
     * 
     * <p>如果依赖不是 Supplier 类型或无法解析，返回 null，让 Spring 使用默认的注入策略。
     * 
     * @param desc 依赖描述符，包含依赖的类型、注解等信息
     * @return 如果成功创建 Supplier Bean，返回 null（让 Spring 注入已注册的 Bean）
     *         如果不处理该依赖，返回 null
     */
    @Override
    public Object getSuggestedValue(DependencyDescriptor desc) {
        // 先调用父类方法，处理其他类型的依赖
        Object suggested = super.getSuggestedValue(desc);
        if (suggested != null) {
            return suggested;
        }

        // 仅处理 Supplier<T> 类型的依赖
        if (!Supplier.class.isAssignableFrom(desc.getDependencyType())) {
            return null;
        }

        // 从注解中解析配置键（@Qualifier 或 @Resource(name)）
        String key = resolveKey(desc.getAnnotatedElement());
        if (key == null || key.isEmpty()) {
            return null;
        }

        // 如果已存在同名 Bean，交回常规流程处理
        if (beanFactory.containsBean(key)) {
            return null;
        }

        // 从泛型参数推断目标类型 T
        ResolvableType rt = desc.getResolvableType();
        Class<?> targetType = (rt != null) ? rt.getGeneric(0).resolve() : null;
        if (targetType == null) {
            return null;
        }

        // 懒加载工厂 → 生成 Supplier<T> → 注册为单例 Bean
        @SuppressWarnings({"rawtypes", "unchecked"})
        Supplier<?> supplier = getFactory().getSupplier(key, (Class) targetType);
        beanFactory.registerSingleton(key, supplier);

        // 返回 null：让容器按常规流程再去注入刚注册的 Bean
        return null;
    }

    /**
     * 获取 DataSupplierFactory 实例（懒加载单例模式）。
     * 
     * <p>使用双重检查锁定（Double-Checked Locking）模式，确保线程安全：
     * <ol>
     *   <li>第一次检查：如果 factory 已存在，直接返回</li>
     *   <li>同步块：加锁防止并发创建</li>
     *   <li>第二次检查：再次确认 factory 是否已存在</li>
     *   <li>创建实例：从 Spring 容器中获取 DataSupplierFactory Bean</li>
     * </ol>
     * 
     * @return DataSupplierFactory 实例
     */
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

    /**
     * 从注解元素中解析配置键。
     * 
     * <p>支持以下注解：
     * <ul>
     *   <li>{@link Qualifier @Qualifier}：使用 value 属性作为配置键</li>
     *   <li>{@link Resource @Resource}：使用 name 属性作为配置键</li>
     * </ul>
     * 
     * <p>优先级：{@code @Qualifier} > {@code @Resource}
     * 
     * @param element 注解元素（字段、参数等）
     * @return 解析得到的配置键，如果无法解析则返回 null
     */
    private static String resolveKey(AnnotatedElement element) {
        if (element == null) {
            return null;
        }
        
        // 优先检查 @Qualifier 注解
        Qualifier q = element.getAnnotation(Qualifier.class);
        if (q != null && !q.value().isEmpty()) {
            return q.value();
        }
        
        // 其次检查 @Resource 注解
        Resource r = element.getAnnotation(Resource.class);
        if (r != null && !r.name().isEmpty()) {
            return r.name();
        }
        
        return null;
    }
}
