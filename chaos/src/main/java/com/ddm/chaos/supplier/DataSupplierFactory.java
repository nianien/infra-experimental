package com.ddm.chaos.supplier;

import java.util.function.Supplier;

/**
 * DataSupplierFactory —— Supplier 的统一生产工厂。
 * <p>
 * 职责：
 * 1. 管理缓存、刷新策略；
 * 2. 基于 DataProvider 的全量快照，生成 Supplier<T>；
 * 3. 对外提供按 key/type 获取数据的统一入口；
 * 4. 生命周期由外部控制（AutoConfiguration 负责构造与关闭）。
 * <p>
 * 🚫 不负责读取配置文件，不负责连接外部数据源。
 */
public interface DataSupplierFactory extends AutoCloseable {

    /**
     * 获取一个可用的 Supplier。
     *
     * @param key        唯一配置键（对应 config_data.config_key）
     * @param targetType 希望返回的类型（如 String、Integer、POJO 等）
     * @return 可安全调用的 Supplier（永不为 null，get() 可返回 null）
     */
    <T> Supplier<T> getSupplier(String key, Class<T> targetType);

    /**
     * 生命周期结束时关闭资源（如线程池、定时任务等）。
     */
    @Override
    default void close() {
        // 默认无操作，由具体实现类重写
    }
}