package com.ddm.chaos.provider.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcDataProvider} 类的单元测试。
 * 
 * @author liyifei
 */
class JdbcDataProviderTest {

    private JdbcDataProvider provider;
    private String dbUrl;

    @BeforeEach
    void setUp() {
        // 每个测试使用独立的数据库实例，避免数据冲突
        String uniqueDbName = "testdb_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
        dbUrl = "jdbc:h2:mem:" + uniqueDbName + ";DB_CLOSE_DELAY=-1";
        
        provider = new JdbcDataProvider();
        
        // 初始化 provider，启用表创建
        Map<String, String> config = new HashMap<>();
        config.put("url", dbUrl);
        config.put("username", "sa");
        config.put("password", "");
        config.put("groups", "default,prod");
        config.put("init_sql", "true");  // 启用表创建
        
        assertDoesNotThrow(() -> provider.initialize(config));
        // 表结构已通过 ensureTables() 自动创建，测试中可以直接插入数据
    }
    
    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        if (provider != null) {
            provider.close();
        }
    }


    @Test
    void testInitialize_WithValidConfig() throws Exception {
        JdbcDataProvider testProvider = new JdbcDataProvider();
        Map<String, String> config = new HashMap<>();
        config.put("url", "jdbc:h2:mem:test");
        config.put("username", "sa");
        config.put("password", "");
        
        testProvider.initialize(config);
        testProvider.close();
    }

    @Test
    void testInitialize_MissingUrl() {
        JdbcDataProvider testProvider = new JdbcDataProvider();
        Map<String, String> config = new HashMap<>();
        config.put("username", "sa");
        
        assertThrows(IllegalArgumentException.class, () -> {
            testProvider.initialize(config);
        });
    }

    @Test
    void testInitialize_WithGroups() throws Exception {
        JdbcDataProvider testProvider = new JdbcDataProvider();
        Map<String, String> config = new HashMap<>();
        config.put("url", "jdbc:h2:mem:test");
        config.put("groups", "default,prod,test");
        
        testProvider.initialize(config);
        testProvider.close();
    }

    @Test
    void testLoadAll_Basic() throws Exception {
        // 表结构已在 setUp() 中通过 provider.initialize() -> ensureTables() 自动创建
        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(dbUrl, "sa", ""));
        
        // 插入测试数据
        jdbc.update("INSERT INTO config_group (group_name, priority) VALUES (?, ?)", "default", 0);
        jdbc.update("INSERT INTO config_group (group_name, priority) VALUES (?, ?)", "prod", 10);
        
        Long defaultGroupId = jdbc.queryForObject(
                "SELECT id FROM config_group WHERE group_name = ?", Long.class, "default");
        Long prodGroupId = jdbc.queryForObject(
                "SELECT id FROM config_group WHERE group_name = ?", Long.class, "prod");
        
        jdbc.update("INSERT INTO config_data (cfg_key, cfg_value, group_id) VALUES (?, ?, ?)",
                "app.name", "MyApp", defaultGroupId);
        jdbc.update("INSERT INTO config_data (cfg_key, cfg_value, group_id) VALUES (?, ?, ?)",
                "app.port", "8080", prodGroupId);
        
        // 加载配置
        Map<String, Object> result = provider.loadAll();
        
        assertNotNull(result);
        assertTrue(result.containsKey("app.name"));
        assertTrue(result.containsKey("app.port"));
        assertEquals("MyApp", result.get("app.name"));
        assertEquals("8080", result.get("app.port"));
    }

    @Test
    void testLoadAll_PriorityResolution() throws Exception {
        // 表结构已自动创建
        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(dbUrl, "sa", ""));
        
        // 创建两个组，prod 的优先级更高
        jdbc.update("INSERT INTO config_group (group_name, priority) VALUES (?, ?)", "default", 0);
        jdbc.update("INSERT INTO config_group (group_name, priority) VALUES (?, ?)", "prod", 10);
        
        Long defaultGroupId = jdbc.queryForObject(
                "SELECT id FROM config_group WHERE group_name = ?", Long.class, "default");
        Long prodGroupId = jdbc.queryForObject(
                "SELECT id FROM config_group WHERE group_name = ?", Long.class, "prod");
        
        // 同一 key 在不同组中有不同的值
        jdbc.update("INSERT INTO config_data (cfg_key, cfg_value, group_id) VALUES (?, ?, ?)",
                "app.name", "DefaultApp", defaultGroupId);
        jdbc.update("INSERT INTO config_data (cfg_key, cfg_value, group_id) VALUES (?, ?, ?)",
                "app.name", "ProdApp", prodGroupId);
        
        // 加载配置，应该选择优先级更高的 prod 组的值
        Map<String, Object> result = provider.loadAll();
        
        assertNotNull(result);
        assertEquals("ProdApp", result.get("app.name"));
    }

    @Test
    void testLoadAll_ExcludeDisabled() throws Exception {
        // 表结构已自动创建
        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(dbUrl, "sa", ""));
        
        jdbc.update("INSERT INTO config_group (group_name, priority) VALUES (?, ?)", "default", 0);
        Long groupId = jdbc.queryForObject(
                "SELECT id FROM config_group WHERE group_name = ?", Long.class, "default");
        
        // 插入启用的配置
        jdbc.update("INSERT INTO config_data (cfg_key, cfg_value, enabled, group_id) VALUES (?, ?, ?, ?)",
                "app.enabled.key", "value1", true, groupId);
        // 插入禁用的配置
        jdbc.update("INSERT INTO config_data (cfg_key, cfg_value, enabled, group_id) VALUES (?, ?, ?, ?)",
                "app.disabled.key", "value2", false, groupId);
        
        // 加载配置，禁用的配置不应该被加载
        Map<String, Object> result = provider.loadAll();
        
        assertNotNull(result);
        assertTrue(result.containsKey("app.enabled.key"));
        assertFalse(result.containsKey("app.disabled.key"));
    }

    @Test
    void testLoadAll_EmptyGroups() throws Exception {
        JdbcDataProvider testProvider = new JdbcDataProvider();
        Map<String, String> config = new HashMap<>();
        config.put("url", "jdbc:h2:mem:test");
        
        testProvider.initialize(config);
        
        try {
            // 如果没有配置组，应该返回空 Map
            Map<String, Object> result = testProvider.loadAll();
            assertNotNull(result);
            assertTrue(result.isEmpty());
        } finally {
            testProvider.close();
        }
    }

    @Test
    void testLoadAll_NoMatchingGroups() throws Exception {
        // 表结构已自动创建
        JdbcTemplate jdbc = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(dbUrl, "sa", ""));
        
        // 创建其他组的配置
        jdbc.update("INSERT INTO config_group (group_name, priority) VALUES (?, ?)", "other", 0);
        Long groupId = jdbc.queryForObject(
                "SELECT id FROM config_group WHERE group_name = ?", Long.class, "other");
        jdbc.update("INSERT INTO config_data (cfg_key, cfg_value, group_id) VALUES (?, ?, ?)",
                "other.key", "value", groupId);
        
        // provider 配置的是 default 和 prod 组，不应该加载 other 组的配置
        Map<String, Object> result = provider.loadAll();
        
        assertNotNull(result);
        assertFalse(result.containsKey("other.key"));
    }

    @Test
    void testLoadAll_ExceptionHandling() throws Exception {
        // 使用无效的数据库连接，验证异常处理
        JdbcDataProvider testProvider = new JdbcDataProvider();
        Map<String, String> config = new HashMap<>();
        config.put("url", "jdbc:h2:mem:nonexistent;DB_CLOSE_DELAY=-1");
        config.put("groups", "default");
        
        testProvider.initialize(config);
        
        try {
            // 即使发生异常，也应该返回空 Map 而不是抛出异常
            Map<String, Object> result = testProvider.loadAll();
            assertNotNull(result);
        } finally {
            testProvider.close();
        }
    }

    @Test
    void testClose() throws Exception {
        assertDoesNotThrow(() -> provider.close());
    }

    @Test
    void testInitialize_WithInitSqlFalse() throws Exception {
        JdbcDataProvider testProvider = new JdbcDataProvider();
        String uniqueDbName = "testdb_init_false_" + System.currentTimeMillis();
        String testUrl = "jdbc:h2:mem:" + uniqueDbName + ";DB_CLOSE_DELAY=-1";
        
        // 设置 init_sql=false 或不设置，都应该跳过表创建（默认行为）
        Map<String, String> config = new HashMap<>();
        config.put("url", testUrl);
        config.put("username", "sa");
        config.put("password", "");
        config.put("groups", "default");
        config.put("init_sql", "false");
        
        testProvider.initialize(config);
        
        try {
            // 验证表没有被创建（表不存在会抛出异常）
            // 由于表没有被创建，loadAll 应该返回空 Map
            Map<String, Object> result = testProvider.loadAll();
            assertNotNull(result);
            assertTrue(result.isEmpty());
            
            // 尝试直接查询表，应该抛出异常（表不存在）
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(testUrl, "sa", ""));
            
            assertThrows(Exception.class, () -> {
                jdbc.queryForObject("SELECT COUNT(*) FROM config_group", Long.class);
            });
        } finally {
            testProvider.close();
        }
    }

    @Test
    void testInitialize_WithInitSqlTrue() throws Exception {
        JdbcDataProvider testProvider = new JdbcDataProvider();
        String uniqueDbName = "testdb_init_true_" + System.currentTimeMillis();
        String testUrl = "jdbc:h2:mem:" + uniqueDbName + ";DB_CLOSE_DELAY=-1";
        
        // 设置 init_sql=true，应该创建表
        Map<String, String> config = new HashMap<>();
        config.put("url", testUrl);
        config.put("username", "sa");
        config.put("password", "");
        config.put("groups", "default");
        config.put("init_sql", "true");
        
        testProvider.initialize(config);
        
        try {
            // 验证表已创建（可以查询）
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(testUrl, "sa", ""));
            
            // 应该不会抛出异常，表已存在
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM config_group", Long.class);
            assertNotNull(count);
        } finally {
            testProvider.close();
        }
    }

    @Test
    void testInitialize_WithoutInitSql() throws Exception {
        JdbcDataProvider testProvider = new JdbcDataProvider();
        String uniqueDbName = "testdb_no_init_" + System.currentTimeMillis();
        String testUrl = "jdbc:h2:mem:" + uniqueDbName + ";DB_CLOSE_DELAY=-1";
        
        // 不设置 init_sql，默认应该不创建表（默认值为 false）
        Map<String, String> config = new HashMap<>();
        config.put("url", testUrl);
        config.put("username", "sa");
        config.put("password", "");
        config.put("groups", "default");
        // 不设置 init_sql（默认行为是不创建表）
        
        testProvider.initialize(config);
        
        try {
            // 验证表没有被创建（默认行为）
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource(testUrl, "sa", ""));
            
            // 应该抛出异常，表不存在
            assertThrows(Exception.class, () -> {
                jdbc.queryForObject("SELECT COUNT(*) FROM config_group", Long.class);
            });
        } finally {
            testProvider.close();
        }
    }
}

