package com.ddm.argus.utils;

import com.ddm.argus.ecs.EcsConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * @author : liyifei
 * @created : 2025/10/18, Saturday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
public class JsonUtils {

    private static final class Holder {
        private static final ObjectMapper INSTANCE = new ObjectMapper();
    }
    
    private static ObjectMapper mapper() { 
        return Holder.INSTANCE; 
    }

    public static JsonNode httpJson(HttpClient http, String url) throws Exception {
        final HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(EcsConstants.HTTP_TIMEOUT)
                .GET().build();
        final HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        
        // 检查 HTTP 状态码
        if (res.statusCode() >= 400) {
            String bodySnippet = res.body() != null && res.body().length() > 200
                    ? res.body().substring(0, 200) + "..."
                    : res.body();
            throw new RuntimeException("HTTP request failed with status " + res.statusCode()
                    + " for URL: " + url + " | body: " + bodySnippet);
        }
        
        return mapper().readTree(res.body());
    }

    public static String text(JsonNode root, String field) {
        if (root == null) return null;
        final JsonNode n = root.path(field);
        return n.isMissingNode() ? null : n.asText();
    }

    /**
     * 安全获取 JSON 字段文本值，如果字段不存在或为 null，返回空字符串。
     * 适用于模板填充等需要避免 null 的场景。
     */
    public static String safeText(JsonNode root, String field) {
        String val = text(root, field);
        return val == null ? "" : val;
    }
}
