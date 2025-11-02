package com.ddm.chaos.demo;

import com.ddm.chaos.autoconfigure.SupplierAutoConfiguration;
import com.ddm.chaos.provider.JdbcDataProvider;
import com.ddm.chaos.supplier.DataSupplierFactory;
import com.ddm.chaos.supplier.DefaultDataSupplierFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * DemoBean 的 Spring Boot 集成测试。
 *
 * <p>该测试验证：
 * <ul>
 *   <li>Spring Boot 应用上下文能够正常启动</li>
 *   <li>DynamicSupplierAutoConfiguration 自动配置正常工作</li>
 *   <li>DemoBean 能够正确注入 Supplier</li>
 *   <li>Supplier 能够正常返回配置值</li>
 * </ul>
 */
@SpringBootTest(
        classes = {
                SupplierAutoConfiguration.class,
                DemoBean.class,
        }
)
@ComponentScan(basePackages = "com.ddm.chaos.demo")
@TestConfiguration
class DemoBeanTest {


    @Autowired
    private DemoBean demoBean;

    @Autowired
    private DataSupplierFactory dataSupplierFactory;


    /**
     * 在测试前准备数据库数据（备用方案，如果 TestConfiguration 中的数据初始化失败）。
     */
    @BeforeEach
    void setUp() {

        // 获取 JdbcTemplate 并插入测试数据
        if (dataSupplierFactory != null) {

            try {
                if (dataSupplierFactory instanceof DefaultDataSupplierFactory df) {
                    var p = df.getProvider();
                    if (p instanceof JdbcDataProvider jp) {
                        var dataSource = jp.getDataSource();
                        if (dataSource != null) {
                            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                            // 插入配置组（H2 使用 MERGE）
                            jdbc.update("MERGE INTO config_group (group_name, priority) KEY(group_name) VALUES (?, ?)",
                                    "default", 0);
                            // 获取组 ID
                            Long groupId = jdbc.queryForObject(
                                    "SELECT id FROM config_group WHERE group_name = ?", Long.class, "default");

                            if (groupId != null) {
                                // 插入测试配置数据（H2 使用 MERGE）
                                // 使用与 DemoBean 中 @Conf 注解匹配的 key
                                jdbc.update("MERGE INTO config_data (cfg_key, cfg_value, group_id, enabled) KEY(cfg_key, group_id) VALUES (?, ?, ?, ?)",
                                        "demo.name", "TestUser", groupId, true);
                                jdbc.update("MERGE INTO config_data (cfg_key, cfg_value, group_id, enabled) KEY(cfg_key, group_id) VALUES (?, ?, ?, ?)",
                                        "demo.age", "25", groupId, true);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    @Test
    void testDataSupplierFactory() {
        if (dataSupplierFactory instanceof DefaultDataSupplierFactory df) {
            df.refreshAll();
        }
        assertEquals("TestUser", demoBean.name().get(), "Name should match");
        assertEquals(25, demoBean.age().get(), "Age should match");
    }


}

