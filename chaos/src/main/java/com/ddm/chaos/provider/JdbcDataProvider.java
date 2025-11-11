package com.ddm.chaos.provider;

import com.ddm.chaos.defined.ConfInfo;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 JDBC 的数据提供者实现。
 * <p>从关系型数据库（MySQL、H2 等）中读取配置数据。
 * <p>支持的配置表结构：
 * <ul>
 *   <li>config_namespace：命名空间表</li>
 *   <li>config_group：分组表</li>
 *   <li>config_item：配置项表（包含 key、value、variant 字段）</li>
 * </ul>
 *
 * @author liyifei
 * @see DataProvider
 * @since 1.0
 */
public class JdbcDataProvider implements DataProvider {

    private NamedParameterJdbcTemplate jdbc;


    @Override
    public String type() {
        return "jdbc";
    }

    /**
     * 初始化 JDBC 数据源。
     * <p>从配置中读取：
     * <ul>
     *   <li>命名空间（namespace）：必填</li>
     *   <li>分组（groups）：可选，为空则查询所有分组</li>
     *   <li>标签（tags）：可选，用于配置项变体计算</li>
     *   <li>数据库连接信息（jdbc-url、username、password）：从 provider.options 中读取</li>
     * </ul>
     *
     * @param options 配置属性，不能为 null
     * @throws IllegalArgumentException 如果必填配置缺失
     */
    @Override
    public void init(Map<String, String> options) {
        Map<String, String> cfg = Objects.requireNonNull(options, "options must not be null");
        DataSource ds = new DriverManagerDataSource(must(cfg, "jdbc-url"),
                cfg.getOrDefault("username", ""),
                cfg.getOrDefault("password", ""));
        this.jdbc = new NamedParameterJdbcTemplate(ds);
    }


    @Override
    public ConfItem loadData(ConfInfo info) {
        String sql = """
                SELECT
                    n.`name` AS `namespace`,
                    g.`name` AS `group`,
                    d.`key`,
                    d.`value`,
                    d.`variant`
                FROM `config_item` d
                JOIN `config_group` g      ON d.`group_id`     = g.`id`
                JOIN `config_namespace` n  ON g.`namespace_id` = n.`id`
                WHERE d.`key` = :key
                  AND d.`enabled` = 1
                  AND n.`name` = :namespace
                  AND g.`name` = :group;
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("key", info.key())
                .addValue("namespace", info.namespace())
                .addValue("group", info.group());
        return jdbc.queryForObject(sql, params, this::mapRowToConfItem);
    }
    
    /**
     * 将 ResultSet 行映射为 ConfItem 对象。
     *
     * @param rs ResultSet
     * @param rowNum 行号（未使用）
     * @return ConfItem 实例
     */
    private ConfItem mapRowToConfItem(java.sql.ResultSet rs, int rowNum) throws SQLException {
        return new ConfItem(
                rs.getString("namespace"),
                rs.getString("group"),
                rs.getString("key"),
                rs.getString("value"),
                rs.getString("variant")
        );
    }

    private static String must(Map<String, String> cfg, String key) {
        return Optional.ofNullable(cfg.get(key)).filter(v -> !v.isBlank()).orElseThrow(() -> new IllegalArgumentException("Missing required options: " + key));
    }


}