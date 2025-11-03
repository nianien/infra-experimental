package com.ddm.chaos.provider;

import com.ddm.chaos.config.ConfigItem;
import com.ddm.chaos.config.ConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.*;

public class JdbcDataProvider implements DataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcDataProvider.class);

    private NamedParameterJdbcTemplate jdbc;

    /**
     * 命名空间名称（必传）
     */
    private String namespace;

    /**
     * 查询用分组（可为空；为空表示“查询该 namespace 下所有分组”）
     */
    private String[] groups = new String[0];

    /**
     * lane/tags（可为空；用于 ConfigItem 内部计算生效值）
     */
    private String[] tags = new String[0];


    @Override
    public String type() {
        return "jdbc";
    }

    @Override
    public void init(ConfigProperties pros) {
        this.namespace = mustNotBlank(pros.namespace(), "namespace");
        this.groups = normalize(pros.groups());
        this.tags = normalize(pros.tags());
        Map<String, String> cfg = Objects.requireNonNull(pros.provider().options(), "options must not be null");
        DataSource ds = new DriverManagerDataSource(must(cfg, "jdbc-url"),
                cfg.getOrDefault("username", ""),
                cfg.getOrDefault("password", ""));
        this.jdbc = new NamedParameterJdbcTemplate(ds);
    }


    @Override
    public List<ConfigItem> loadData() {
        boolean hasGroups = groups.length > 0;

        String sql = hasGroups ? """
                SELECT d.`key` , d.`value` , d.`variant` 
                FROM   `config_data` d
                JOIN   `config_group` g      ON d.`group_id`     = g.`id`
                JOIN   `config_namespace` n  ON g.`namespace_id` = n.`id`
                WHERE  n.`name` = :ns
                AND  d.`enabled` = 1
                AND  g.`name` IN (:groups)
                """ : """
                SELECT d.`key` , d.`value` , d.`variant` 
                FROM   `config_data` d
                JOIN   `config_group` g      ON d.`group_id`     = g.`id`
                JOIN   `config_namespace` n  ON g.`namespace_id` = n.`id`
                WHERE  n.`name` = :ns
                AND  d.`enabled` = 1
                """;

        MapSqlParameterSource p = new MapSqlParameterSource().addValue("ns", namespace);
        if (hasGroups) {
            // 用集合绑定 IN (:groups)
            p.addValue("groups", Arrays.asList(groups));
        }

        try {
            return jdbc.query(sql, p, rs -> {
                List<ConfigItem> res = new ArrayList<>();
                while (rs.next()) {
                    String key = rs.getString("key");
                    String value = rs.getString("value");
                    String variant = rs.getString("variant");
                    res.add(new ConfigItem(key, value, variant, tags));
                }
                return res;
            });
        } catch (Exception e) {
            log.warn("Failed to load configs via JDBC (ns={}, groups={}): {}", namespace, (hasGroups ? Arrays.toString(groups) : "(ALL)"), e.getMessage(), e);
            return List.of();
        }
    }

    private static String must(Map<String, String> cfg, String key) {
        return Optional.ofNullable(cfg.get(key)).filter(v -> !v.isBlank()).orElseThrow(() -> new IllegalArgumentException("Missing required options: " + key));
    }

    private static String mustNotBlank(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("ProviderConfig." + name + " must not be blank");
        }
        return v;
    }

    /**
     * 去空白、去 null、保序去重；返回不可变数组。
     */
    private static String[] normalize(String[] arr) {
        if (arr == null || arr.length == 0) return new String[0];
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : arr) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) set.add(t);
        }
        return set.toArray(String[]::new);
    }
}