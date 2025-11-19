package com.ddm.chaos.autoconfigure;

import com.ddm.chaos.factory.ConfigFactory;
import com.ddm.chaos.factory.ConfigProperties;
import com.ddm.chaos.factory.DefaultConfigFactory;
import com.ddm.chaos.proto.ConfigServiceGrpc.ConfigServiceBlockingStub;
import com.ddm.chaos.provider.DataProvider;
import com.ddm.chaos.provider.GrpcDataProvider;
import com.ddm.chaos.provider.JdbcDataProvider;
import com.ddm.chaos.resolver.ConfigResolver;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Chaos 配置中心的自动配置类。
 * <p>自动配置以下组件：
 * <ul>
 *   <li>{@link ConfigFactory}：配置工厂 Bean</li>
 *   <li>{@link ConfigResolver}：配置解析器，支持 {@code @Conf} 注解注入</li>
 * </ul>
 *
 * @author liyifei
 * @see ConfigFactory
 * @see ConfigResolver
 * @since 1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(ConfigProperties.class)
public class ChaosAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChaosAutoConfiguration.class);


    static class ConfigService {
        @GrpcClient("chaos-service")
        private ConfigServiceBlockingStub stub;
    }


    @ConditionalOnProperty(
            prefix = "grpc.client.chaos-service",
            name = "address"
    )
    @Bean
    public ConfigService configService() {
        return new ConfigService();
    }


    @ConditionalOnBean(name = "configService")
    @Bean
    public DataProvider grpcDataProvider(ConfigService configService) {
        return new GrpcDataProvider(configService.stub);
    }


    @Bean
    @ConditionalOnProperty(
            prefix = "spring.datasource.chaos",
            name = "url"
    )
    @ConfigurationProperties("spring.datasource.chaos")
    public DataSourceProperties chaosDataSourceProperties() {
        return new DataSourceProperties();
    }


    @Bean
    @ConditionalOnBean(name = "chaosDataSourceProperties")
    public DataSource chaosDataSource(@Qualifier("chaosDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    @ConditionalOnBean(name = "chaosDataSource")
    @Bean
    @Primary
    public DataProvider jdbcDataProvider(@Qualifier("chaosDataSource") DataSource ds) {
        return new JdbcDataProvider(ds);
    }


    /**
     * 创建配置工厂 Bean。
     *
     * @param props 配置属性
     * @return ConfigFactory 实例
     */
    @Bean
    public ConfigFactory configFactory(DataProvider provider, ConfigProperties props) {
        return new DefaultConfigFactory(provider, props);
    }

    /**
     * 注册配置解析器，支持 {@code @Conf} 注解的动态注入。
     * <p>通过 BeanFactoryPostProcessor 在 Bean 工厂初始化后注册自定义的依赖解析器。
     *
     * @return BeanFactoryPostProcessor 实例
     */
    @Bean
    public static BeanFactoryPostProcessor registerConfigResolver() {
        return beanFactory -> {
            if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
                log.info("Registering ConfigResolver for dynamic @Conf injection");
                dlbf.setAutowireCandidateResolver(new ConfigResolver(dlbf));
            } else {
                log.warn("Skip ConfigResolver registration: BeanFactory is not DefaultListableBeanFactory (actual: {})",
                        beanFactory.getClass().getName());
            }
        };
    }


}