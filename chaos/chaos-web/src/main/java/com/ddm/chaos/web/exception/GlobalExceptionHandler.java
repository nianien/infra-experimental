package com.ddm.chaos.web.exception;

import com.ddm.chaos.web.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理器。
 *
 * @author liyifei
 * @since 1.0
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Invalid argument: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException e) {
        log.warn("Invalid state: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMessage(), e);
        String userMessage = parseDatabaseError(e);
        return ResponseEntity.badRequest().body(ApiResponse.error(userMessage));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccessException(DataAccessException e) {
        log.warn("Data access exception: {}", e.getMessage(), e);
        // 检查是否是 DataIntegrityViolationException 的子类
        if (e instanceof DataIntegrityViolationException) {
            String userMessage = parseDatabaseError((DataIntegrityViolationException) e);
            return ResponseEntity.badRequest().body(ApiResponse.error(userMessage));
        }
        // 尝试从消息中解析数据库错误
        String userMessage = parseDatabaseErrorFromMessage(e.getMessage());
        if (userMessage != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(userMessage));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("数据操作失败，请稍后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        // 尝试从消息中解析数据库错误（可能被包装在其他异常中）
        String userMessage = parseDatabaseErrorFromMessage(e.getMessage());
        if (userMessage != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(userMessage));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("操作失败，请稍后重试"));
    }

    /**
     * 从异常消息中解析数据库错误（用于处理被包装的异常）。
     *
     * @param errorMessage 异常消息
     * @return 用户友好的错误消息，如果无法解析则返回 null
     */
    private String parseDatabaseErrorFromMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return null;
        }
        return parseDatabaseErrorMessage(errorMessage);
    }

    /**
     * 解析数据库错误信息，转换为用户友好的错误消息。
     *
     * @param e 数据库完整性约束异常
     * @return 用户友好的错误消息
     */
    private String parseDatabaseError(DataIntegrityViolationException e) {
        String errorMessage = e.getMessage();
        if (errorMessage == null) {
            // 尝试从 cause 中获取消息
            Throwable cause = e.getCause();
            if (cause != null) {
                errorMessage = cause.getMessage();
            }
            if (errorMessage == null) {
                return "数据操作失败，请检查输入数据";
            }
        }
        return parseDatabaseErrorMessage(errorMessage);
    }

    /**
     * 解析数据库错误消息的核心逻辑。
     *
     * @param errorMessage 错误消息
     * @return 用户友好的错误消息
     */
    private String parseDatabaseErrorMessage(String errorMessage) {

        // 处理唯一键冲突
        if (errorMessage.contains("Duplicate entry")) {
            // 提取冲突的键名（注意：MySQL 错误消息中可能包含表名前缀，如 "config_group.uk_group_name"）
            if (errorMessage.contains("uk_group_name") || errorMessage.contains("group_name")) {
                // 提取冲突的值（可能是复合键，如 "namespace-name"）
                String duplicateValue = extractDuplicateValue(errorMessage);
                if (duplicateValue != null) {
                    // 如果是复合键，提取分组名称部分
                    String groupName = extractGroupNameFromCompositeKey(duplicateValue);
                    if (groupName != null && !groupName.equals(duplicateValue)) {
                        return String.format("分组名称 '%s' 已存在，请使用其他名称", groupName);
                    }
                    return String.format("分组名称 '%s' 已存在，请使用其他名称", duplicateValue);
                }
                return "分组名称已存在，请使用其他名称";
            }
            if (errorMessage.contains("uk_ns_name") || errorMessage.contains("ns_name")) {
                String duplicateValue = extractDuplicateValue(errorMessage);
                if (duplicateValue != null) {
                    return String.format("命名空间名称 '%s' 已存在，请使用其他名称", duplicateValue);
                }
                return "命名空间名称已存在，请使用其他名称";
            }
            if (errorMessage.contains("uk_ns_gp_key") || errorMessage.contains("ns_gp_key")) {
                String duplicateValue = extractDuplicateValue(errorMessage);
                if (duplicateValue != null) {
                    // 复合键格式可能是 "namespace-group-key"，提取 key 部分
                    String keyName = extractKeyFromCompositeKey(duplicateValue);
                    if (keyName != null && !keyName.equals(duplicateValue)) {
                        return String.format("配置项键 '%s' 已存在，请使用其他键名", keyName);
                    }
                    return String.format("配置项键 '%s' 已存在，请使用其他键名", duplicateValue);
                }
                return "配置项键已存在，请使用其他键名";
            }
            return "数据已存在，请检查输入";
        }

        // 处理外键约束
        if (errorMessage.contains("foreign key constraint") || errorMessage.contains("Cannot add or update")) {
            return "关联的数据不存在，请检查输入";
        }

        // 处理非空约束
        if (errorMessage.contains("cannot be null") || errorMessage.contains("NOT NULL")) {
            return "必填字段不能为空";
        }

        // 默认错误消息
        log.debug("Unhandled database error: {}", errorMessage);
        return "数据操作失败，请检查输入数据";
    }

    /**
     * 从错误消息中提取重复的值。
     * 例如：从 "Duplicate entry 'com.ddm-common' for key 'uk_group_name'" 中提取 "com.ddm-common"
     *
     * @param errorMessage 错误消息
     * @return 重复的值，如果无法提取则返回 null
     */
    private String extractDuplicateValue(String errorMessage) {
        try {
            int startIndex = errorMessage.indexOf("'");
            if (startIndex == -1) {
                return null;
            }
            int endIndex = errorMessage.indexOf("'", startIndex + 1);
            if (endIndex == -1) {
                return null;
            }
            return errorMessage.substring(startIndex + 1, endIndex);
        } catch (Exception e) {
            log.debug("Failed to extract duplicate value from error message", e);
            return null;
        }
    }

    /**
     * 从复合键中提取分组名称。
     * MySQL 的复合唯一键在错误消息中可能显示为 "namespace-name" 格式。
     *
     * @param compositeKey 复合键值
     * @return 分组名称，如果无法提取则返回 null
     */
    private String extractGroupNameFromCompositeKey(String compositeKey) {
        // 复合键格式可能是 "namespace-name"，尝试提取最后一部分
        if (compositeKey != null && compositeKey.contains("-")) {
            String[] parts = compositeKey.split("-", 2);
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return null;
    }

    /**
     * 从复合键中提取配置项键名。
     * MySQL 的复合唯一键在错误消息中可能显示为 "namespace-group-key" 格式。
     *
     * @param compositeKey 复合键值
     * @return 配置项键名，如果无法提取则返回 null
     */
    private String extractKeyFromCompositeKey(String compositeKey) {
        // 复合键格式可能是 "namespace-group-key"，尝试提取最后一部分
        if (compositeKey != null) {
            // 尝试按 "-" 分割，取最后一部分
            int lastDashIndex = compositeKey.lastIndexOf("-");
            if (lastDashIndex > 0 && lastDashIndex < compositeKey.length() - 1) {
                return compositeKey.substring(lastDashIndex + 1);
            }
        }
        return null;
    }
}

