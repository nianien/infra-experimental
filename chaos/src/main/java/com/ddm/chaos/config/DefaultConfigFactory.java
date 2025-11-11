package com.ddm.chaos.config;

import com.ddm.chaos.config.ConfigProperties.Provider;
import com.ddm.chaos.defined.ConfDesc;
import com.ddm.chaos.defined.ConfInfo;
import com.ddm.chaos.provider.ConfItem;
import com.ddm.chaos.provider.DataProvider;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 同步版缓存：
 * - 使用 Caffeine LoadingCache + refreshAfterWrite(ttl)
 * - 首次未命中：同步加载（阻塞当前调用线程一次）
 * - 到期刷新：异步在后台执行，读路径始终返回旧值
 * - 刷新失败：永远回退旧值（旧值不失效）
 */
public final class DefaultConfigFactory implements ConfigFactory {

    /**
     * 仅用于异步刷新（reload）的执行器；load 仍在调用线程中同步执行
     */
    private final ExecutorService refreshPool;

    private final LoadingCache<ConfInfo, ConfigData> cache;

    private final DataProvider provider;
    private final ConfigProperties props;

    /**
     * 刷新线程池大小
     */
    private static final int REFRESH_POOL_SIZE = 2;

    public DefaultConfigFactory(ConfigProperties props) {
        Objects.requireNonNull(props.ttl(), "ttl");
        Objects.requireNonNull(props.provider(), "provider");
        // 专用于 refresh 的后台线程（daemon）
        this.provider = loadDataProvider(props.provider());
        this.props = props;
        this.refreshPool = createRefreshExecutor();

        this.cache = Caffeine.newBuilder()
                // 读到期触发异步 reload，读路径返回旧值
                .refreshAfterWrite(props.ttl())
                // 仅影响 reload 的执行线程
                .executor(this.refreshPool)
                .build(this::loadData);
    }


    private ConfigData loadData(ConfInfo info) {
        ConfItem item = provider.loadData(info);
        return new ConfigData(item, props.tags());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> createSupplier(ConfDesc desc) {
        Objects.requireNonNull(desc, "desc");
        return () -> (T) cache.get(desc.info()).getValue(desc);
    }

    /**
     * 创建用于异步刷新的执行器。
     *
     * @return 守护线程执行器
     */
    private static ExecutorService createRefreshExecutor() {
        return Executors.newFixedThreadPool(REFRESH_POOL_SIZE, r -> {
            Thread t = new Thread(r, "config-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    @Override
    public void close() {
        if (refreshPool != null) {
            refreshPool.shutdownNow();
        }
        if (provider != null) {
            try {
                provider.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 通过 SPI 机制加载指定类型的 DataProvider。
     *
     * @param pCfg Provider 类型（不区分大小写），不能为 null
     * @return 对应的 DataProvider 实例，不会为 null
     * @throws IllegalStateException 如果找不到指定类型的 DataProvider
     */
    private static DataProvider loadDataProvider(Provider pCfg) {
        String type = pCfg.type();
        Objects.requireNonNull(type, "provider type cannot be null");
        ServiceLoader<DataProvider> loader =
                ServiceLoader.load(DataProvider.class, Thread.currentThread().getContextClassLoader());
        List<String> availableTypes = new ArrayList<>();
        DataProvider foundProvider = null;
        for (DataProvider provider : loader) {
            String providerType = provider.type();
            if (type.equalsIgnoreCase(providerType)) {
                foundProvider = provider;
                break;
            }
            availableTypes.add(providerType);
        }
        if (foundProvider != null) {
            foundProvider.init(pCfg.options());
            return foundProvider;
        }
        String typesList = availableTypes.isEmpty()
                ? "none"
                : String.join(", ", availableTypes);
        throw new IllegalStateException(
                String.format("No DataProvider found via SPI for type '%s'. Available types: %s",
                        type, typesList));
    }
}


