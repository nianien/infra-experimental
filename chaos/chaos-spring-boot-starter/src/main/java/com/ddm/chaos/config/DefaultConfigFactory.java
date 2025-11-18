package com.ddm.chaos.config;

import com.ddm.chaos.defined.ConfData;
import com.ddm.chaos.defined.ConfDesc;
import com.ddm.chaos.defined.ConfItem;
import com.ddm.chaos.defined.ConfRef;
import com.ddm.chaos.provider.DataProvider;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 默认的配置工厂实现，基于 Caffeine LoadingCache + 异步刷新。
 */
public final class DefaultConfigFactory implements ConfigFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigFactory.class);

    /**
     * 缺省哨兵（负缓存标记：表示 provider 没有这个 key）
     */
    private static final ConfData MISSING_DATA = new ConfData("", "", new String[0]);

    private static final int REFRESH_POOL_SIZE = 2;

    private final ExecutorService refreshPool;
    private final LoadingCache<ConfRef, ConfData> cache;
    private final DataProvider provider;
    private final ConfigProperties props;

    public DefaultConfigFactory(DataProvider provider, ConfigProperties props) {
        Objects.requireNonNull(props.ttl(), "ttl required");
        Objects.requireNonNull(provider, "provider required");

        this.provider = provider;
        this.props = props;
        this.refreshPool = createRefreshExecutor();

        this.cache = Caffeine.newBuilder()
                .refreshAfterWrite(props.ttl())
                .executor(refreshPool)
                .build(this::loadData);

        log.info("Config factory initialized with provider '{}' (ttl={})",
                provider.getClass(), props.ttl());
    }

    /**
     * 加载配置（被 Caffeine 调用）。
     * 三种情况：
     * 1) item 存在 → 返回真实数据
     * 2) item 不存在 → 返回 MISSING_DATA（负缓存）
     * 3) provider 异常 → 抛异常（Caffeine 保留旧值）
     */
    private ConfData loadData(ConfRef ref) {
        log.debug("Loading config item: {}", ref);
        try {
            ConfItem item = provider.loadData(ref);
            if (item == null) {
                log.info("Config {} not found in provider, caching MISSING_DATA", ref);
                return MISSING_DATA;
            }

            log.trace("Loaded config {} = {}", ref, previewValue(item.value(), 100));

            return new ConfData(item, props.tags());

        } catch (Exception e) {
            log.error("Failed to load config {} from provider {}, keeping old value",
                    ref, provider.getClass().getSimpleName(), e);
            throw new IllegalStateException("Failed to load config " + ref, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> createSupplier(ConfDesc desc) {
        Objects.requireNonNull(desc, "desc");

        ConfRef ref = desc.ref();
        log.debug("Creating supplier for config: {} (type={})",
                ref, desc.type().getTypeName());

        return () -> {
            try {
                ConfData data = cache.get(ref);

                // 1) key 不存在（负缓存）
                if (data == MISSING_DATA) {
                    T def = (T) desc.defaultValue();
                    log.trace("Config {} missing, using default value", ref);
                    return def;
                }

                // 2) 正常解析
                T value = data.getValue(desc);
                log.trace("Retrieved config {} = {}", ref, value);
                return value;

            } catch (Exception e) {
                // 3) 真异常：cache loader/parse 异常
                T def = (T) desc.defaultValue();
                log.error("Config load error for {}, using default value", ref, e);
                return def;
            }
        };
    }

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
        log.info("Shutting down config factory (provider={})", provider.getClass());

        try {
            refreshPool.shutdownNow();
        } catch (Exception ignore) {
        }

        try {
            provider.close();
        } catch (Exception e) {
            log.warn("Failed to close provider {}", provider.getClass(), e);
        }
    }

    private static String previewValue(String v, int maxLength) {
        if (v == null) return "null";
        if (v.length() <= maxLength) return v;
        return v.substring(0, maxLength) + "...(truncated)";
    }
}