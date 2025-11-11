package com.ddm.chaos.config;

import com.ddm.chaos.config.ConfigProperties.Provider;
import com.ddm.chaos.defined.ConfDesc;
import com.ddm.chaos.defined.ConfRef;
import com.ddm.chaos.provider.ConfItem;
import com.ddm.chaos.provider.DataProvider;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 默认的配置工厂实现。
 * <p>
 * 责任：
 * <ul>
 *   <li>基于 {@link DataProvider} 拉取配置快照</li>
 *   <li>借助 Caffeine 缓存配置结果，并在 TTL 到期后异步刷新</li>
 *   <li>为 {@code @Conf} 注入点创建类型安全的 {@link Supplier}</li>
 * </ul>
 */
public final class DefaultConfigFactory implements ConfigFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigFactory.class);

    /**
     * 仅用于异步刷新（reload）的执行器；load 仍在调用线程中同步执行
     */
    private final ExecutorService refreshPool;

    private final LoadingCache<ConfRef, ConfigData> cache;

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

        log.info("Config factory initialized with provider '{}' (ttl={})",
                provider.type(), props.ttl());
    }


    private ConfigData loadData(ConfRef ref) {
        log.debug("Loading config item {}", ref);
        ConfItem item = provider.loadData(ref);
        if (item == null) {
            throw new IllegalStateException("Provider returned null ConfigItem for " + ref);
        }
        return new ConfigData(item, props.tags());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> createSupplier(ConfDesc desc) {
        Objects.requireNonNull(desc, "desc");
        return () -> (T) cache.get(desc.ref()).getValue(desc);
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
        log.info("Shutting down config factory");
        refreshPool.shutdownNow();
        try {
            provider.close();
        } catch (Exception e) {
            log.warn("Failed to close provider '{}'", provider.type(), e);
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
            log.info("DataProvider '{}' initialized via SPI", type);
            return foundProvider;
        }
        String typesList = availableTypes.isEmpty()
                ? "none"
                : String.join(", ", availableTypes);
        String message = String.format("No DataProvider found via SPI for type '%s'. Available types: %s",
                type, typesList);
        log.error(message);
        throw new IllegalStateException(message);
    }
}


