package com.ddm.chaos.demo;

import com.ddm.chaos.autoconfigure.SupplierAutoConfiguration;
import com.ddm.chaos.provider.DataProvider;
import com.ddm.chaos.supplier.DataSupplierFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

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
@SpringBootTest(classes = {
        SupplierAutoConfiguration.class,
        DemoBean.class
})
@ComponentScan(basePackages = "com.ddm.chaos.demo")
@TestPropertySource(properties = {
        "chaos.supplier.provider=com.ddm.chaos.demo.DemoBeanTest$TestDataProvider",
        "chaos.supplier.ttl=30s",
        "chaos.supplier.config.dummy=test"
})
class DemoBeanTest {

    @Autowired
    private DemoBean demoBean;

    @Autowired
    private DataSupplierFactory dataSupplierFactory;

    @Test
    void testDemoBeanInjection() {
        // 验证 DemoBean 已正确注入
        assertNotNull(demoBean, "DemoBean should be injected");
    }

    @Test
    void testSupplierValues() {
        // 验证 Supplier 能够返回正确的配置值
        assertNotNull(demoBean, "DemoBean should be injected");
        
        // 调用 doSomething() 验证 Supplier 能正常工作
        assertDoesNotThrow(() -> demoBean.doSomething(), "doSomething should not throw exception");
        
        // 直接验证 Supplier 返回的值
        String name = demoBean.name.get();
        Integer age = demoBean.age.get();
        
        assertEquals("TestUser", name, "Name should match configured value");
        assertEquals(25, age, "Age should match configured value");
    }

    @Test
    void testDataSupplierFactory() {
        // 验证 DataSupplierFactory 已正确创建
        assertNotNull(dataSupplierFactory, "DataSupplierFactory should be created");
        
        // 验证可以通过工厂获取 Supplier
        Supplier<String> nameSupplier = dataSupplierFactory.getSupplier("com.dd.demo.name", String.class);
        Supplier<Integer> ageSupplier = dataSupplierFactory.getSupplier("com.dd.demo.age", Integer.class);
        
        assertNotNull(nameSupplier, "Name supplier should not be null");
        assertNotNull(ageSupplier, "Age supplier should not be null");
        
        assertEquals("TestUser", nameSupplier.get(), "Name should match");
        assertEquals(25, ageSupplier.get(), "Age should match");
    }


    /**
     * 测试用的 DataProvider 实现。
     * 
     * <p>使用内存 Map 存储配置数据，支持 DemoBean 测试。
     * 
     * <p>注意：必须是 public static 类，以便 ServiceLoader 能够实例化。
     */
    public static class TestDataProvider implements DataProvider {
        
        private Map<String, Object> data = new HashMap<>();

        @Override
        public void initialize(Map<String, String> cfg) {
            // 初始化测试数据
            data.put("com.dd.demo.name", "TestUser");
            data.put("com.dd.demo.age", "25");
        }

        @Override
        public Map<String, Object> loadAll() {
            return new HashMap<>(data);
        }

        @Override
        public void close() {
            data.clear();
        }
    }
}

