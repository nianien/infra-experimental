package com.ddm.chaos.factory;

import com.ddm.chaos.defined.ConfDesc;

import java.util.function.Supplier;

/**
 * 配置数据的 Supplier 工厂接口。
 *
 * @author liyifei
 * @since 1.0
 */
public interface ConfigFactory extends AutoCloseable {

    /**
     * 为指定的配置描述符创建类型化的 Supplier。
     *
     * @param <T>  目标类型
     * @param desc 配置描述符
     * @return 类型化的 Supplier 实例
     */
    <T> Supplier<T> createSupplier(ConfDesc desc);

    /**
     * 关闭工厂，释放相关资源。
     */
    @Override
    default void close() {
        // 默认无操作，由具体实现类重写
    }
}
