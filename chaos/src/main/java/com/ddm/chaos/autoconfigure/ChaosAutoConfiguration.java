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

@AutoConfiguration
@EnableConfigurationProperties(ConfigProperties.class)
public class ChaosAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChaosAutoConfiguration.class);

    @Bean
    public ConfigFactory configFactory(ConfigProperties props) {
        return new DefaultConfigFactory(props);
    }


    @Bean
    public static BeanFactoryPostProcessor registerConfigResolver() {
        return beanFactory -> {
            if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
                log.info("Installing DataSupplierResolver for dynamic @Conf injection");
                dlbf.setAutowireCandidateResolver(new ConfigResolver(dlbf));
            } else {
                log.warn("BeanFactory is not DefaultListableBeanFactory, cannot install DataSupplierResolver");
            }
        };
    }


}