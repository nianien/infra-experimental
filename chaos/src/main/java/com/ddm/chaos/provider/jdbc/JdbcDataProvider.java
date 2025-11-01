package com.ddm.chaos.provider.jdbc;

import com.ddm.chaos.provider.DataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
 * Map<String, String> config = Map.of(
 *     "url", "jdbc:mysql://localhost:3306/config_center",
 *     "username", "root",
 *     "password", "root",
 *     "groups", "default,prod"
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
     * 初始化 JDBC 数据源和配置组。
     *
     * <p>该方法执行以下操作：
     * <ol>
     *   <li>从配置中获取数据库连接参数（url、username、password）</li>
     *   <li>创建 JdbcTemplate 实例</li>
     *   <li>解析配置组名称列表（从 {@code groups} 配置项）</li>
     *   <li>确保数据库表结构存在（如果不存在则自动创建）</li>
     * </ol>
     *
     * @param cfg 配置参数 Map，必须包含 "url"，可选包含 "username"、"password"、"groups"
     * @throws IllegalArgumentException 如果缺少必需的配置参数（如 url）
     */
    @Override
    public void initialize(Map<String, String> cfg) {
        String url = must(cfg, "url");
        String username = cfg.getOrDefault("username", "");
        String password = cfg.getOrDefault("password", "");

        // 创建 JDBC 数据源和模板
        this.jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));

        // 解析配置组名称列表
        if (cfg.containsKey("groups")) {
            var parsed = Arrays.stream(cfg.get("groups").split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
            if (!parsed.isEmpty()) {
                this.groupNames = parsed;
            }
        }

        // 确保表结构存在
        ensureTables();
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
    public Map<String, Object> loadAll() {
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
                    result.put(rs.getString("cfg_key"), rs.getObject("cfg_value"));
                }
                return result;
            });
        } catch (Exception e) {
            log.error("Failed to load configs from database: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 确保数据库表结构存在（幂等执行）。
     *
     * <p>该方法会自动创建 {@code config_group} 和 {@code config_data} 表（如果不存在）。
     * 使用 {@code CREATE TABLE IF NOT EXISTS} 语句，确保幂等性。
     *
     * <p>如果表已存在或创建失败，会记录调试日志但不抛出异常，不影响主流程。
     */
    private void ensureTables() {
        try {
            // 创建 config_group 表
            jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS config_group (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            group_name VARCHAR(128) NOT NULL UNIQUE,
                            priority   INT NOT NULL DEFAULT 0,
                            remark     VARCHAR(512),
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """);

            // 创建 config_data 表
            jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS config_data (
                            id BIGINT AUTO_INCREMENT PRIMARY KEY,
                            cfg_key   VARCHAR(255) NOT NULL,
                            cfg_value TEXT         NOT NULL,
                            cfg_type  VARCHAR(64)  DEFAULT 'string',
                            enabled   BOOLEAN      DEFAULT TRUE,
                            group_id  BIGINT       NOT NULL,
                            remark    VARCHAR(512),
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            UNIQUE KEY uk_cfg (cfg_key, group_id),
                            FOREIGN KEY (group_id) REFERENCES config_group(id)
                        )
                    """);
        } catch (Exception e) {
            // 表可能已存在或创建失败，记录日志但不影响主流程
            log.debug("Failed to ensure tables (may already exist): {}", e.getMessage());
        }
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
