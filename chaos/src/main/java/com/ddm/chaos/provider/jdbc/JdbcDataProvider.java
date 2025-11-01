package com.ddm.chaos.provider.jdbc;

import com.ddm.chaos.provider.DataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.*;
import java.util.stream.Collectors;

/**
 * JdbcDataProvider
 *
 * <p>支持 config_group 分组优先级：
 * <pre>
 * CREATE TABLE config_group (
 *   id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   group_name VARCHAR(128) NOT NULL UNIQUE,
 *   priority   INT NOT NULL DEFAULT 0,   -- 越大越优先
 *   remark     VARCHAR(512),
 *   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * );
 *
 * CREATE TABLE config_data (
 *   id BIGINT AUTO_INCREMENT PRIMARY KEY,
 *   cfg_key   VARCHAR(255) NOT NULL,
 *   cfg_value TEXT         NOT NULL,
 *   cfg_type  VARCHAR(64)  DEFAULT 'string',
 *   enabled   BOOLEAN      DEFAULT TRUE,
 *   group_id  BIGINT       NOT NULL,
 *   remark    VARCHAR(512),
 *   updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *   created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *   UNIQUE KEY uk_cfg (cfg_key, group_id),
 *   FOREIGN KEY (group_id) REFERENCES config_group(id)
 * );
 * </pre>
 *
 * <p>查询逻辑：
 * <ul>
 *   <li>仅加载 enabled=TRUE 的配置；</li>
 *   <li>支持从配置传入多个 group_name；</li>
 *   <li>按 group.priority DESC, group.id DESC 稳定取最高优先记录；</li>
 *   <li>每个 cfg_key 仅保留一条最终生效值。</li>
 * </ul>
 */
public class JdbcDataProvider implements DataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataProvider.class);

    private JdbcTemplate jdbc;
    private List<String> groupNames = List.of("default"); // 默认组

    @Override
    public void initialize(Map<String, String> cfg) {
        String url = must(cfg, "url");
        String username = cfg.getOrDefault("username", "");
        String password = cfg.getOrDefault("password", "");

        this.jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));

        if (cfg.containsKey("groups")) {
            this.groupNames = Arrays.stream(cfg.get("groups").split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.toList());
        }

        ensureTables();
        log.info("JdbcDataProvider initialized: url={}, groups={}", url, groupNames);
    }

    @Override
    public Map<String, Object> loadAll() {
        if (groupNames.isEmpty()) {
            log.warn("No group names specified; skip loading configs");
            return Map.of();
        }

        // 使用 priority + g.id 确保结果唯一且稳定
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
                int i = 1;
                for (String name : groupNames) {
                    ps.setString(i++, name);
                }
            }, rs -> {
                Map<String, Object> result = new LinkedHashMap<>();
                while (rs.next()) {
                    result.put(rs.getString("cfg_key"), rs.getObject("cfg_value"));
                }
                return result;
            });
        } catch (Exception e) {
            log.error("[JdbcDataProvider] loadAll failed: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * 初始化表结构（幂等执行）
     */
    private void ensureTables() {
        try {
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
            log.debug("[JdbcDataProvider] ensureTables ignored (may exist): {}", e.getMessage());
        }
    }

    private static String must(Map<String, String> cfg, String key) {
        return Optional.ofNullable(cfg.get(key))
                .filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing config: " + key));
    }
}