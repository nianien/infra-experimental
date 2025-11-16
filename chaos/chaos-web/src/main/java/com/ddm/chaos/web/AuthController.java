package com.ddm.chaos.web;

import com.ddm.chaos.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 认证控制器，提供登录/登出等认证相关的 API。
 * <p>
 * 当前为 Mock 实现，用于演示。
 *
 * @author liyifei
 * @since 1.0
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /**
     * Mock 用户数据（仅用于演示）
     */
    private static final Map<String, String> MOCK_USERS = Map.of(
            "admin", "admin123",
            "user", "user123",
            "test", "test123"
    );

    /**
     * 用户登录接口（Mock 实现）。
     * <p>
     * 支持的测试账号：
     * <ul>
     *   <li>admin / admin123</li>
     *   <li>user / user123</li>
     *   <li>test / test123</li>
     * </ul>
     *
     * @param request 登录请求，包含 username 和 password
     * @return 登录结果
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String password = request.get("password");

        log.info("Login attempt: username={}", username);

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("用户名不能为空"));
        }

        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("密码不能为空"));
        }

        // Mock 验证：检查用户名和密码
        String expectedPassword = MOCK_USERS.get(username);
        if (expectedPassword == null || !expectedPassword.equals(password)) {
            log.warn("Login failed: invalid credentials for username={}", username);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("用户名或密码错误"));
        }

        // 登录成功，生成 Mock Token
        String token = "mock-token-" + UUID.randomUUID().toString();
        log.info("Login success: username={}, token={}", username, token);

        Map<String, Object> response = ApiResponse.success("登录成功");
        response.put("username", username);
        response.put("token", token);

        return ResponseEntity.ok(response);
    }

    /**
     * 用户登出接口。
     *
     * @return 登出结果
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        log.info("User logout");
        return ResponseEntity.ok(ApiResponse.success("登出成功"));
    }

    /**
     * 获取当前用户信息。
     *
     * @return 用户信息
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser() {
        // Mock 实现：返回默认用户信息
        Map<String, Object> userInfo = Map.of(
                "username", "admin",
                "roles", new String[]{"admin"}
        );
        return ResponseEntity.ok(userInfo);
    }
}

