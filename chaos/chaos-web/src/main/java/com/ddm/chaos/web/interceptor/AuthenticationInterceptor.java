package com.ddm.chaos.web.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器，用于验证需要登录的操作。
 * <p>
 * 对于 POST、PUT、DELETE 请求，验证请求头中的 X-User 是否存在。
 * 如果不存在或为空，返回 401 Unauthorized。
 *
 * @author liyifei
 * @since 1.0
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationInterceptor.class);
    private static final String HEADER_USER = "X-User";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        // 只对修改操作（POST、PUT、DELETE）进行认证检查
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) {
            return true;
        }

        // 排除认证相关的接口
        if (uri.startsWith("/api/auth/")) {
            return true;
        }

        // 检查请求头中的用户信息
        String user = request.getHeader(HEADER_USER);
        if (user == null || user.isBlank()) {
            log.warn("Unauthorized request: {} {} - Missing X-User header", method, uri);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"error\":\"未登录，请先登录\"}");
            return false;
        }

        // 验证用户名的有效性（简单检查：不能是默认值 "admin" 除非是真正的登录用户）
        // 这里可以根据实际需求扩展，比如验证 token、检查用户是否存在等
        log.debug("Authenticated request: {} {} - User: {}", method, uri, user);
        return true;
    }
}

