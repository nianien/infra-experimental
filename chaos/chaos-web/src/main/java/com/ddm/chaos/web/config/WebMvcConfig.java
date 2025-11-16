package com.ddm.chaos.web.config;

import com.ddm.chaos.web.interceptor.AuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类，用于注册拦截器。
 *
 * @author liyifei
 * @since 1.0
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthenticationInterceptor authenticationInterceptor;

    public WebMvcConfig(AuthenticationInterceptor authenticationInterceptor) {
        this.authenticationInterceptor = authenticationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/**")  // 拦截所有 /api 路径
                .excludePathPatterns("/api/auth/**");  // 排除认证接口（拦截器内部已通过 HTTP 方法过滤 GET 请求）
    }
}

