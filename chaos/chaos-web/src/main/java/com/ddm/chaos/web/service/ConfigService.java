package com.ddm.chaos.web.service;

import com.ddm.chaos.web.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置管理服务，提供配置的 CRUD 操作。
 *
 * @author liyifei
 * @since 1.0
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public ConfigService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ===================== 命名空间管理 ===================== */

    /**
     * 获取所有命名空间列表。
     */
    public List<Map<String, Object>> listNamespaces() {
        String sql = "SELECT id, name, description, owner, operator, created_at, updated_at FROM config_namespace ORDER BY id";
        return jdbcTemplate.query(sql, namespaceRowMapper());
    }

    /**
     * 获取单个命名空间详情。
     */
    public Map<String, Object> getNamespace(Long id) {
        String sql = "SELECT id, name, description, owner, operator, created_at, updated_at FROM config_namespace WHERE id = ?";
        List<Map<String, Object>> namespaces = jdbcTemplate.query(sql, namespaceRowMapper(), id);
        if (namespaces.isEmpty()) {
            return null;
        }
        return namespaces.get(0);
    }

    /**
     * 创建命名空间。
     * @param currentUser 当前用户，将作为 owner 和 operator
     */
    @Transactional
    public void createNamespace(String name, String description, String currentUser) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        String sql = "INSERT INTO config_namespace (name, description, owner, operator) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(sql, name, description, currentUser, currentUser);
    }

    /**
     * 更新命名空间。
     * @param currentUser 当前用户，将作为 operator
     */
    @Transactional
    public boolean updateNamespace(Long id, String name, String description, String owner, String currentUser) {
        StringBuilder sql = new StringBuilder("UPDATE config_namespace SET operator = ?");
        List<Object> params = new ArrayList<>();
        params.add(currentUser);
        boolean hasUpdate = false;

        if (name != null && !name.isBlank()) {
            sql.append(", name = ?");
            params.add(name);
            hasUpdate = true;
        }
        if (description != null) {
            sql.append(", description = ?");
            params.add(description);
            hasUpdate = true;
        }
        if (owner != null) {
            sql.append(", owner = ?");
            params.add(owner);
            hasUpdate = true;
        }

        sql.append(" WHERE id = ?");
        params.add(id);

        int updated = jdbcTemplate.update(sql.toString(), params.toArray());
        return updated > 0;
    }

    /* ===================== 配置分组管理 ===================== */

    /**
     * 获取指定命名空间下的所有分组。
     */
    public List<Map<String, Object>> listGroups(Long namespaceId) {
        String sql = "SELECT cg.id, ns.id AS namespace_id, ns.name AS namespace_name, " +
                "cg.name, cg.description, cg.owner, cg.operator, cg.created_at, cg.updated_at " +
                "FROM config_group cg " +
                "JOIN config_namespace ns ON cg.namespace = ns.name " +
                "WHERE ns.id = ? ORDER BY cg.id";
        return jdbcTemplate.query(sql, groupWithNamespaceRowMapper(), namespaceId);
    }

    /**
     * 获取单个分组详情。
     */
    public Map<String, Object> getGroup(Long id) {
        String sql = "SELECT cg.id, ns.id AS namespace_id, COALESCE(ns.name, cg.namespace) AS namespace_name, " +
                "cg.name, cg.description, cg.owner, cg.operator, cg.created_at, cg.updated_at " +
                "FROM config_group cg " +
                "LEFT JOIN config_namespace ns ON cg.namespace = ns.name " +
                "WHERE cg.id = ?";
        List<Map<String, Object>> groups = jdbcTemplate.query(sql, groupWithNamespaceRowMapper(), id);
        if (groups.isEmpty()) {
            return null;
        }
        return groups.get(0);
    }

    /**
     * 创建配置分组。
     * @param currentUser 当前用户，将作为 owner 和 operator
     */
    @Transactional
    public void createGroup(Long namespaceId, String name, String description, String currentUser) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }

        String namespaceName = getNamespaceName(namespaceId);
        if (namespaceName == null) {
            throw new IllegalArgumentException("命名空间不存在: " + namespaceId);
        }

        String sql = "INSERT INTO config_group (`namespace`, name, description, owner, operator) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, namespaceName, name, description, currentUser, currentUser);
    }

    /**
     * 更新配置分组。
     * @param currentUser 当前用户，将作为 operator
     */
    @Transactional
    public boolean updateGroup(Long id, String name, String description, String owner, String currentUser) {
        StringBuilder sql = new StringBuilder("UPDATE config_group SET operator = ?");
        List<Object> params = new ArrayList<>();
        params.add(currentUser);
        boolean hasUpdate = false;

        if (name != null && !name.isBlank()) {
            sql.append(", name = ?");
            params.add(name);
            hasUpdate = true;
        }
        if (description != null) {
            sql.append(", description = ?");
            params.add(description);
            hasUpdate = true;
        }
        if (owner != null) {
            sql.append(", owner = ?");
            params.add(owner);
            hasUpdate = true;
        }

        sql.append(" WHERE id = ?");
        params.add(id);

        int updated = jdbcTemplate.update(sql.toString(), params.toArray());
        return updated > 0;
    }

    /**
     * 删除配置分组。
     * 如果该分组下存在配置项，则抛出异常。
     */
    @Transactional
    public void deleteGroup(Long id) {
        // 检查是否存在关联的配置项
        String checkSql = "SELECT COUNT(*) FROM config_item ci " +
                "INNER JOIN config_group cg ON ci.group_name = cg.name AND ci.namespace = cg.namespace " +
                "WHERE cg.id = ?";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, id);
        if (count != null && count > 0) {
            throw new IllegalStateException("无法删除分组：该分组下存在 " + count + " 个配置项，请先删除所有配置项");
        }

        String sql = "DELETE FROM config_group WHERE id = ?";
        int deleted = jdbcTemplate.update(sql, id);
        if (deleted == 0) {
            throw new IllegalArgumentException("分组不存在: " + id);
        }
    }

    /* ===================== 配置项管理 ===================== */

    /**
     * 获取配置项列表。
     */
    public List<Map<String, Object>> listItems(Long namespaceId, Long groupId) {
        StringBuilder sql = new StringBuilder(
                "SELECT ci.id, ns.id AS namespace_id, COALESCE(ns.name, ci.namespace) AS namespace_name, " +
                        "cg.id AS group_id, COALESCE(cg.name, ci.group_name) AS group_name, " +
                        "ci.`key`, ci.`value`, ci.variants AS variants, ci.type, ci.enabled, ci.description, " +
                        "ci.operator, ci.created_at, ci.updated_at, ci.version " +
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

        return jdbcTemplate.query(sql.toString(), configItemDetailRowMapper(), params.toArray());
    }

    /**
     * 获取单个配置项详情。
     */
    public Map<String, Object> getItem(Long id) {
        String sql = "SELECT ci.id, ns.id AS namespace_id, COALESCE(ns.name, ci.namespace) AS namespace_name, " +
                "cg.id AS group_id, COALESCE(cg.name, ci.group_name) AS group_name, " +
                "ci.`key`, ci.`value`, ci.variants AS variants, ci.type, ci.enabled, ci.description, " +
                "ci.operator, ci.created_at, ci.updated_at, ci.version " +
                "FROM config_item ci " +
                "LEFT JOIN config_namespace ns ON ci.namespace = ns.name " +
                "LEFT JOIN config_group cg ON ci.group_name = cg.name AND cg.namespace = ci.namespace " +
                "WHERE ci.id = ?";
        List<Map<String, Object>> items = jdbcTemplate.query(sql, configItemDetailRowMapper(), id);
        if (items.isEmpty()) {
            return null;
        }
        return items.get(0);
    }

    /**
     * 创建配置项。
     * @param currentUser 当前用户，将作为 operator
     */
    @Transactional
    public void createItem(Long namespaceId, Long groupId, String key, String value, String variant,
                          String type, Boolean enabled, String description, String currentUser) {
        if (namespaceId == null || groupId == null || key == null || key.isBlank() || value == null) {
            throw new IllegalArgumentException("namespaceId, groupId, key, value 不能为空");
        }

        String namespaceName = getNamespaceName(namespaceId);
        if (namespaceName == null) {
            throw new IllegalArgumentException("命名空间不存在: " + namespaceId);
        }

        GroupInfo groupInfo = getGroupInfo(groupId);
        if (groupInfo == null) {
            throw new IllegalArgumentException("配置分组不存在: " + groupId);
        }
        if (!namespaceName.equals(groupInfo.namespace())) {
            throw new IllegalArgumentException("分组不属于指定命名空间");
        }

        // 验证 variant 是否为有效的 JSON
        if (variant != null && !variant.isBlank()) {
            try {
                JSON.readTree(variant);
            } catch (Exception e) {
                throw new IllegalArgumentException("variant 必须是有效的 JSON 格式");
            }
        }

        String sql = "INSERT INTO config_item (`namespace`, group_name, `key`, `value`, variants, type, enabled, description, operator) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String variantParam = (variant == null || variant.isBlank()) ? null : variant;
        jdbcTemplate.update(sql, namespaceName, groupInfo.name(), key, value, variantParam, type, enabled ? 1 : 0, description, currentUser);
    }

    /**
     * 更新配置项。
     * @param currentUser 当前用户，将作为 operator
     */
    @Transactional
    public boolean updateItem(Long id, String value, String variant, String type, Boolean enabled,
                             String description, String currentUser) {
        // 验证 variant 是否为有效的 JSON
        if (variant != null && !variant.isBlank()) {
            try {
                JSON.readTree(variant);
            } catch (Exception e) {
                throw new IllegalArgumentException("variant 必须是有效的 JSON 格式");
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE config_item SET version = version + 1, operator = ?");
        List<Object> params = new ArrayList<>();
        params.add(currentUser);

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
        sql.append(" WHERE id = ?");
        params.add(id);

        int updated = jdbcTemplate.update(sql.toString(), params.toArray());
        return updated > 0;
    }

    /**
     * 删除配置项。
     */
    @Transactional
    public void deleteItem(Long id) {
        String sql = "DELETE FROM config_item WHERE id = ?";
        int deleted = jdbcTemplate.update(sql, id);
        if (deleted == 0) {
            throw new IllegalArgumentException("配置项不存在: " + id);
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
            map.put("operator", rs.getString("operator"));
            map.put("createdAt", formatTimestamp(rs.getTimestamp("created_at")));
            map.put("updatedAt", formatTimestamp(rs.getTimestamp("updated_at")));
            return map;
        };
    }

    private RowMapper<Map<String, Object>> groupWithNamespaceRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", rs.getLong("id"));
            Object nsIdObj = rs.getObject("namespace_id");
            map.put("namespaceId", nsIdObj == null ? null : rs.getLong("namespace_id"));
            map.put("namespaceName", rs.getString("namespace_name"));
            map.put("name", rs.getString("name"));
            map.put("description", rs.getString("description"));
            map.put("owner", rs.getString("owner"));
            map.put("operator", rs.getString("operator"));
            map.put("createdAt", formatTimestamp(rs.getTimestamp("created_at")));
            map.put("updatedAt", formatTimestamp(rs.getTimestamp("updated_at")));
            return map;
        };
    }

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
            map.put("operator", rs.getString("operator"));
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

