package com.ddm.chaos.web.service;

import com.ddm.chaos.web.dto.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 用户服务，提供用户认证和基本信息查询。
 * <p>
 * 注意：本系统不维护会话生命周期，会话由网关/SSO统一管理。
 * 本服务仅负责用户身份验证和基本信息查询，用于授权和审计。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    
    // Token 密钥（生产环境应该从配置文件读取）
    private static final String TOKEN_SECRET = "change-me-to-a-very-strong-random-secret-in-production";
    // Token 有效期：8小时
    private static final long TOKEN_TTL_MILLIS = 8 * 60 * 60 * 1000L;

    private final JdbcTemplate jdbcTemplate;

    public UserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 用户 RowMapper。
     */
    private RowMapper<User> userRowMapper() {
        return (rs, rowNum) -> new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password"),
                rs.getInt("status")
        );
    }

    /**
     * 验证用户登录。
     *
     * @param username 用户名
     * @param password 密码（明文）
     * @return 用户信息（password 已置为 null），如果验证失败返回 null
     */
    public User authenticate(String username, String password) {
        try {
            String sql = "SELECT id, username, password, status FROM sys_user WHERE username = ?";
            User user = jdbcTemplate.queryForObject(sql, userRowMapper(), username);

            if (user == null) {
                log.warn("User login failed: user not found, username={}", username);
                return null;
            }

            // 检查用户状态
            if (!user.isEnabled()) {
                log.warn("User login failed: user is disabled, username={}", username);
                return null;
            }

            // 验证密码
            String storedPassword = user.password();
            String hashedPassword = hashPassword(password);
            if (!hashedPassword.equals(storedPassword)) {
                log.warn("User login failed: invalid password, username={}", username);
                return null;
            }

            // 返回“脱敏”用户（不暴露密码）
            User safeUser = new User(
                    user.id(),
                    user.username(),
                    null,
                    user.status()
            );

            log.info("User authenticated successfully: username={}", username);
            return safeUser;
        } catch (EmptyResultDataAccessException e) {
            log.warn("User login failed: user not found, username={}", username);
            return null;
        } catch (Exception e) {
            log.error("Error authenticating user: username={}", username, e);
            return null;
        }
    }

    /**
     * 生成带签名的 token，包含 userId / username / 过期时间。
     * <p>
     * 格式（Base64Url 编码后的字符串）：
     *   base64Url( userId:username:expiresAt:signature )
     * 其中 signature = HMAC_SHA256("userId:username:expiresAt", SECRET)
     *
     * @param userId 用户 ID
     * @param username 用户名
     * @return 签名的 token
     */
    public String generateToken(Long userId, String username) {
        long expiresAt = System.currentTimeMillis() + TOKEN_TTL_MILLIS;
        String payload = userId + ":" + username + ":" + expiresAt;
        String signature = hmacSha256(payload, TOKEN_SECRET);
        String tokenRaw = payload + ":" + signature;

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(tokenRaw.getBytes(StandardCharsets.UTF_8));

        // 不要打完整 token，截断一点就行
        log.debug("Token generated: userId={}, username={}, token.prefix={}",
                userId, username, token.substring(0, Math.min(12, token.length())));
        return token;
    }

    /**
     * 验证 token：
     * 1. Base64Url 解码
     * 2. 拆成 4 段：userId:username:expiresAt:signature
     * 3. 验证签名
     * 4. 检查是否过期
     * 5. 检查用户是否存在且启用
     *
     * @param token token 字符串
     * @return 用户信息，如果 token 无效返回 null
     */
    public User validateToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            // Base64Url 解码
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            String tokenRaw = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = tokenRaw.split(":");
            if (parts.length != 4) {
                log.warn("Invalid token format: expected 4 parts, got {}", parts.length);
                return null;
            }

            Long userId = Long.valueOf(parts[0]);
            String username = parts[1];
            long expiresAt = Long.parseLong(parts[2]);
            String signature = parts[3];

            // 过期检查
            if (System.currentTimeMillis() > expiresAt) {
                log.info("Token expired: username={}, expiresAt={}", username, expiresAt);
                return null;
            }

            // 签名验证
            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            String expectedSig = hmacSha256(payload, TOKEN_SECRET);
            if (!constantTimeEquals(expectedSig, signature)) {
                log.warn("Token signature mismatch: username={}", username);
                return null;
            }

            // 查询用户，确保仍然有效
            User user = getUserById(userId);
            if (user == null || !user.isEnabled()) {
                log.warn("Token user not found or disabled: userId={}", userId);
                return null;
            }

            // 返回一个不带密码的副本
            return new User(
                    user.id(),
                    user.username(),
                    null,
                    user.status()
            );
        } catch (Exception e) {
            log.warn("Failed to validate token", e);
            return null;
        }
    }

    /**
     * 密码加密（使用 SHA-256）。
     * 生产环境建议使用 BCrypt 等。
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /**
     * 根据用户 ID 获取用户信息（含密码，用于内部使用）。
     */
    private User getUserById(Long id) {
        try {
            String sql = "SELECT id, username, password, status FROM sys_user WHERE id = ?";
            return jdbcTemplate.queryForObject(sql, userRowMapper(), id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 根据用户名获取用户信息（含密码，用于内部使用）。
     */
    public User getUserByUsername(String username) {
        try {
            String sql = "SELECT id, username, password, status FROM sys_user WHERE username = ?";
            return jdbcTemplate.queryForObject(sql, userRowMapper(), username);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 修改密码。
     *
     * @param userId 用户 ID
     * @param oldPassword 旧密码（明文）
     * @param newPassword 新密码（明文）
     * @return 是否修改成功
     */
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        if (userId == null) {
            return false;
        }
        User user = getUserById(userId);
        if (user == null || !user.isEnabled()) {
            log.warn("Change password failed: user not found or disabled, userId={}", userId);
            return false;
        }

        String oldHash = hashPassword(oldPassword);
        if (!oldHash.equals(user.password())) {
            log.warn("Change password failed: old password mismatch, userId={}", userId);
            return false;
        }

        String newHash = hashPassword(newPassword);
        if (newHash.equals(user.password())) {
            log.warn("Change password skipped: new password equals old, userId={}", userId);
            return false;
        }

        int updated = jdbcTemplate.update("UPDATE sys_user SET password = ? WHERE id = ?", newHash, userId);
        boolean success = updated > 0;
        if (success) {
            log.info("Password changed successfully for userId={}", userId);
        } else {
            log.error("Password change update returned 0 rows, userId={}", userId);
        }
        return success;
    }

    /**
     * HMAC-SHA256 签名。
     *
     * @param data 要签名的数据
     * @param secret 密钥
     * @return 签名的十六进制字符串
     */
    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(result.length * 2);
            for (byte b : result) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate HMAC-SHA256", e);
        }
    }

    /**
     * 常量时间字符串比较，避免时间侧信道攻击。
     *
     * @param a 字符串 a
     * @param b 字符串 b
     * @return 是否相等
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}