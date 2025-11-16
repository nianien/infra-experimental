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

    private static final String KEY_SUCCESS = "success";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_ERROR = "error";

    /**
     * 创建成功响应。
     *
     * @param message 成功消息
     * @return 响应对象
     */
    public static Map<String, Object> success(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put(KEY_SUCCESS, true);
        result.put(KEY_MESSAGE, message);
        return result;
    }

    /**
     * 创建成功响应（无消息）。
     *
     * @return 响应对象
     */
    public static Map<String, Object> success() {
        return success("操作成功");
    }

    /**
     * 创建错误响应。
     *
     * @param message 错误消息
     * @return 响应对象
     */
    public static Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put(KEY_SUCCESS, false);
        result.put(KEY_ERROR, message);
        return result;
    }

    /**
     * 创建带数据的成功响应。
     *
     * @param message 成功消息
     * @param data 数据对象
     * @return 响应对象
     */
    public static Map<String, Object> successWithData(String message, Object data) {
        Map<String, Object> result = success(message);
        result.put("data", data);
        return result;
    }
}

