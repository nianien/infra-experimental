package com.ddm.chaos.web;

import com.ddm.chaos.web.dto.ApiResponse;
import com.ddm.chaos.web.dto.User;
import com.ddm.chaos.web.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器，提供登录/登出等认证相关的 API。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String HEADER_TOKEN = "Authorization";

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

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

        // 验证用户
        User user = userService.authenticate(username, password);
        if (user == null) {
            log.warn("Login failed: invalid credentials for username={}", username);
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("用户名或密码错误"));
        }

        // 生成带签名的 token
        String token = userService.generateToken(user.id(), user.username());

        log.info("Login success: username={}, userId={}, token={}", username, user.id(), token);

        Map<String, Object> response = ApiResponse.success("登录成功");
        response.put("username", user.username());
        response.put("token", token);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(HttpServletRequest request) {
        String token = extractToken(request);
        if (token == null) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("未登录"));
        }

        User user = userService.validateToken(token);
        if (user == null) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("Token 无效或已过期"));
        }

        Map<String, Object> userInfo = ApiResponse.success("获取用户信息成功");
        userInfo.put("id", user.id());
        userInfo.put("username", user.username());
        return ResponseEntity.ok(userInfo);
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@RequestBody Map<String, String> request,
                                                              HttpServletRequest httpRequest) {
        String token = extractToken(httpRequest);
        if (token == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("未登录"));
        }

        User user = userService.validateToken(token);
        if (user == null) {
            return ResponseEntity.status(401).body(ApiResponse.error("Token 无效或已过期"));
        }

        String oldPassword = request.get("oldPassword");
        String newPassword = request.get("newPassword");

        if (oldPassword == null || oldPassword.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("请输入当前密码"));
        }
        if (newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("请输入新密码"));
        }
        if (newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(ApiResponse.error("新密码长度至少 6 位"));
        }
        if (oldPassword.equals(newPassword)) {
            return ResponseEntity.badRequest().body(ApiResponse.error("新密码不能与旧密码相同"));
        }

        boolean updated = userService.changePassword(user.id(), oldPassword, newPassword);
        if (!updated) {
            return ResponseEntity.badRequest().body(ApiResponse.error("密码修改失败，请确认旧密码正确"));
        }

        return ResponseEntity.ok(ApiResponse.success("密码修改成功"));
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HEADER_TOKEN);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // 兼容 X-User header（向后兼容）
        return request.getHeader("X-User");
    }
}