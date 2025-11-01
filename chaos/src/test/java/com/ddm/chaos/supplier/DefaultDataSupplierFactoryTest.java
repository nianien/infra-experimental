package com.ddm.chaos.supplier;

import com.ddm.chaos.provider.DataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultDataSupplierFactory} 类的单元测试。
 * 
 * @author liyifei
 */
class DefaultDataSupplierFactoryTest {

    private DataProvider mockProvider;
    private DefaultDataSupplierFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        mockProvider = mock(DataProvider.class);
        factory = new DefaultDataSupplierFactory(mockProvider);
    }

    @Test
    void testGetSupplier_StringType() throws Exception {
        // 准备测试数据
        Map<String, Object> config = new HashMap<>();
        config.put("app.name", "MyApp");
        when(mockProvider.loadAll()).thenReturn(config);

        // 启动工厂
        factory.setRefreshInterval(Duration.ofSeconds(0)); // 禁用定时刷新
        factory.startRefresh();

        // 获取 Supplier 并验证
        Supplier<String> supplier = factory.getSupplier("app.name", String.class);
        assertNotNull(supplier);
        assertEquals("MyApp", supplier.get());
    }

    @Test
    void testGetSupplier_IntegerType() throws Exception {
        // 准备测试数据
        Map<String, Object> config = new HashMap<>();
        config.put("app.port", "8080");
        when(mockProvider.loadAll()).thenReturn(config);

        // 启动工厂
        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        // 获取 Supplier 并验证
        Supplier<Integer> supplier = factory.getSupplier("app.port", Integer.class);
        assertNotNull(supplier);
        assertEquals(8080, supplier.get());
    }

    @Test
    void testGetSupplier_MissingKey() throws Exception {
        // 准备测试数据（不包含目标 key）
        Map<String, Object> config = new HashMap<>();
        config.put("other.key", "value");
        when(mockProvider.loadAll()).thenReturn(config);

        // 启动工厂
        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        // 获取不存在的配置，应该返回 null
        Supplier<String> supplier = factory.getSupplier("missing.key", String.class);
        assertNotNull(supplier);
        assertNull(supplier.get());
    }

    @Test
    void testGetSupplier_TypeConversion() throws Exception {
        // 准备测试数据
        Map<String, Object> config = new HashMap<>();
        config.put("app.timeout", "30s");
        when(mockProvider.loadAll()).thenReturn(config);

        // 启动工厂
        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        // 测试 Duration 类型转换
        Supplier<Duration> supplier = factory.getSupplier("app.timeout", Duration.class);
        assertNotNull(supplier);
        Duration duration = supplier.get();
        assertNotNull(duration);
        assertEquals(Duration.ofSeconds(30), duration);
    }

    @Test
    void testGetSupplier_RefreshUpdatesCache() throws Exception {
        // 准备初始数据
        Map<String, Object> initialConfig = new HashMap<>();
        initialConfig.put("app.version", "1.0");
        
        // 准备刷新后的数据
        Map<String, Object> updatedConfig = new HashMap<>();
        updatedConfig.put("app.version", "2.0");

        when(mockProvider.loadAll())
            .thenReturn(initialConfig)
            .thenReturn(updatedConfig);

        // 启动工厂
        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        // 获取初始值
        Supplier<String> supplier = factory.getSupplier("app.version", String.class);
        assertEquals("1.0", supplier.get());

        // 手动触发刷新（通过反射调用私有方法）
        java.lang.reflect.Method refreshMethod = DefaultDataSupplierFactory.class
                .getDeclaredMethod("refreshAll");
        refreshMethod.setAccessible(true);
        refreshMethod.invoke(factory);

        // 验证值已更新
        assertEquals("2.0", supplier.get());
    }

    @Test
    void testGetSupplier_NullKeyThrowsException() {
        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        assertThrows(NullPointerException.class, () -> {
            factory.getSupplier(null, String.class);
        });
    }

    @Test
    void testGetSupplier_NullTypeThrowsException() {
        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        assertThrows(NullPointerException.class, () -> {
            factory.getSupplier("key", null);
        });
    }

    @Test
    void testStartRefresh_CallsProviderLoadAll() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("test.key", "test.value");
        when(mockProvider.loadAll()).thenReturn(config);

        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        verify(mockProvider, atLeastOnce()).loadAll();
    }

    @Test
    void testStartRefresh_WithScheduler() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("test.key", "test.value");
        when(mockProvider.loadAll()).thenReturn(config);

        // 设置很短的刷新间隔用于测试
        factory.setRefreshInterval(Duration.ofMillis(100));
        factory.startRefresh();

        // 等待一段时间，让调度器执行
        Thread.sleep(250);

        // 验证 loadAll 被调用了至少两次（启动时一次 + 定时刷新至少一次）
        verify(mockProvider, atLeast(2)).loadAll();
    }

    @Test
    void testStartRefresh_Idempotent() throws Exception {
        Map<String, Object> config = new HashMap<>();
        when(mockProvider.loadAll()).thenReturn(config);

        factory.setRefreshInterval(Duration.ofSeconds(60));
        factory.startRefresh();
        factory.startRefresh(); // 再次调用

        // 验证调度器只创建了一次
        verify(mockProvider, times(1)).loadAll();
    }

    @Test
    void testRefreshAll_ConcurrentRefresh() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("test.key", "value");
        when(mockProvider.loadAll()).thenReturn(config);

        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        // 模拟并发刷新（实际上会因为 tryLock 失败而跳过）
        java.lang.reflect.Method refreshMethod = DefaultDataSupplierFactory.class
                .getDeclaredMethod("refreshAll");
        refreshMethod.setAccessible(true);
        
        CountDownLatch latch = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            new Thread(() -> {
                try {
                    refreshMethod.invoke(factory);
                } catch (Exception e) {
                    // 忽略异常
                }
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        // 由于 tryLock 的保护，应该不会出现并发问题
    }

    @Test
    void testRefreshAll_EmptyDataKeepsPrevious() throws Exception {
        // 初始数据
        Map<String, Object> initialConfig = new HashMap<>();
        initialConfig.put("app.name", "MyApp");
        when(mockProvider.loadAll())
            .thenReturn(initialConfig)
            .thenReturn(Map.of()); // 返回空数据

        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        Supplier<String> supplier = factory.getSupplier("app.name", String.class);
        assertEquals("MyApp", supplier.get());

        // 刷新时返回空数据，应该保留旧数据
        java.lang.reflect.Method refreshMethod = DefaultDataSupplierFactory.class
                .getDeclaredMethod("refreshAll");
        refreshMethod.setAccessible(true);
        refreshMethod.invoke(factory);
        
        assertEquals("MyApp", supplier.get()); // 仍然返回旧值
    }

    @Test
    void testRefreshAll_ProviderExceptionKeepsPrevious() throws Exception {
        // 初始数据
        Map<String, Object> initialConfig = new HashMap<>();
        initialConfig.put("app.name", "MyApp");
        when(mockProvider.loadAll())
            .thenReturn(initialConfig)
            .thenThrow(new RuntimeException("Provider error")); // 抛出异常

        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        Supplier<String> supplier = factory.getSupplier("app.name", String.class);
        assertEquals("MyApp", supplier.get());

        // 刷新时抛出异常，应该保留旧数据
        java.lang.reflect.Method refreshMethod = DefaultDataSupplierFactory.class
                .getDeclaredMethod("refreshAll");
        refreshMethod.setAccessible(true);
        refreshMethod.invoke(factory);
        
        assertEquals("MyApp", supplier.get()); // 仍然返回旧值
    }

    @Test
    void testClose_ShutsDownScheduler() throws Exception {
        Map<String, Object> config = new HashMap<>();
        when(mockProvider.loadAll()).thenReturn(config);

        factory.setRefreshInterval(Duration.ofMillis(50));
        factory.startRefresh();

        // 等待一段时间让调度器运行
        Thread.sleep(100);

        // 验证 loadAll 已被调用（至少启动时调用了一次）
        verify(mockProvider, atLeastOnce()).loadAll();
        
        // 记录当前调用次数
        int callCountBeforeClose = (int) org.mockito.Mockito.mockingDetails(mockProvider)
                .getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("loadAll"))
                .count();

        // 关闭工厂
        factory.close();

        // 再等待一段时间，验证调度器已停止（不应该再有新的调用）
        Thread.sleep(300);
        
        // 验证调用次数没有增加（调度器已停止）
        int callCountAfterClose = (int) org.mockito.Mockito.mockingDetails(mockProvider)
                .getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("loadAll"))
                .count();
        
        assertEquals(callCountBeforeClose, callCountAfterClose, 
                "Scheduler should be stopped, no more calls should be made");
    }

    @Test
    void testClose_ClosesProvider() throws Exception {
        Map<String, Object> config = new HashMap<>();
        when(mockProvider.loadAll()).thenReturn(config);

        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();
        factory.close();

        verify(mockProvider).close();
    }

    @Test
    void testClose_Idempotent() throws Exception {
        Map<String, Object> config = new HashMap<>();
        when(mockProvider.loadAll()).thenReturn(config);

        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();
        factory.close();
        factory.close(); // 再次调用

        // close() 方法每次都会调用 provider.close()，这是正常的
        // 因为 provider.close() 应该也是幂等的
        verify(mockProvider, atLeastOnce()).close();
    }

    @Test
    void testMultipleTypes_SameKey() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("app.port", "8080");
        when(mockProvider.loadAll()).thenReturn(config);

        factory.setRefreshInterval(Duration.ofSeconds(0));
        factory.startRefresh();

        // 同一 key，不同类型，应该都正确转换
        Supplier<String> stringSupplier = factory.getSupplier("app.port", String.class);
        Supplier<Integer> intSupplier = factory.getSupplier("app.port", Integer.class);

        assertEquals("8080", stringSupplier.get());
        assertEquals(8080, intSupplier.get());
    }
}

