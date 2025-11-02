package com.ddm.chaos.autoconfigure;

import com.ddm.chaos.provider.DataProvider;
import com.ddm.chaos.supplier.DataSupplierFactory;
import com.ddm.chaos.supplier.DataSupplierProperties;
import com.ddm.chaos.supplier.DataSupplierResolver;
import com.ddm.chaos.supplier.DefaultDataSupplierFactory;
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
@EnableConfigurationProperties(DataSupplierProperties.class)
public class SupplierAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SupplierAutoConfiguration.class);

    @Bean(destroyMethod = "close")
    public DataSupplierFactory dataSupplierFactory(DataSupplierProperties props) throws Exception {
        String fqcn = Objects.requireNonNull(props.provider(), "chaos.supplier.provider must not be null");

        // 加载 Provider
        DataProvider provider = loadDataProvider(fqcn);
        Map<String, String> config = props.config();
        provider.initialize(config);

        DefaultDataSupplierFactory factory = new DefaultDataSupplierFactory(provider);
        factory.setRefreshInterval(props.ttl());
        factory.startRefresh();
        return factory;
    }

    @Bean
    public static BeanFactoryPostProcessor registerDataSupplierResolver() {
        return beanFactory -> {
            if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
                log.info("Installing DataSupplierResolver for dynamic @Conf injection");
                dlbf.setAutowireCandidateResolver(new DataSupplierResolver(dlbf));
            } else {
                log.warn("BeanFactory is not DefaultListableBeanFactory, cannot install DataSupplierResolver");
            }
        };
    }

    private static DataProvider loadDataProvider(String className) {
        ServiceLoader<DataProvider> loader =
                ServiceLoader.load(DataProvider.class, Thread.currentThread().getContextClassLoader());
        for (DataProvider p : loader) {
            if (p.getClass().getName().equals(className)) {
                return p;
            }
        }
        throw new IllegalStateException("No DataProvider found via SPI for: " + className);
    }
}