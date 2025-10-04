package com.ddm.argus;

import com.ddm.argus.trace.grpc.TraceInterceptor;
import com.ddm.argus.trace.http.TraceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Argus Spring Boot Auto Configuration
 * <p>
 * 自动配置 TraceFilter 和 TraceInterceptor：
 * - TraceFilter: 仅在 Web 环境下加载
 * - TraceInterceptor: 在所有环境下加载
 */
@Configuration
public class ArgusAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ArgusAutoConfiguration.class);

    public ArgusAutoConfiguration() {
        log.info("==>[Argus]: ArgusAutoConfiguration constructor called");
    }

    /**
     * Web 环境下的 HTTP 过滤器配置
     */
    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(TraceFilter.class)
    static class WebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public TraceFilter traceFilter() {
            log.info("==>[Argus]: Auto-configuring TraceFilter for web environment");
            return new TraceFilter();
        }
    }

    /**
     * gRPC 拦截器配置（适用于所有环境）
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceInterceptor traceInterceptor() {
        log.info("==>[Argus]: Auto-configuring TraceInterceptor");
        return new TraceInterceptor();
    }
}
