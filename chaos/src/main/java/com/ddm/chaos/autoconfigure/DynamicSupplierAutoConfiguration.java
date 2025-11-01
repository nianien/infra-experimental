package com.ddm.chaos.autoconfigure;

import com.ddm.chaos.provider.DataProvider;
import com.ddm.chaos.supplier.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * 动态 Supplier 的 Spring Boot 自动配置类。
 * 
 * <p>该配置类负责：
 * <ol>
 *   <li>创建并配置 {@link DataSupplierFactory} Bean</li>
 *   <li>通过 SPI 机制加载指定的 {@link DataProvider} 实现</li>
 *   <li>初始化 DataProvider 并启动刷新任务</li>
 *   <li>注册 {@link DataSupplierResolver} 用于动态注入 Supplier</li>
 * </ol>
 * 
 * <p><strong>配置要求：</strong>
 * <ul>
 *   <li>需要在配置文件中配置 {@code chaos.supplier.*} 属性</li>
 *   <li>DataProvider 实现类必须通过 SPI 机制注册</li>
 *   <li>必须创建 META-INF/services/com.ddm.chaos.provider.DataProvider 文件</li>
 * </ul>
 * 
 * <p><strong>自动配置的 Bean：</strong>
 * <ul>
 *   <li>{@link DataSupplierFactory}：配置数据的 Supplier 工厂，支持定时刷新</li>
 *   <li>{@link BeanFactoryPostProcessor}：用于注册动态 Supplier 解析器</li>
 * </ul>
 * 
 * @author liyifei
 * @since 1.0
 * @see DataSupplierProperties
 * @see DataSupplierFactory
 * @see DataSupplierResolver
 */
@AutoConfiguration
@EnableConfigurationProperties(DataSupplierProperties.class)
public class DynamicSupplierAutoConfiguration {

    /**
     * 创建 DataSupplierFactory Bean。
     * 
     * <p>该方法执行以下步骤：
     * <ol>
     *   <li>从配置中获取 DataProvider 的全限定名</li>
     *   <li>通过 SPI 机制加载对应的 DataProvider 实现</li>
     *   <li>使用配置参数初始化 DataProvider</li>
     *   <li>创建 DefaultDataSupplierFactory 并设置刷新间隔</li>
     *   <li>启动自动刷新任务</li>
     * </ol>
     * 
     * <p><strong>Bean 销毁：</strong>
     * 使用 {@code destroyMethod = "close"} 确保在 Spring 容器关闭时正确释放资源。
     * 
     * @param props 配置属性，包含 ttl、provider、config 等信息
     * @return 已初始化的 DataSupplierFactory 实例
     * @throws IllegalStateException 如果 provider 配置为空或加载失败
     */
    @Bean(destroyMethod = "close")
    public DataSupplierFactory dataSupplierFactory(DataSupplierProperties props) {
        String fqcn = Objects.requireNonNull(
                props.provider(), "chaos.supplier.provider must not be null");

        // 1. 通过 SPI 加载 DataProvider
        DataProvider provider = loadProviderByFqcn(fqcn);
        
        // 2. 使用配置参数初始化 DataProvider
        Map<String, String> config = props.config();
        try {
            provider.initialize(config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize provider: " + fqcn, e);
        }

        // 3. 构造工厂，设置刷新周期并启动刷新任务
        DefaultDataSupplierFactory factory = new DefaultDataSupplierFactory(provider);
        factory.setRefreshInterval(props.ttl());
        factory.startRefresh();
        return factory;
    }

    /**
     * 通过全限定名（FQCN）加载 DataProvider 实现。
     * 
     * <p>该方法使用 Java SPI（Service Provider Interface）机制加载 DataProvider：
     * <ol>
     *   <li>使用 {@link ServiceLoader} 加载所有已注册的 DataProvider 实现</li>
     *   <li>遍历加载的实现，查找类名匹配的实例</li>
     *   <li>返回匹配的 DataProvider 实例</li>
     * </ol>
     * 
     * <p><strong>SPI 注册要求：</strong>
     * 必须在 {@code META-INF/services/com.ddm.chaos.provider.DataProvider} 文件中
     * 注册实现类的全限定名，例如：
     * <pre>
     * com.ddm.chaos.provider.jdbc.JdbcDataProvider
     * </pre>
     * 
     * @param fqcn DataProvider 实现类的全限定名（Fully Qualified Class Name）
     * @return 匹配的 DataProvider 实例
     * @throws IllegalStateException 如果找不到匹配的 DataProvider 实现
     */
    private static DataProvider loadProviderByFqcn(String fqcn) {
        ServiceLoader<DataProvider> loader =
                ServiceLoader.load(DataProvider.class, Thread.currentThread().getContextClassLoader());
        for (DataProvider p : loader) {
            if (p.getClass().getName().equals(fqcn)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "No DataProvider found via SPI for: " + fqcn + 
                ". Please ensure the class is registered in META-INF/services/com.ddm.chaos.provider.DataProvider");
    }

    /**
     * 创建 BeanFactoryPostProcessor，用于注册动态 Supplier 解析器。
     * 
     * <p>该方法注册 {@link DataSupplierResolver}，使其能够：
     * <ul>
     *   <li>在依赖注入阶段拦截 {@code Supplier<T>} 类型的依赖</li>
     *   <li>根据 {@code @Qualifier} 或 {@code @Resource(name=...)} 注解解析配置键</li>
     *   <li>动态创建并注册 Supplier Bean</li>
     * </ul>
     * 
     * <p><strong>使用示例：</strong>
     * <pre>{@code
     * @Component
     * public class MyService {
     *     @Resource(name = "app.name")
     *     private Supplier<String> appName;
     *     
     *     public void useConfig() {
     *         String name = appName.get();
     *     }
     * }
     * }</pre>
     * 
     * @return BeanFactoryPostProcessor 实例，用于注册动态解析器
     */
    @Bean
    public static BeanFactoryPostProcessor dynamicSupplierResolverPostProcessor() {
        return (ConfigurableListableBeanFactory bf) -> {
            if (bf instanceof DefaultListableBeanFactory dlbf) {
                dlbf.setAutowireCandidateResolver(new DataSupplierResolver(dlbf));
            }
        };
    }
}
