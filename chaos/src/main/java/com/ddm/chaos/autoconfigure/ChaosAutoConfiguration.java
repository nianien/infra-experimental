package com.ddm.chaos.autoconfigure;

import com.ddm.chaos.config.ConfigFactory;
import com.ddm.chaos.config.ConfigProperties;
import com.ddm.chaos.config.ConfigResolver;
import com.ddm.chaos.config.DefaultConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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

    /**
     * 创建配置工厂 Bean。
     *
     * @param props 配置属性
     * @return ConfigFactory 实例
     */
    @Bean
    public ConfigFactory configFactory(ConfigProperties props) {
        return new DefaultConfigFactory(props);
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
                log.info("register ConfigResolver for dynamic @Conf injection");
                dlbf.setAutowireCandidateResolver(new ConfigResolver(dlbf));
            } else {
                log.warn("BeanFactory is not DefaultListableBeanFactory, cannot register ConfigResolver");
            }
        };
    }


}