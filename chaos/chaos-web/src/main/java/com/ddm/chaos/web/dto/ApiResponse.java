package com.ddm.chaos.web.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一的 API 响应格式。
 *
 * @author liyifei
 * @since 1.0
 */
public class ApiResponse {

    /**
     * 创建成功响应。
     *
     * @param message 成功消息
     * @return 响应对象
     */
    public static Map<String, Object> success(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", message);
        return result;
    }

    /**
     * 创建成功响应（带数据）。
     *
     * @param message 成功消息
     * @param data    数据
     * @return 响应对象
     */
    public static Map<String, Object> success(String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", message);
        result.put("data", data);
        return result;
    }

    /**
     * 创建错误响应。
     *
     * @param message 错误消息
     * @return 响应对象
     */
    public static Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("error", message);
        return result;
    }
}

