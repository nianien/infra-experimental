package com.ddm.chaos.web.interceptor;

import com.ddm.chaos.web.dto.ApiResponse;
import com.ddm.chaos.web.dto.User;
import com.ddm.chaos.web.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器，用于验证需要登录的操作。
 * <p>
 * 对于 POST、PUT、DELETE 请求，验证请求头中的 Authorization (Bearer token) 或 X-User。
 * <p>
 * 注意：本系统不维护会话生命周期，实际认证应该由网关/SSO完成。
 * 这里只做基本的用户存在性检查，用于授权和审计。
 * <p>
 * 如果未来接入网关/SSO，可以从请求头（如 X-User、X-User-Id）中直接获取用户信息。
 *
 * @author liyifei
 * @since 1.0
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationInterceptor.class);
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_USER = "X-User";
    private static final String ATTR_CURRENT_USER = "currentUser";
    private static final String ERROR_UNAUTHORIZED = "未登录，请先登录";
    private static final int MIN_TOKEN_LENGTH = 20;

    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AuthenticationInterceptor(UserService userService) {
        this.userService = userService;
        this.objectMapper = new ObjectMapper();
    }

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

        // 提取 token
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            log.warn("Unauthorized request: {} {} - Missing token", method, uri);
            sendUnauthorizedResponse(response);
            return false;
        }

        // 验证 token
        // 实际生产环境中，token 验证应该由网关/SSO完成
        User user = userService.validateToken(token);
        if (user == null) {
            log.warn("Unauthorized request: {} {} - Invalid token or user not found", method, uri);
            sendUnauthorizedResponse(response);
            return false;
        }

        // 将用户信息存储到 request 属性中，供后续使用
        String username = user.username();
        request.setAttribute(ATTR_CURRENT_USER, username);
        log.debug("Authenticated request: {} {} - User: {}", method, uri, username);
        return true;
    }

    /**
     * 从请求头中提取 token。
     * 优先使用 Authorization: Bearer token，如果没有则使用 X-User header（向后兼容）。
     *
     * @param request HTTP 请求
     * @return token，如果不存在返回 null
     */
    private String extractToken(HttpServletRequest request) {
        // 优先使用 Authorization header
        String authHeader = request.getHeader(HEADER_AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // 向后兼容：使用 X-User header（如果它是 token 格式）
        String userHeader = request.getHeader(HEADER_USER);
        if (userHeader != null && userHeader.length() > MIN_TOKEN_LENGTH) {
            // 假设 token 长度大于 MIN_TOKEN_LENGTH，否则可能是用户名
            return userHeader;
        }
        return null;
    }

    /**
     * 发送未授权响应。
     *
     * @param response HTTP 响应
     */
    private void sendUnauthorizedResponse(HttpServletResponse response) {
        try {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            String errorJson = objectMapper.writeValueAsString(ApiResponse.error(ERROR_UNAUTHORIZED));
            response.getWriter().write(errorJson);
        } catch (Exception e) {
            log.error("Failed to send unauthorized response", e);
            try {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"" + ERROR_UNAUTHORIZED + "\"}");
            } catch (Exception ex) {
                log.error("Failed to send fallback unauthorized response", ex);
            }
        }
    }
}

