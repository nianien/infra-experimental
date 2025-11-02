package com.ddm.chaos.provider.jdbc;

import com.ddm.chaos.provider.DataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 JDBC 的配置数据提供者实现。
 *
 * <p>该实现从关系型数据库（MySQL、PostgreSQL 等）读取配置数据，支持配置分组和优先级。
 *
 * <p><strong>数据库表结构：</strong>
 *
 * <h3>config_group 表（配置分组）</h3>
 * <pre>{@code
 * CREATE TABLE config_group (
 *   id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   group_name VARCHAR(128) NOT NULL UNIQUE,  -- 分组名称，唯一
 *   priority   INT NOT NULL DEFAULT 0,        -- 优先级，越大越优先
 *   remark     VARCHAR(512),                  -- 备注
 *   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 * }</pre>
 *
 * <h3>config_data 表（配置数据）</h3>
 * <pre>{@code
 * CREATE TABLE config_data (
 *   id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   cfg_key   VARCHAR(255) NOT NULL,          -- 配置键
 *   cfg_value TEXT         NOT NULL,          -- 配置值（可以是字符串、JSON 等）
 *   cfg_type  VARCHAR(64)  DEFAULT 'string',  -- 配置类型（可选）
 *   enabled   BOOLEAN      DEFAULT TRUE,      -- 是否启用
 *   group_id  BIGINT       NOT NULL,          -- 所属分组 ID
 *   remark    VARCHAR(512),                   -- 备注
 *   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *   UNIQUE KEY uk_cfg (cfg_key, group_id),    -- 同一分组内配置键唯一
 *   FOREIGN KEY (group_id) REFERENCES config_group(id)
 * );
 * }</pre>
 *
 * <p><strong>配置参数：</strong>
 * <ul>
 *   <li>{@code url}（必需）：JDBC 连接 URL，如 {@code jdbc:mysql://localhost:3306/config_center}</li>
 *   <li>{@code username}（可选）：数据库用户名，默认为空字符串</li>
 *   <li>{@code password}（可选）：数据库密码，默认为空字符串</li>
 *   <li>{@code groups}（可选）：配置组名称列表，逗号分隔，默认为 "default"</li>
 *   <li>{@code init_sql}（可选）：是否执行默认的表初始化，默认值为 {@code false}。
 *       只有当设置为 {@code "true"}/{@code "1"}/{@code "yes"}/{@code "on"} 时，才会自动创建表结构。
 *       如果未配置或设置为其他值，将跳过自动创建表结构。</li>
 * </ul>
 *
 * <p><strong>查询逻辑：</strong>
 * <ul>
 *   <li>仅加载 {@code enabled=TRUE} 的配置项</li>
 *   <li>支持从配置传入多个 {@code group_name}，用逗号分隔</li>
 *   <li>按 {@code group.priority DESC, group.id DESC} 排序，稳定取最高优先级的记录</li>
 *   <li>每个 {@code cfg_key} 仅保留一条最终生效值（使用 ROW_NUMBER 窗口函数）</li>
 * </ul>
 *
 * <p><strong>优先级规则：</strong>
 * <ul>
 *   <li>如果同一个配置键在多个分组中存在，选择优先级最高的分组</li>
 *   <li>如果优先级相同，选择 ID 最大的分组（最后创建的）</li>
 *   <li>如果配置键在同一个分组中存在多条记录，会抛出唯一键约束异常（应该在数据库层面避免）</li>
 * </ul>
 *
 * <p><strong>使用示例：</strong>
 * <pre>{@code
 * // 示例 1：使用默认表创建逻辑
 * Map<String, String> config = Map.of(
 *     "url", "jdbc:mysql://localhost:3306/config_center",
 *     "username", "root",
 *     "password", "root",
 *     "groups", "default,prod"
 * );
 *
 * // 示例 2：启用自动表初始化
 * Map<String, String> config2 = Map.of(
 *     "url", "jdbc:mysql://localhost:3306/config_center",
 *     "username", "root",
 *     "password", "root",
 *     "groups", "default,prod",
 *     "init_sql", "true"  // 启用自动创建表结构
 * );
 *
 * JdbcDataProvider provider = new JdbcDataProvider();
 * provider.initialize(config);
 * Map<String, Object> data = provider.loadAll();
 * }</pre>
 *
 * @author liyifei
 * @see DataProvider
 * @since 1.0
 */
public class JdbcDataProvider implements DataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataProvider.class);

    /**
     * JDBC 模板，用于执行数据库查询。
     */
    private JdbcTemplate jdbc;

    /**
     * 配置组名称列表，默认包含 "default" 组。
     */
    private List<String> groupNames = List.of("default");

    /**
     * 获取 JDBC 数据源（用于测试和调试）。
     *
     * @return 数据源实例，如果未初始化则返回 null
     */
    public DataSource getDataSource() {
        return jdbc != null ? jdbc.getDataSource() : null;
    }

    @Override
    public String type() {
        return "jdbc";
    }

    /**
     * 初始化 JDBC 数据源和配置组。
     *
     * <p>该方法执行以下操作：
     * <ol>
     *   <li>从配置中获取数据库连接参数（url、username、password）</li>
     *   <li>创建 JdbcTemplate 实例</li>
     *   <li>解析配置组名称列表（从 {@code groups} 配置项）</li>
     *   <li>表结构初始化：
     *     <ul>
     *       <li>只有当 {@code init_sql} 明确设置为 {@code "true"}/{@code "1"}/{@code "yes"}/{@code "on"} 时，才会执行表创建</li>
     *       <li>默认情况下（未配置或为其他值），跳过表创建</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param cfg 配置参数 Map，必须包含 "url"，
     *            可选包含 "username"、"password"、"groups"、"init_sql"
     * @throws IllegalArgumentException 如果缺少必需的配置参数（如 url）
     */
    @Override
    public void initialize(Map<String, String> cfg) {
        String url = must(cfg, "url");
        String username = cfg.getOrDefault("username", "");
        String password = cfg.getOrDefault("password", "");

        // 创建 JDBC 数据源和模板
        try {
            this.jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize JdbcTemplate for url: " + url, e);
        }

        // 解析配置组名称列表
        var groups = Arrays.stream(cfg.getOrDefault("groups", "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (!groups.isEmpty()) {
            this.groupNames = groups;
        }
        boolean initSql = switch (cfg.getOrDefault("init_sql", "").trim().toLowerCase()) {
            case "true", "1", "yes", "on" -> true;
            default -> false;
        };
        if (initSql) {
            ensureTables();
        }
        log.info("JdbcDataProvider initialized: url={}, groups={}", url, groupNames);
    }


    /**
     * 从数据库加载所有配置数据。
     *
     * <p>该方法执行以下步骤：
     * <ol>
     *   <li>检查配置组列表是否为空，如果为空则返回空 Map</li>
     *   <li>使用窗口函数（ROW_NUMBER）查询每个配置键的最高优先级记录</li>
     *   <li>仅加载 {@code enabled=TRUE} 的配置项</li>
     *   <li>按优先级和分组 ID 排序，确保结果稳定</li>
     *   <li>返回配置键值对的 Map</li>
     * </ol>
     *
     * <p>如果查询失败，会记录错误日志并返回空 Map，保证系统可用性。
     *
     * @return 配置键值对的 Map，Key 为配置键（cfg_key），Value 为配置值（cfg_value）
     * 如果无配置或查询失败，返回空 Map（不返回 null）
     */
    @Override
    public Map<String, Object> loadData(String namespace, String groups, String tag) {
        if (groupNames.isEmpty()) {
            log.warn("No group names specified; skip loading configs");
            return Map.of();
        }

        // 使用窗口函数确保每个 cfg_key 只取优先级最高的一条记录
        // 排序规则：priority DESC（优先级越高越优先），id DESC（同优先级时取最新的）
        String sql = """
                SELECT t.cfg_key, t.cfg_value
                FROM (
                    SELECT c.cfg_key, c.cfg_value, g.priority, g.id,
                           ROW_NUMBER() OVER (
                               PARTITION BY c.cfg_key
                               ORDER BY g.priority DESC, g.id DESC
                           ) AS rn
                    FROM config_data AS c
                    JOIN config_group AS g ON c.group_id = g.id
                    WHERE c.enabled = TRUE
                      AND g.group_name IN (%s)
                ) AS t
                WHERE t.rn = 1
                """.formatted(
                groupNames.stream().map(x -> "?").collect(Collectors.joining(", "))
        );

        try {
            return jdbc.query(sql, ps -> {
                // 设置查询参数：配置组名称列表
                int i = 1;
                for (String name : groupNames) {
                    ps.setString(i++, name);
                }
            }, rs -> {
                // 将查询结果转换为 Map
                Map<String, Object> result = new LinkedHashMap<>();
                while (rs.next()) {
                    String key = rs.getString("cfg_key");
                    Object value = rs.getObject("cfg_value");
                    if (key != null) {
                        result.put(key, value);
                    }
                }
                return result;
            });
        } catch (Exception e) {
            log.error("Failed to load configs from database", e);
            return Map.of();
        }
    }

    /**
     * 各数据库的完整 SQL 模板映射。
     *
     * <p>Key 格式：{dbType}:{tableName}，如 "h2:config_group"、"mysql:config_data"
     * Value：完整的 CREATE TABLE 语句
     *
     * <p>支持扩展：只需添加新的 Key-Value 对即可支持更多数据库类型。
     */
    private static final Map<String, String> SQL_TEMPLATES = Map.of(
            // H2 数据库
            "h2:config_group", """
                    CREATE TABLE IF NOT EXISTS config_group (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        group_name VARCHAR(128) NOT NULL UNIQUE,
                        priority   INT NOT NULL DEFAULT 0,
                        remark     VARCHAR(512),
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
            "h2:config_data", """
                    CREATE TABLE IF NOT EXISTS config_data (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        cfg_key   VARCHAR(255) NOT NULL,
                        cfg_value TEXT         NOT NULL,
                        cfg_type  VARCHAR(64)  DEFAULT 'string',
                        enabled   BOOLEAN      DEFAULT TRUE,
                        group_id  BIGINT       NOT NULL,
                        remark    VARCHAR(512),
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uk_cfg UNIQUE (cfg_key, group_id),
                        FOREIGN KEY (group_id) REFERENCES config_group(id)
                    )
                    """,
            // MySQL 数据库
            "mysql:config_group", """
                    CREATE TABLE IF NOT EXISTS `config_group` (
                        `id` bigint NOT NULL AUTO_INCREMENT,
                        `group_name` varchar(128) NOT NULL,
                        `priority` int NOT NULL DEFAULT '0',
                        `remark` varchar(512) DEFAULT NULL,
                        `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (`id`),
                        UNIQUE KEY `group_name` (`group_name`),
                        KEY `idx_group_name` (`group_name`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """,
            "mysql:config_data", """
                    CREATE TABLE IF NOT EXISTS `config_data` (
                        `id` BIGINT NOT NULL AUTO_INCREMENT,
                        `cfg_key` VARCHAR ( 255 ) NOT NULL,
                        `cfg_value` TEXT NOT NULL,
                        `cfg_type` VARCHAR ( 64 ) DEFAULT 'string',
                        `enabled` TINYINT ( 1 ) DEFAULT '1',
                        `group_id` BIGINT NOT NULL,
                        `remark` VARCHAR ( 512 ) DEFAULT NULL,
                        `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY ( `id` ),
                        UNIQUE KEY `uk_cfg` ( `cfg_key`, `group_id` ),
                        KEY `idx_cfg_group_enabled` ( `group_id`, `enabled` ),
                        CONSTRAINT `fk_cfg_group` FOREIGN KEY ( `group_id` ) REFERENCES `config_group` ( `id` )
                    ) ENGINE = INNODB DEFAULT CHARSET = utf8mb4;
                    """
    );

    /**
     * 确保数据库表结构存在（幂等执行）。
     *
     * <p>该方法会自动创建 {@code config_group} 和 {@code config_data} 表（如果不存在）。
     * 使用 {@code CREATE TABLE IF NOT EXISTS} 语句，确保幂等性。
     *
     * <p>支持的数据库类型：
     * <ul>
     *   <li>H2（用于测试）</li>
     *   <li>MySQL</li>
     * </ul>
     *
     * <p>如果表已存在或创建失败，会记录调试日志但不抛出异常，不影响主流程。
     */
    private void ensureTables() {
        try {
            // 获取数据库 URL 来判断数据库类型
            var dataSource = jdbc.getDataSource();
            if (dataSource == null) {
                log.warn("DataSource is null, cannot detect database provider");
                return;
            }

            String url;
            try (var connection = dataSource.getConnection()) {
                url = connection.getMetaData().getURL();
            }
            String dbType = detectDatabaseType(url);

            // 从 SQL 模板 Map 中取出对应数据库的 SQL 并执行
            String groupTableSql = SQL_TEMPLATES.get(dbType + ":config_group");
            String dataTableSql = SQL_TEMPLATES.get(dbType + ":config_data");

            if (groupTableSql != null && dataTableSql != null) {
                jdbc.execute(groupTableSql);
                jdbc.execute(dataTableSql);
            } else {
                log.warn("Unsupported database provider: {}, table creation skipped", dbType);
            }
        } catch (Exception e) {
            // 表可能已存在，记录调试日志
            // 如果是其他错误，记录警告但继续（某些数据库可能不支持某些语法）
            log.warn("Failed to ensure tables (may already exist).", e);
        }
    }

    /**
     * 根据数据库 URL 检测数据库类型。
     *
     * @param url 数据库连接 URL
     * @return 数据库类型（h2、mysql），如果无法识别则返回 "mysql" 作为默认值
     */
    private static String detectDatabaseType(String url) {
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.contains(":h2:")) {
            return "h2";
        } else if (lowerUrl.contains(":mysql:") || lowerUrl.contains(":mariadb:")) {
            return "mysql";
        }
        // 默认使用 mysql 语法（最常见）
        return "mysql";
    }

    /**
     * 从配置 Map 中获取必需的配置项。
     *
     * <p>如果配置项不存在或为空字符串，抛出 {@link IllegalArgumentException}。
     *
     * @param cfg 配置参数 Map
     * @param key 配置键
     * @return 配置值（非空且非空白）
     * @throws IllegalArgumentException 如果配置项不存在或为空
     */
    private static String must(Map<String, String> cfg, String key) {
        return Optional.ofNullable(cfg.get(key))
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing required config: " + key));
    }
}
