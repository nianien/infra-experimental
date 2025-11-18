package com.ddm.chaos.provider;

import com.ddm.chaos.resolver.ConfRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 基于 JDBC 的数据提供者实现。
 * <p>
 * 从关系型数据库（MySQL、H2 等）中读取配置数据。
 * <p>
 * 支持的配置表结构：
 * <ul>
 *   <li>config_namespace：命名空间表</li>
 *   <li>config_group：分组表</li>
 *   <li>config_item：配置项表（包含 namespace、group_name、key、value、variants 字段）</li>
 * </ul>
 *
 * <p><strong>使用方式：</strong>
 * 通过构造函数传入已配置的 DataSource，由外部（如 Spring）管理数据源的生命周期。
 *
 * @author liyifei
 * @see DataProvider
 * @since 1.0
 */
public class JdbcDataProvider implements DataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataProvider.class);

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 构造 JDBC 数据提供者。
     * <p>
     * 使用传入的 DataSource 创建 NamedParameterJdbcTemplate。
     * DataSource 的生命周期由外部管理，本类不负责关闭。
     *
     * @param dataSource 数据源，不能为 null
     * @throws NullPointerException 如果 dataSource 为 null
     */
    public JdbcDataProvider(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }


    /**
     * 从数据库加载指定配置项的数据。
     * <p>
     * 根据配置引用（ConfRef）中的 namespace、group、key 查询数据库，
     * 返回对应的配置项数据。
     *
     * @param ref 配置引用，包含 namespace、group、key
     * @return 配置项数据，如果未找到则可能返回 null（取决于数据库查询结果）
     * @throws DataAccessException 如果数据库查询失败
     */
    @Override
    public ConfItem loadData(ConfRef ref) {
        String sql = """
                SELECT
                    c.`namespace` AS `namespace`,
                    c.`group_name` AS `group`,
                    c.`key`,
                    c.`value`,
                    c.`variants`
                FROM `config_item` c
                WHERE c.`namespace` = :namespace
                  AND c.`group_name` = :groupName
                  AND c.`key` = :key
                  AND c.`enabled` = 1
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("namespace", ref.namespace())
                .addValue("key", ref.key())
                .addValue("groupName", ref.group());

        try {
            log.debug("Querying config item from database: {}", ref);
            ConfItem item = jdbc.queryForObject(sql, params, this::mapRowToConfItem);
            if (item != null) {
                log.trace("Successfully loaded config item from database: {} (value length: {})",
                        ref, item.value() != null ? item.value().length() : 0);
            }
            return item;
        } catch (EmptyResultDataAccessException e) {
            log.debug("Config item not found in database: {}", ref);
            return null;
        } catch (DataAccessException e) {
            log.error("Database error while loading config item: {} (SQL: {})", ref, sql, e);
            throw new IllegalStateException(
                    String.format("Failed to load config item from database: %s", ref), e);
        }
    }

    /**
     * 将 ResultSet 行映射为 ConfItem 对象。
     *
     * @param rs     ResultSet
     * @param rowNum 行号（未使用）
     * @throws SQLException 如果读取 ResultSet 时发生错误
     */
    private ConfItem mapRowToConfItem(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new ConfItem(
                    rs.getString("namespace"),
                    rs.getString("group"),
                    rs.getString("key"),
                    rs.getString("value"),
                    rs.getString("variants")
            );
        } catch (SQLException e) {
            log.error("Failed to map ResultSet row to ConfItem at row {}", rowNum, e);
            throw e;
        }
    }


}