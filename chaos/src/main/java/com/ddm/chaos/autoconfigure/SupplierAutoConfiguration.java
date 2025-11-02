package com.ddm.chaos.autoconfigure;

import com.ddm.chaos.provider.DataProvider;
import com.ddm.chaos.config.DataConfigFactory;
import com.ddm.chaos.config.DataConfigProperties;
import com.ddm.chaos.config.DataConfigResolver;
import com.ddm.chaos.config.DefaultDataConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

@AutoConfiguration
@EnableConfigurationProperties(DataConfigProperties.class)
public class SupplierAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SupplierAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    public DataConfigFactory dataSupplierFactory(DataConfigProperties props) throws Exception {
        DefaultDataConfigFactory factory = new DefaultDataConfigFactory(props);
        return factory;
    }

    @Bean
    public static BeanFactoryPostProcessor registerDataSupplierResolver() {
        return beanFactory -> {
            if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
                log.info("Installing DataSupplierResolver for dynamic @Conf injection");
                dlbf.setAutowireCandidateResolver(new DataConfigResolver(dlbf));
            } else {
                log.warn("BeanFactory is not DefaultListableBeanFactory, cannot install DataSupplierResolver");
            }
        };
    }


}