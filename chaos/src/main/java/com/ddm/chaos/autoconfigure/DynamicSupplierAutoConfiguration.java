package com.ddm.chaos.autoconfigure;

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

@AutoConfiguration
@EnableConfigurationProperties(DataSupplierProperties.class)
public class DynamicSupplierAutoConfiguration {

    @Bean(destroyMethod = "close")
    public DataSupplierFactory dataSupplierFactory(DataSupplierProperties props) {
        String fqcn = Objects.requireNonNull(
                props.provider(), "dynamic.supplier.provider-type must not be null");

        // 1. SPI加载 DataProvider
        DataProvider provider = loadProviderByFqcn(fqcn);
        // 2. provider 初始化（用全部配置）
        Map<String, String> config = props.config();
        try {
            provider.initialize(config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize provider: " + fqcn, e);
        }

        // 3. 构造工厂，仅关心刷新周期
        DefaultDataSupplierFactory factory = new DefaultDataSupplierFactory(provider);
        factory.setRefreshInterval(props.ttl());
        factory.startRefresh();
        return factory;
    }

    private static DataProvider loadProviderByFqcn(String fqcn) {
        ServiceLoader<DataProvider> loader =
                ServiceLoader.load(DataProvider.class, Thread.currentThread().getContextClassLoader());
        for (DataProvider p : loader) {
            if (p.getClass().getName().equals(fqcn)) return p;
        }
        throw new IllegalStateException("No DataProvider via SPI for: " + fqcn);
    }


    @Bean
    public static BeanFactoryPostProcessor dynamicSupplierResolverPostProcessor() {
        return (ConfigurableListableBeanFactory bf) -> {
            if (bf instanceof DefaultListableBeanFactory dlbf) {
                dlbf.setAutowireCandidateResolver(new DataSupplierResolver(dlbf));
            }
        };
    }
}