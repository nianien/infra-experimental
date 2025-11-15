package com.ddm.chaos.config;

import com.ddm.chaos.config.ConfigProperties.Provider;
import com.ddm.chaos.defined.ConfDesc;
import com.ddm.chaos.defined.ConfRef;
import com.ddm.chaos.defined.ConfItem;
import com.ddm.chaos.defined.ConfData;
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
 * 默认的配置工厂实现，使用 Caffeine 缓存和异步刷新机制。
 * <p>
 * 该实现提供了以下功能：
 * <ul>
 *   <li><strong>配置缓存</strong>：使用 Caffeine LoadingCache 缓存配置数据，缓存键为 {@link ConfRef}</li>
 *   <li><strong>异步刷新</strong>：配置数据在 TTL 到期后异步刷新，读路径始终返回旧值，保证可用性</li>
 *   <li><strong>类型转换</strong>：支持将配置值转换为任意类型，转换结果在 {@link ConfData} 内部缓存</li>
 *   <li><strong>SPI 加载</strong>：通过 SPI 机制动态加载 {@link DataProvider} 实现</li>
 * </ul>
 *
 * <p><strong>缓存策略：</strong>
 * <ul>
 *   <li>首次未命中：同步加载（阻塞当前调用线程一次）</li>
 *   <li>到期刷新：异步在后台执行，读路径始终返回旧值</li>
 *   <li>刷新失败：永远回退旧值（旧值不失效）</li>
 * </ul>
 *
 * @author liyifei
 * @see ConfigFactory
 * @see DataProvider
 * @see ConfData
 * @since 1.0
 */
public final class DefaultConfigFactory implements ConfigFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigFactory.class);

    /**
     * 仅用于异步刷新（reload）的执行器；load 仍在调用线程中同步执行
     */
    private final ExecutorService refreshPool;

    /**
     * 配置数据缓存，键为配置引用（ConfRef），值为配置数据（ConfData）
     */
    private final LoadingCache<ConfRef, ConfData> cache;

    /**
     * 数据提供者，用于从数据源加载配置数据
     */
    private final DataProvider provider;
    
    /**
     * 配置属性
     */
    private final ConfigProperties props;

    /**
     * 刷新线程池大小
     */
    private static final int REFRESH_POOL_SIZE = 2;

    /**
     * 构造配置工厂实例。
     * <p>
     * 初始化过程：
     * <ol>
     *   <li>通过 SPI 机制加载并初始化指定的 DataProvider</li>
     *   <li>创建用于异步刷新的线程池</li>
     *   <li>初始化 Caffeine 缓存，配置 TTL 和刷新策略</li>
     * </ol>
     *
     * @param props 配置属性，不能为 null，必须包含有效的 ttl 和 provider
     * @throws NullPointerException  如果 props 或其必需字段为 null
     * @throws IllegalStateException 如果无法加载指定的 DataProvider
     */
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


    /**
     * 从数据提供者加载配置数据。
     * <p>
     * 该方法由 Caffeine 缓存调用，用于加载或刷新配置数据。
     * 如果加载失败，会抛出异常，Caffeine 会保留旧值。
     *
     * @param ref 配置引用，包含 namespace、group、key
     * @return 配置数据，包含已解析的配置值和类型转换缓存
     * @throws IllegalStateException 如果数据提供者返回 null 或加载失败
     */
    private ConfData loadData(ConfRef ref) {
        log.debug("Loading config item: {}", ref);
        try {
            ConfItem item = provider.loadData(ref);
            if (item == null) {
                String message = String.format("DataProvider '%s' returned null ConfigItem for %s", 
                        provider.type(), ref);
                log.error(message);
                throw new IllegalStateException(message);
            }
            log.trace("Successfully loaded config item: {} (value length: {})", 
                    ref, item.value() != null ? item.value().length() : 0);
            return new ConfData(item, props.tags());
        } catch (IllegalStateException e) {
            // 重新抛出 IllegalStateException，不包装
            throw e;
        } catch (Exception e) {
            log.error("Failed to load config item: {} (provider: {})", ref, provider.type(), e);
            throw new IllegalStateException(
                    String.format("Failed to load config item: %s (provider: %s)", ref, provider.type()), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> createSupplier(ConfDesc desc) {
        Objects.requireNonNull(desc, "desc");
        ConfRef ref = desc.ref();
        log.debug("Creating supplier for config: {} (type: {})", ref, desc.type().getTypeName());
        return () -> {
            try {
                ConfData data = cache.get(ref);
                T value = (T) data.getValue(desc);
                log.trace("Retrieved config value for {}: {}", ref, value);
                return value;
            } catch (IllegalStateException e) {
                // 缓存加载失败，记录错误并返回默认值
                log.error("Failed to load config from cache for {}, returning default value", ref, e);
                return (T) desc.defaultValue();
            } catch (Exception e) {
                // 其他异常（如类型转换失败），记录警告并返回默认值
                log.warn("Failed to get config value for {}, returning default value. Error: {}", 
                        ref, e.getMessage());
                return (T) desc.defaultValue();
            }
        };
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

    /**
     * 关闭配置工厂，释放相关资源。
     * <p>
     * 该方法会：
     * <ul>
     *   <li>关闭刷新线程池</li>
     *   <li>关闭数据提供者</li>
     * </ul>
     * 通常在 Spring 容器销毁时自动调用（通过 {@code @PreDestroy} 注解）。
     */
    @PreDestroy
    @Override
    public void close() {
        log.info("Shutting down config factory (provider: {})", provider.type());
        try {
            refreshPool.shutdownNow();
            log.debug("Refresh pool shutdown completed");
        } catch (Exception e) {
            log.warn("Error while shutting down refresh pool", e);
        }
        
        try {
            provider.close();
            log.debug("DataProvider '{}' closed successfully", provider.type());
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
            try {
                foundProvider.init(pCfg.options());
                log.info("DataProvider '{}' initialized successfully via SPI", type);
                return foundProvider;
            } catch (Exception e) {
                String message = String.format("Failed to initialize DataProvider '%s'", type);
                log.error(message, e);
                throw new IllegalStateException(message, e);
            }
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


