package com.ddm.chaos.demo;

import com.ddm.chaos.SpringTestApplication;
import com.ddm.chaos.autoconfigure.ChaosAutoConfiguration;
import com.ddm.chaos.config.ConfigFactory;
import com.ddm.chaos.config.DefaultConfigFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                ChaosAutoConfiguration.class,
                DemoBean.class,
                SpringTestApplication.class
        }
)
@ComponentScan(basePackages = "com.ddm.chaos.demo")
@TestConfiguration
@Sql(scripts = {"/data-h2.sql"}) // 测试启动前建表
class DemoBeanTest {


    @Autowired
    private DemoBean demoBean;

    @Autowired
    private ConfigFactory dataSupplierFactory;


    @Test
    void testDataSupplierFactory() {
        if (dataSupplierFactory instanceof DefaultConfigFactory df) {
            df.refresh();
        }

        assertEquals("TestUser", demoBean.name.get(), "Name should match");
        assertEquals(25, demoBean.age.get(), "Age should match");
    }


}

