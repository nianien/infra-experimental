package com.ddm.demo.client;

import com.ddm.chaos.config.ConfigProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 配置管理控制器，提供配置的 CRUD 操作。
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
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造函数，从 ConfigProperties 创建 DataSource 和 JdbcTemplate。
     *
     * @param configProperties 配置属性
     */
    @Autowired
    public ConfigController(ConfigProperties configProperties) {
        DataSource dataSource = createDataSource(configProperties);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 从配置属性创建 DataSource。
     */
    private DataSource createDataSource(ConfigProperties props) {
        Map<String, String> options = props.provider().options();
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(options.get("jdbc-url"));
        ds.setUsername(options.getOrDefault("username", ""));
        ds.setPassword(options.getOrDefault("password", ""));
        return ds;
    }

    /* ===================== 命名空间管理 ===================== */

    /**
     * 获取所有命名空间列表。
     *
     * @return 命名空间列表
     */
    @GetMapping("/namespaces")
    public ResponseEntity<List<Map<String, Object>>> listNamespaces() {
        try {
            String sql = "SELECT id, name, description, owner, created_at, updated_at FROM config_namespace ORDER BY id";
            List<Map<String, Object>> namespaces = jdbcTemplate.query(sql, namespaceRowMapper());
            return ResponseEntity.ok(namespaces);
        } catch (Exception e) {
            log.error("Failed to list namespaces", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 创建命名空间。
     *
     * @param request 命名空间信息
     * @return 创建结果
     */
    @PostMapping("/namespaces")
    public ResponseEntity<Map<String, Object>> createNamespace(@RequestBody Map<String, String> request) {
        try {
            String name = request.get("name");
            String description = request.get("description");
            String owner = request.get("owner");

            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(createError("name 不能为空"));
            }

            String sql = "INSERT INTO config_namespace (name, description, owner) VALUES (?, ?, ?)";
            jdbcTemplate.update(sql, name, description, owner);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "命名空间创建成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to create namespace", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createError("创建失败: " + e.getMessage()));
        }
    }

    /* ===================== 配置分组管理 ===================== */

    /**
     * 获取指定命名空间下的所有分组。
     * 注意：此路由必须在 /namespaces/{id} 之前定义，以避免路由冲突。
     *
     * @param namespaceId 命名空间 ID
     * @return 分组列表，包含命名空间名
     */
    @GetMapping("/namespaces/{namespaceId}/groups")
    public ResponseEntity<List<Map<String, Object>>> listGroups(@PathVariable Long namespaceId) {
        try {
            String sql = "SELECT cg.id, ns.id AS namespace_id, ns.name AS namespace_name, " +
                    "cg.name, cg.description, cg.created_at, cg.updated_at " +
                    "FROM config_group cg " +
                    "JOIN config_namespace ns ON cg.namespace = ns.name " +
                    "WHERE ns.id = ? ORDER BY cg.id";
            List<Map<String, Object>> groups = jdbcTemplate.query(sql, groupWithNamespaceRowMapper(), namespaceId);
            return ResponseEntity.ok(groups);
        } catch (Exception e) {
            log.error("Failed to list groups for namespace {}", namespaceId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取单个命名空间详情。
     * 注意：此路由必须在 /namespaces/{namespaceId}/groups 之后定义，以避免路由冲突。
     *
     * @param id 命名空间 ID
     * @return 命名空间信息
     */
    @GetMapping("/namespaces/{id}")
    public ResponseEntity<Map<String, Object>> getNamespace(@PathVariable Long id) {
        try {
            String sql = "SELECT id, name, description, owner, created_at, updated_at FROM config_namespace WHERE id = ?";
            List<Map<String, Object>> namespaces = jdbcTemplate.query(sql, namespaceRowMapper(), id);
            if (namespaces.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(namespaces.get(0));
        } catch (Exception e) {
            log.error("Failed to get namespace {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 更新命名空间。
     *
     * @param id      命名空间 ID
     * @param request 命名空间信息
     * @return 更新结果
     */
    @PutMapping("/namespaces/{id}")
    public ResponseEntity<Map<String, Object>> updateNamespace(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            String name = request.get("name");
            String description = request.get("description");
            String owner = request.get("owner");

            StringBuilder sql = new StringBuilder("UPDATE config_namespace SET");
            List<Object> params = new ArrayList<>();
            boolean hasUpdate = false;

            if (name != null && !name.isBlank()) {
                sql.append(" name = ?");
                params.add(name);
                hasUpdate = true;
            }
            if (description != null) {
                if (hasUpdate) {
                    sql.append(",");
                }
                sql.append(" description = ?");
                params.add(description);
                hasUpdate = true;
            }
            if (owner != null) {
                if (hasUpdate) {
                    sql.append(",");
                }
                sql.append(" owner = ?");
                params.add(owner);
                hasUpdate = true;
            }

            if (!hasUpdate) {
                return ResponseEntity.badRequest().body(createError("没有需要更新的字段"));
            }

            sql.append(" WHERE id = ?");
            params.add(id);

            int updated = jdbcTemplate.update(sql.toString(), params.toArray());
            if (updated == 0) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "命名空间更新成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to update namespace {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createError("更新失败: " + e.getMessage()));
        }
    }

    /**
     * 创建配置分组。
     *
     * @param namespaceId 命名空间 ID
     * @param request 分组信息
     * @return 创建结果
     */
    @PostMapping("/namespaces/{namespaceId}/groups")
    public ResponseEntity<Map<String, Object>> createGroup(
            @PathVariable Long namespaceId,
            @RequestBody Map<String, String> request) {
        try {
            String name = request.get("name");
            String description = request.get("description");

            if (name == null || name.isBlank()) {
                return ResponseEntity.badRequest().body(createError("name 不能为空"));
            }

            String namespaceName = getNamespaceName(namespaceId);
            if (namespaceName == null) {
                return ResponseEntity.badRequest().body(createError("命名空间不存在: " + namespaceId));
            }

            String sql = "INSERT INTO config_group (`namespace`, name, description) VALUES (?, ?, ?)";
            jdbcTemplate.update(sql, namespaceName, name, description);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "分组创建成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to create group", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createError("创建失败: " + e.getMessage()));
        }
    }

    /**
     * 获取单个分组详情。
     *
     * @param id 分组 ID
     * @return 分组信息
     */
    @GetMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> getGroup(@PathVariable Long id) {
        try {
            String sql = "SELECT cg.id, ns.id AS namespace_id, COALESCE(ns.name, cg.namespace) AS namespace_name, " +
                    "cg.name, cg.description, cg.created_at, cg.updated_at " +
                    "FROM config_group cg " +
                    "LEFT JOIN config_namespace ns ON cg.namespace = ns.name " +
                    "WHERE cg.id = ?";
            List<Map<String, Object>> groups = jdbcTemplate.query(sql, groupWithNamespaceRowMapper(), id);
            if (groups.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(groups.get(0));
        } catch (Exception e) {
            log.error("Failed to get group {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 更新配置分组。
     *
     * @param id      分组 ID
     * @param request 分组信息
     * @return 更新结果
     */
    @PutMapping("/groups/{id}")
    public ResponseEntity<Map<String, Object>> updateGroup(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        try {
            String name = request.get("name");
            String description = request.get("description");

            StringBuilder sql = new StringBuilder("UPDATE config_group SET");
            List<Object> params = new ArrayList<>();
            boolean hasUpdate = false;

            if (name != null && !name.isBlank()) {
                sql.append(" name = ?");
                params.add(name);
                hasUpdate = true;
            }
            if (description != null) {
                if (hasUpdate) {
                    sql.append(",");
                }
                sql.append(" description = ?");
                params.add(description);
                hasUpdate = true;
            }

            if (!hasUpdate) {
                return ResponseEntity.badRequest().body(createError("没有需要更新的字段"));
            }

            sql.append(" WHERE id = ?");
            params.add(id);

            int updated = jdbcTemplate.update(sql.toString(), params.toArray());
            if (updated == 0) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "分组更新成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to update group {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createError("更新失败: " + e.getMessage()));
        }
    }

    /* ===================== 配置项管理 ===================== */

    /**
     * 获取配置项列表。
     *
     * @param namespaceId 命名空间 ID（可选，查询参数）
     * @param groupId     分组 ID（可选，查询参数）
     * @return 配置项列表，包含命名空间名和分组名
     */
    @GetMapping("/items")
    public ResponseEntity<List<Map<String, Object>>> listItems(
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(required = false) Long groupId) {
        try {
            StringBuilder sql = new StringBuilder(
                    "SELECT ci.id, ns.id AS namespace_id, COALESCE(ns.name, ci.namespace) AS namespace_name, " +
                    "cg.id AS group_id, COALESCE(cg.name, ci.group_name) AS group_name, " +
                    "ci.`key`, ci.`value`, ci.variants AS variants, ci.type, ci.enabled, ci.description, " +
                    "ci.updated_by, ci.created_at, ci.updated_at, ci.version " +
                    "FROM config_item ci " +
                    "LEFT JOIN config_namespace ns ON ci.namespace = ns.name " +
                    "LEFT JOIN config_group cg ON ci.group_name = cg.name AND cg.namespace = ci.namespace " +
                    "WHERE 1=1");
            List<Object> params = new ArrayList<>();

            if (namespaceId != null) {
                sql.append(" AND ns.id = ?");
                params.add(namespaceId);
            }
            if (groupId != null) {
                sql.append(" AND cg.id = ?");
                params.add(groupId);
            }
            sql.append(" ORDER BY ci.id");

            List<Map<String, Object>> items = jdbcTemplate.query(
                    sql.toString(), configItemDetailRowMapper(), params.toArray());
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            log.error("Failed to list config items", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取单个配置项详情。
     *
     * @param id 配置项 ID
     * @return 配置项信息，包含命名空间名和分组名
     */
    @GetMapping("/items/{id}")
    public ResponseEntity<Map<String, Object>> getItem(@PathVariable Long id) {
        try {
            String sql = "SELECT ci.id, ns.id AS namespace_id, COALESCE(ns.name, ci.namespace) AS namespace_name, " +
                    "cg.id AS group_id, COALESCE(cg.name, ci.group_name) AS group_name, " +
                    "ci.`key`, ci.`value`, ci.variants AS variants, ci.type, ci.enabled, ci.description, " +
                    "ci.updated_by, ci.created_at, ci.updated_at, ci.version " +
                    "FROM config_item ci " +
                    "LEFT JOIN config_namespace ns ON ci.namespace = ns.name " +
                    "LEFT JOIN config_group cg ON ci.group_name = cg.name AND cg.namespace = ci.namespace " +
                    "WHERE ci.id = ?";
            List<Map<String, Object>> items = jdbcTemplate.query(sql, configItemDetailRowMapper(), id);
            if (items.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(items.get(0));
        } catch (Exception e) {
            log.error("Failed to get config item {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 创建配置项。
     *
     * @param request 配置项信息
     * @return 创建结果
     */
    @PostMapping("/items")
    public ResponseEntity<Map<String, Object>> createItem(@RequestBody Map<String, Object> request) {
        try {
            Long namespaceId = getLong(request, "namespaceId");
            Long groupId = getLong(request, "groupId");
            String key = (String) request.get("key");
            String value = (String) request.get("value");
            String variant = (String) request.get("variant");
            String type = (String) request.getOrDefault("type", "string");
            Boolean enabled = (Boolean) request.getOrDefault("enabled", true);
            String description = (String) request.get("description");
            String updatedBy = (String) request.get("updatedBy");

            if (namespaceId == null || groupId == null || key == null || key.isBlank() || value == null) {
                return ResponseEntity.badRequest().body(createError("namespaceId, groupId, key, value 不能为空"));
            }

            String namespaceName = getNamespaceName(namespaceId);
            if (namespaceName == null) {
                return ResponseEntity.badRequest().body(createError("命名空间不存在: " + namespaceId));
            }

            GroupInfo groupInfo = getGroupInfo(groupId);
            if (groupInfo == null) {
                return ResponseEntity.badRequest().body(createError("配置分组不存在: " + groupId));
            }
            if (!namespaceName.equals(groupInfo.namespace())) {
                return ResponseEntity.badRequest().body(createError("分组不属于指定命名空间"));
            }

            // 验证 variant 是否为有效的 JSON
            if (variant != null && !variant.isBlank()) {
                try {
                    JSON.readTree(variant);
                } catch (Exception e) {
                    return ResponseEntity.badRequest().body(createError("variant 必须是有效的 JSON 格式"));
                }
            }

            String sql = "INSERT INTO config_item (`namespace`, group_name, `key`, `value`, variants, type, enabled, description, updated_by) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            // variant 列为 JSON 类型，空串会触发 MySQL JSON 校验失败；应传 NULL
            String variantParam = (variant == null || variant.isBlank()) ? null : variant;
            jdbcTemplate.update(sql, namespaceName, groupInfo.name(), key, value, variantParam, type, enabled ? 1 : 0, description, updatedBy);

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "配置项创建成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to create config item", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createError("创建失败: " + e.getMessage()));
        }
    }

    /**
     * 更新配置项。
     *
     * @param id      配置项 ID
     * @param request 配置项信息
     * @return 更新结果
     */
    @PutMapping("/items/{id}")
    public ResponseEntity<Map<String, Object>> updateItem(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        try {
            String value = (String) request.get("value");
            String variant = (String) request.get("variant");
            String type = (String) request.get("type");
            Boolean enabled = (Boolean) request.get("enabled");
            String description = (String) request.get("description");
            String updatedBy = (String) request.get("updatedBy");

            // 验证 variant 是否为有效的 JSON
            if (variant != null && !variant.isBlank()) {
                try {
                    JSON.readTree(variant);
                } catch (Exception e) {
                    return ResponseEntity.badRequest().body(createError("variant 必须是有效的 JSON 格式"));
                }
            }

            StringBuilder sql = new StringBuilder("UPDATE config_item SET version = version + 1");
            List<Object> params = new ArrayList<>();

            if (value != null) {
                sql.append(", `value` = ?");
                params.add(value);
            }
            if (variant != null) {
                sql.append(", variants = ?");
                params.add(variant.isBlank() ? null : variant);
            }
            if (type != null) {
                sql.append(", type = ?");
                params.add(type);
            }
            if (enabled != null) {
                sql.append(", enabled = ?");
                params.add(enabled ? 1 : 0);
            }
            if (description != null) {
                sql.append(", description = ?");
                params.add(description);
            }
            if (updatedBy != null) {
                sql.append(", updated_by = ?");
                params.add(updatedBy);
            }
            sql.append(" WHERE id = ?");
            params.add(id);

            int updated = jdbcTemplate.update(sql.toString(), params.toArray());
            if (updated == 0) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "配置项更新成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to update config item {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createError("更新失败: " + e.getMessage()));
        }
    }

    /**
     * 删除配置项。
     *
     * @param id 配置项 ID
     * @return 删除结果
     */
    @DeleteMapping("/items/{id}")
    public ResponseEntity<Map<String, Object>> deleteItem(@PathVariable Long id) {
        try {
            String sql = "DELETE FROM config_item WHERE id = ?";
            int deleted = jdbcTemplate.update(sql, id);
            if (deleted == 0) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "配置项删除成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to delete config item {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createError("删除失败: " + e.getMessage()));
        }
    }

    /* ===================== 辅助方法 ===================== */

    private RowMapper<Map<String, Object>> namespaceRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", rs.getLong("id"));
            map.put("name", rs.getString("name"));
            map.put("description", rs.getString("description"));
            map.put("owner", rs.getString("owner"));
            map.put("createdAt", formatTimestamp(rs.getTimestamp("created_at")));
            map.put("updatedAt", formatTimestamp(rs.getTimestamp("updated_at")));
            return map;
        };
    }

    /**
     * 分组 RowMapper，包含命名空间名。
     */
    private RowMapper<Map<String, Object>> groupWithNamespaceRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", rs.getLong("id"));
            Object nsIdObj = rs.getObject("namespace_id");
            map.put("namespaceId", nsIdObj == null ? null : rs.getLong("namespace_id"));
            map.put("namespaceName", rs.getString("namespace_name"));
            map.put("name", rs.getString("name"));
            map.put("description", rs.getString("description"));
            map.put("createdAt", formatTimestamp(rs.getTimestamp("created_at")));
            map.put("updatedAt", formatTimestamp(rs.getTimestamp("updated_at")));
            return map;
        };
    }

    /**
     * 配置项详情 RowMapper，包含命名空间名和分组名。
     */
    private RowMapper<Map<String, Object>> configItemDetailRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", rs.getLong("id"));
            Object nsIdObj = rs.getObject("namespace_id");
            Object groupIdObj = rs.getObject("group_id");
            map.put("namespaceId", nsIdObj == null ? null : rs.getLong("namespace_id"));
            map.put("groupId", groupIdObj == null ? null : rs.getLong("group_id"));
            map.put("namespaceName", rs.getString("namespace_name"));
            map.put("groupName", rs.getString("group_name"));
            map.put("key", rs.getString("key"));
            map.put("value", rs.getString("value"));
            map.put("variant", rs.getString("variants"));
            map.put("type", rs.getString("type"));
            map.put("enabled", rs.getBoolean("enabled"));
            map.put("description", rs.getString("description"));
            map.put("updatedBy", rs.getString("updated_by"));
            map.put("createdAt", formatTimestamp(rs.getTimestamp("created_at")));
            map.put("updatedAt", formatTimestamp(rs.getTimestamp("updated_at")));
            map.put("version", rs.getLong("version"));
            return map;
        };
    }

    private String formatTimestamp(java.sql.Timestamp timestamp) {
        if (timestamp == null) return null;
        return timestamp.toLocalDateTime().format(DATETIME_FORMATTER);
    }

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

    private Map<String, Object> createError(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        return error;
    }

    private String getNamespaceName(Long namespaceId) {
        if (namespaceId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT name FROM config_namespace WHERE id = ?",
                    String.class,
                    namespaceId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private GroupInfo getGroupInfo(Long groupId) {
        if (groupId == null) {
            return null;
        }
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, name, namespace FROM config_group WHERE id = ?",
                    (rs, rowNum) -> new GroupInfo(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("namespace")),
                    groupId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private record GroupInfo(Long id, String name, String namespace) {
    }
}

