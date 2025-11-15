package com.ddm.chaos.web;

import com.ddm.chaos.web.dto.ApiResponse;
import com.ddm.chaos.web.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 配置管理控制器，提供配置的 RESTful API。
 * <p>基于 chaos 模块的表结构设计，支持：
 * <ul>
 *   <li>命名空间（namespace）管理</li>
 *   <li>配置分组（group）管理</li>
 *   <li>配置项（config_item）的增删改查</li>
 * </ul>
 *
 * @author liyifei
 * @since 1.0
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /* ===================== 命名空间管理 ===================== */

    /**
     * 获取所有命名空间列表。
     */
    @GetMapping("/namespaces")
    public ResponseEntity<List<Map<String, Object>>> listNamespaces() {
        try {
            List<Map<String, Object>> namespaces = configService.listNamespaces();
            return ResponseEntity.ok(namespaces);
        } catch (Exception e) {
            log.error("Failed to list namespaces", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取单个命名空间详情。
     */
    @GetMapping("/namespaces/{id}")
    public ResponseEntity<Map<String, Object>> getNamespace(@PathVariable Long id) {
        try {
            Map<String, Object> namespace = configService.getNamespace(id);
            if (namespace == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(namespace);
        } catch (Exception e) {
            log.error("Failed to get namespace {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 创建命名空间。
     */
    @PostMapping("/namespaces")
    public ResponseEntity<Map<String, Object>> createNamespace(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");
        String owner = request.get("owner");

        configService.createNamespace(name, description, owner);
        return ResponseEntity.ok(ApiResponse.success("命名空间创建成功"));
    }

    /**
     * 更新命名空间。
     */
    @PutMapping("/namespaces/{id}")
    public ResponseEntity<Map<String, Object>> updateNamespace(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");
        String owner = request.get("owner");

        boolean updated = configService.updateNamespace(id, name, description, owner);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success("命名空间更新成功"));
    }

    /* ===================== 配置分组管理 ===================== */

    /**
     * 获取指定命名空间下的所有分组。
     * 注意：此路由必须在 /namespaces/{id} 之前定义，以避免路由冲突。
     */
    @GetMapping("/namespaces/{namespaceId}/groups")
    public ResponseEntity<List<Map<String, Object>>> listGroups(@PathVariable Long namespaceId) {
        try {
            List<Map<String, Object>> groups = configService.listGroups(namespaceId);
            return ResponseEntity.ok(groups);
        } catch (Exception e) {
            log.error("Failed to list groups for namespace {}", namespaceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取单个分组详情。
     */
    @GetMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> getGroup(@PathVariable Long id) {
        try {
            Map<String, Object> group = configService.getGroup(id);
            if (group == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(group);
        } catch (Exception e) {
            log.error("Failed to get group {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 创建配置分组。
     */
    @PostMapping("/namespaces/{namespaceId}/groups")
    public ResponseEntity<Map<String, Object>> createGroup(
            @PathVariable Long namespaceId,
            @RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");

        configService.createGroup(namespaceId, name, description);
        return ResponseEntity.ok(ApiResponse.success("分组创建成功"));
    }

    /**
     * 更新配置分组。
     */
    @PutMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> updateGroup(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");

        boolean updated = configService.updateGroup(id, name, description);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success("分组更新成功"));
    }

    /**
     * 删除配置分组。
     */
    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> deleteGroup(@PathVariable Long id) {
        configService.deleteGroup(id);
        return ResponseEntity.ok(ApiResponse.success("分组删除成功"));
    }

    /* ===================== 配置项管理 ===================== */

    /**
     * 获取配置项列表。
     */
    @GetMapping("/items")
    public ResponseEntity<List<Map<String, Object>>> listItems(
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(required = false) Long groupId) {
        try {
            List<Map<String, Object>> items = configService.listItems(namespaceId, groupId);
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            log.error("Failed to list config items", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取单个配置项详情。
     */
    @GetMapping("/items/{id}")
    public ResponseEntity<Map<String, Object>> getItem(@PathVariable Long id) {
        try {
            Map<String, Object> item = configService.getItem(id);
            if (item == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(item);
        } catch (Exception e) {
            log.error("Failed to get config item {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 创建配置项。
     */
    @PostMapping("/items")
    public ResponseEntity<Map<String, Object>> createItem(@RequestBody Map<String, Object> request) {
        Long namespaceId = getLong(request, "namespaceId");
        Long groupId = getLong(request, "groupId");
        String key = (String) request.get("key");
        String value = (String) request.get("value");
        String variant = (String) request.get("variant");
        String type = (String) request.getOrDefault("type", "string");
        Boolean enabled = (Boolean) request.getOrDefault("enabled", true);
        String description = (String) request.get("description");
        String updatedBy = (String) request.get("updatedBy");

        configService.createItem(namespaceId, groupId, key, value, variant, type, enabled, description, updatedBy);
        return ResponseEntity.ok(ApiResponse.success("配置项创建成功"));
    }

    /**
     * 更新配置项。
     */
    @PutMapping("/items/{id}")
    public ResponseEntity<Map<String, Object>> updateItem(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        String value = (String) request.get("value");
        String variant = (String) request.get("variant");
        String type = (String) request.get("type");
        Boolean enabled = (Boolean) request.get("enabled");
        String description = (String) request.get("description");
        String updatedBy = (String) request.get("updatedBy");

        boolean updated = configService.updateItem(id, value, variant, type, enabled, description, updatedBy);
        if (!updated) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ApiResponse.success("配置项更新成功"));
    }

    /**
     * 删除配置项。
     */
    @DeleteMapping("/items/{id}")
    public ResponseEntity<Map<String, Object>> deleteItem(@PathVariable Long id) {
        configService.deleteItem(id);
        return ResponseEntity.ok(ApiResponse.success("配置项删除成功"));
    }

    /* ===================== 辅助方法 ===================== */

    private Long getLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
