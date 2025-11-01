package com.ddm.chaos.supplier;

import java.util.Map;

/**
 * 只负责“拉取全量配置”的数据源抽象（DB/Redis/HTTP...）
 */
public interface DataProvider extends AutoCloseable {

    /**
     * 用配置初始化数据源（连接、客户端等）
     */
    void initialize(Map<String, String> config) throws Exception;

    /**
     * 拉取全量配置快照：key -> raw value（字符串/JSON/数字…）
     */
    Map<String, Object> loadAll() throws Exception;

    @Override
    default void close() throws Exception { /* no-op */ }
}