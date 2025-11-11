package com.ddm.chaos.config;

import com.ddm.chaos.defined.ConfDesc;

import java.util.function.Supplier;

/**
 * Supplier 的统一生产工厂接口。
 *
 * <p>该接口定义了配置数据的 Supplier 生成和管理能力，主要职责包括：
 * <ol>
 *   <li><strong>管理缓存和刷新策略</strong>：维护配置数据的缓存，支持定时刷新</li>
 *   <li><strong>生成类型化的 Supplier</strong>：基于 DataProvider 的全量快照，生成强类型的 Supplier&lt;T&gt;</li>
 *   <li><strong>提供统一的数据访问入口</strong>：通过 key 和 targetType 获取对应的 Supplier</li>
 *   <li><strong>生命周期管理</strong>：由外部（如 AutoConfiguration）控制构造与关闭</li>
 * </ol>
 *
 * <p><strong>使用示例：</strong>
 * <pre>{@code
 * // 获取 String 类型的配置
 * Supplier<String> nameSupplier = factory.getSupplier(
 *     TypedKey.of("app.name", String.class, "defaultName"));
 * String appName = nameSupplier.get();
 *
 * // 获取 Integer 类型的配置
 * Supplier<Integer> portSupplier = factory.getSupplier(
 *     TypedKey.of("app.port", Integer.class, "8080"));
 * Integer port = portSupplier.get();
 * }</pre>
 *
 * <p><strong>设计约束：</strong>
 * <ul>
 *   <li>不负责读取配置文件（由配置属性类负责）</li>
 *   <li>不负责连接外部数据源（由 DataProvider 负责）</li>
 *   <li>只负责数据的缓存、转换和提供</li>
 * </ul>
 *
 * @author liyifei
 * @see DefaultConfigFactory
 * @since 1.0
 */
public interface ConfigFactory extends AutoCloseable {


    <T> Supplier<T> createSupplier(ConfDesc desc);

    /**
     * 关闭工厂，释放相关资源。
     *
     * <p>该方法在工厂不再使用时调用，用于：
     * <ul>
     *   <li>停止定时刷新任务</li>
     *   <li>关闭线程池</li>
     *   <li>关闭底层 DataProvider</li>
     * </ul>
     *
     * <p>默认实现为空操作（no-op），实现类应根据需要重写以释放资源。
     * 通常在 Spring 容器销毁时自动调用。
     */
    @Override
    default void close() {
        // 默认无操作，由具体实现类重写
    }
}
