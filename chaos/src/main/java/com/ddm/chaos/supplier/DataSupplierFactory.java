package com.ddm.chaos.supplier;

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
 * Supplier<String> nameSupplier = factory.getSupplier("app.name", String.class);
 * String appName = nameSupplier.get();
 * 
 * // 获取 Integer 类型的配置
 * Supplier<Integer> portSupplier = factory.getSupplier("app.port", Integer.class);
 * Integer port = portSupplier.get();
 * }</pre>
 * 
 * <p><strong>设计约束：</strong>
 * <ul>
 *   <li>🚫 不负责读取配置文件（由配置属性类负责）</li>
 *   <li>🚫 不负责连接外部数据源（由 DataProvider 负责）</li>
 *   <li>✅ 只负责数据的缓存、转换和提供</li>
 * </ul>
 * 
 * @author liyifei
 * @since 1.0
 * @see com.ddm.chaos.supplier.DefaultDataSupplierFactory
 */
public interface DataSupplierFactory extends AutoCloseable {

    /**
     * 根据配置键和目标类型获取对应的 Supplier。
     * 
     * <p>该方法返回的 Supplier 具有以下特性：
     * <ul>
     *   <li><strong>永不返回 null</strong>：该方法本身不会返回 null</li>
     *   <li><strong>get() 可能返回 null</strong>：如果配置不存在或转换失败，get() 返回 null</li>
     *   <li><strong>线程安全</strong>：返回的 Supplier 可以安全地在多线程环境中使用</li>
     *   <li><strong>类型安全</strong>：自动将原始值转换为目标类型</li>
     * </ul>
     * 
     * <p><strong>类型转换支持：</strong>
     * <ul>
     *   <li>基础数值类型：Byte、Short、Integer、Long、Float、Double</li>
     *   <li>大数类型：BigInteger、BigDecimal</li>
     *   <li>时间类型：Duration、Instant、LocalDate、LocalDateTime 等</li>
     *   <li>字符串类型：String</li>
     *   <li>JSON 对象：通过 Jackson 反序列化为 POJO</li>
     * </ul>
     * 
     * @param <T> 目标类型
     * @param key 唯一配置键（对应配置数据中的 key）
     * @param targetType 希望返回的类型（如 String.class、Integer.class、自定义 POJO.class 等）
     * @return 可安全调用的 Supplier&lt;T&gt;，永不返回 null
     *         调用其 get() 方法获取配置值，如果配置不存在或转换失败则返回 null
     * @throws NullPointerException 如果 key 或 targetType 为 null
     */
    <T> Supplier<T> getSupplier(String key, Class<T> targetType);

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
