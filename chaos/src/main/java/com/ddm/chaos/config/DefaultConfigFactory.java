package com.ddm.chaos.config;

import com.ddm.chaos.config.ConfigProperties.Provider;
import com.ddm.chaos.provider.DataProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static com.ddm.chaos.utils.Converters.cast;

/**
 * {@link ConfigFactory} 的默认实现，采用两层缓存架构和惰性类型化策略。
 *
 * <p><strong>核心设计：</strong>
 * <ul>
 *   <li><strong>两层缓存架构</strong>：
 *     <ol>
 *       <li><strong>rawCache</strong>：保存原始配置值的全量快照，使用 {@code volatile} 引用实现无锁读取</li>
 *       <li><strong>typedCache</strong>：保存类型化转换结果，按需懒构建，避免不必要的类型转换</li>
 *     </ol>
 *   </li>
 *   <li><strong>写时复制（Copy-on-Write）</strong>：刷新时整体替换 {@code rawCache} 引用，保证读路径零锁</li>
 *   <li><strong>惰性类型化</strong>：只在第一次调用 {@code Supplier.get()} 时进行类型转换，后续直接返回缓存结果</li>
 *   <li><strong>可用性优先</strong>：刷新失败时保留旧数据，保证系统可用性，不会因为刷新失败导致服务不可用</li>
 * </ul>
 *
 * <p><strong>并发控制：</strong>
 * <ul>
 *   <li><strong>读路径</strong>：完全无锁，通过 {@code volatile} 引用保证可见性，性能优异</li>
 *   <li><strong>写路径</strong>：使用 {@code ReentrantLock.tryLock()} 防止并发刷新，避免数据不一致</li>
 *   <li><strong>类型转换</strong>：使用 {@code ConcurrentHashMap.computeIfAbsent()} 保证每个 {@code TypedKey} 仅转换一次</li>
 * </ul>
 *
 * <p><strong>刷新策略：</strong>
 * <ul>
 *   <li>启动时立即同步刷新一次，保证数据可用</li>
 *   <li>支持定时刷新，刷新间隔由 {@link ConfigProperties#ttl()} 配置</li>
 *   <li>刷新时先原子替换 {@code rawCache}，再清空 {@code typedCache}（不逐条清除，避免全表写入和竞争）</li>
 *   <li>刷新失败时保留旧镜像，不中断读路径，保证服务可用性</li>
 * </ul>
 *
 * <p><strong>使用示例：</strong>
 * <pre>{@code
 * // 通过 Spring Boot 自动配置使用（推荐）
 * @Autowired
 * private DataConfigFactory factory;
 *
 * // 获取配置 Supplier
 * Supplier<String> nameSupplier = factory.getSupplier(
 *     new TypedKey("demo.name", "defaultName", String.class));
 * String appName = nameSupplier.get();
 *
 * // 或在字段上使用 @Conf 注解
 * @Conf(key = "demo.name", defaultValue = "defaultName")
 * private Supplier<String> name;
 * }</pre>
 *
 * @author liyifei
 * @see ConfigFactory
 * @see ConfigProperties
 * @see DataProvider
 * @since 1.0
 */
public final class DefaultConfigFactory implements ConfigFactory, ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigFactory.class);
    private ApplicationContext ctx;
    /**
     * 上游数据提供者，负责从数据源（数据库、HTTP API 等）拉取原始配置数据。
     * <p>通过 SPI 机制加载，类型由 {@link ConfigProperties#provider()} 指定。
     */
    private DataProvider provider;

    /**
     * 配置属性，包含 Provider 配置和刷新间隔等信息。
     */
    private final ConfigProperties props;

    /**
     * 刷新互斥锁，用于防止并发刷新导致的数据不一致。
     * <p>使用 {@code tryLock()} 而非 {@code lock()}，避免阻塞读操作。
     * 如果刷新正在进行，新的刷新请求会被跳过（不等待）。
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /**
     * 第一层缓存：原始配置值的全量快照。
     * <p>Key 为配置键名（String），Value 为原始配置值（Object）。
     * <p>特点：
     * <ul>
     *   <li>使用 {@code volatile} 关键字保证可见性</li>
     *   <li>刷新时整体替换引用，实现写时复制（Copy-on-Write）</li>
     *   <li>初始化为空 Map（不可变）</li>
     *   <li>读操作完全无锁，性能优异</li>
     * </ul>
     */
    private volatile Map<String, ConfigItem> baseCache = Map.of();

    /**
     * 第二层缓存：类型化结果的缓存。
     * <p>Key 为 {@link TypedKey}，Value 为已转换的对象或 {@link #NULL} 哨兵值。
     * <p>特点：
     * <ul>
     *   <li>Key：由配置键名、默认值、目标类型组成</li>
     *   <li>Value：已转换的对象或 {@link #NULL} 哨兵值</li>
     *   <li>按需懒构建：只在第一次请求时进行类型转换</li>
     *   <li>刷新后清空：丢弃旧容器，强制基于新的 {@code rawCache} 重新构建</li>
     * </ul>
     */
    private volatile ConcurrentMap<TypedKey, Object> typedCache = new ConcurrentHashMap<>();

    /**
     * NULL 哨兵值，用于在 {@code ConcurrentHashMap} 中表示 {@code null} 值。
     * <p>由于 {@code ConcurrentHashMap} 不允许 {@code null} 值，使用此哨兵值表示"转换结果为 null"的情况。
     * 这样可以区分"配置不存在"和"配置值为 null"两种情况。
     */
    private static final Object NULL = new Object();

    /**
     * 单线程调度器，负责定时刷新配置数据。
     * <p>使用 daemon 线程，不会阻止 JVM 退出。
     * <p>仅在 {@link ConfigProperties#ttl()} 大于 0 时创建和使用。
     */
    private ScheduledExecutorService scheduler;

    /**
     * @param props
     */
    public DefaultConfigFactory(ConfigProperties props) {
        this.props = Objects.requireNonNull(props, "props cannot be null");
    }

    @PostConstruct
    public void init() throws Exception {
        // 1) 读取 ProviderConfig
        Provider pcfg = Objects.requireNonNull(
                props.provider(), "chaos.options.provider must not be null");
        String type = Objects.requireNonNull(
                pcfg.type(), "chaos.options.provider.type must not be null");
        // 2) SPI 加载 DataProvider
        DataProvider dp = loadDataProvider(type);
        // 3) 初始化并记录
        dp.init(props);
        this.provider = dp;
        log.info("Provider [{}] initialized successfully", type);
    }


    /**
     * 启动自动刷新任务。
     * <p>该方法执行以下操作：
     * <ol>
     *   <li>立即同步刷新一次，保证启动时数据可用</li>
     *   <li>如果刷新间隔（TTL）大于 0，启动定时刷新任务</li>
     *   <li>创建 daemon 线程的调度器，不会阻止 JVM 退出</li>
     * </ol>
     * <p>该方法具有幂等性：多次调用仅第一次生效。
     * <p>刷新线程首次延迟等于刷新间隔，避免与"立即刷新"背靠背执行。
     */
    public synchronized void start() {
        if (scheduler != null) {
            log.debug("Refresh scheduler already started, skipping");
            return;
        }
        refresh();
        long refreshIntervalMs = props.ttl().toMillis();
        if (refreshIntervalMs > 0) {
            // 创建单线程调度器
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chaos-data-refresh-" + provider.type());
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((th, ex) ->
                        log.error("Uncaught exception in refresh thread '{}'", th.getName(), ex));
                return t;
            });

            // 启动定时刷新任务（首次延迟等于周期，避免与立即刷新背靠背）
            scheduler.scheduleAtFixedRate(
                    this::refresh,
                    refreshIntervalMs,
                    refreshIntervalMs,
                    TimeUnit.MILLISECONDS
            );
            log.info("DefaultDataConfigFactory [{}] started with refresh interval: {}ms ({}s)",
                    provider.type(), refreshIntervalMs, refreshIntervalMs / 1000.0);
        } else {
            log.info("DefaultDataConfigFactory [{}] started without scheduler (one-time load only)",
                    provider.type());
        }
    }

    /**
     * 根据 {@link TypedKey} 获取对应的 {@code Supplier<T>}。
     * <p>返回的 {@code Supplier} 具有以下特性：
     * <ul>
     *   <li><strong>线程安全</strong>：可以在多线程环境中安全使用</li>
     *   <li><strong>类型安全</strong>：自动将原始值转换为目标类型</li>
     *   <li><strong>惰性转换</strong>：只在第一次调用 {@code get()} 时进行类型转换</li>
     *   <li><strong>缓存结果</strong>：后续调用直接返回缓存的结果</li>
     * </ul>
     * <p><strong>转换流程：</strong>
     * <ol>
     *   <li>检查 {@code typedCache} 中是否存在该 {@code TypedKey} 的缓存</li>
     *   <li>如果不存在，从 {@code rawCache} 获取原始值</li>
     *   <li>如果原始值为空，使用 {@code TypedKey.defaultValue()}（默认值）</li>
     *   <li>使用 {@link com.ddm.chaos.utils.Converters#cast(Object, Class)} 进行类型转换</li>
     *   <li>将转换结果缓存到 {@code typedCache} 中</li>
     *   <li>返回转换后的值（如果转换失败或配置不存在，返回 {@code null}）</li>
     * </ol>
     *
     * @param <T> 目标类型
     * @param key 配置键，包含键名、默认值、目标类型，不能为 null
     * @return {@code Supplier<T>} 实例，永不返回 null。调用其 {@code get()} 方法获取配置值，
     * 如果配置不存在或转换失败则返回 {@code null}
     * @throws NullPointerException 如果 key 为 null
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> getSupplier(TypedKey<T> key) {
        Objects.requireNonNull(key, "key cannot be null");
        return () -> {
            Object v = typedCache.computeIfAbsent(key, __ ->
            {
                // 从原始缓存中获取值
                ConfigItem item = baseCache.get(key.name());
                // 1) 没拿到配置，或解析为空：用 NULL 哨兵，外层会回退 defObj
                if (item == null || item.resolvedValue() == null) {
                    return NULL;
                }
                try {
                    // 进行类型转换
                    Object converted = cast(item.resolvedValue(), key.type());
                    // 如果转换结果为 null，使用 NULL 哨兵
                    return (converted == null) ? NULL : converted;
                } catch (Exception ex) {
                    // 转换失败，记录日志并使用 NULL 哨兵（避免后续反复抛出异常）
                    if (log.isDebugEnabled()) {
                        log.debug("Type conversion failed for key='{}', type={}, defaultValue='{}': {}",
                                key.name(), key.type().getSimpleName(), key.defaultValue(), ex.getMessage(), ex);
                    }
                    return NULL;
                }
            });
            return (v == NULL) ? key.defaultValue() : (T) v;
        };
    }


    /**
     * 刷新配置数据（两阶段原子替换）。
     * <p><strong>刷新流程：</strong>
     * <ol>
     *   <li>从 {@link DataProvider} 加载新的全量配置数据</li>
     *   <li>如果数据有效（非空），执行两阶段替换：
     *     <ul>
     *       <li><strong>阶段1</strong>：原子替换 {@code rawCache} 引用（使用不可变 Map）</li>
     *       <li><strong>阶段2</strong>：清空 {@code typedCache}，丢弃旧容器</li>
     *     </ul>
     *   </li>
     * </ol>
     * <p><strong>并发控制：</strong>
     * <ul>
     *   <li>使用 {@code tryLock()} 防止并发刷新</li>
     *   <li>如果刷新正在进行，新的刷新请求会被跳过（不等待）</li>
     * </ul>
     * <p><strong>可用性保证：</strong>
     * <ul>
     *   <li>如果刷新失败或数据为空，保留旧镜像</li>
     *   <li>读操作不会被阻塞，始终可以访问旧数据</li>
     *   <li>不会因为刷新失败导致服务不可用</li>
     * </ul>
     */
    public void refresh() {
        // 尝试获取锁，如果获取失败则跳过本次刷新（避免并发刷新）
        if (!refreshLock.tryLock()) {
            log.debug("Skip concurrent refresh request (refresh already in progress)");
            return;
        }
        try {
            final long start = System.currentTimeMillis();
            final String ptype = provider.type();
            log.debug("Starting configuration refresh from provider [{}]", ptype);

            // 1) 拉取最新配置（List<ConfigItem>）
            final List<ConfigItem> items = provider.loadData();

            if (items == null || items.isEmpty()) {
                log.warn("Skip refresh: provider [{}] returned null or empty data, keeping previous cache ({} entries)",
                        ptype, baseCache.size());
                return;
            }

            // 2) 构建新的原始快照（Map<key, ConfigItem>），保持插入顺序
            final Map<String, ConfigItem> newRaw = new LinkedHashMap<>(items.size());
            for (ConfigItem it : items) {
                if (it == null) continue;
                final String k = it.key();
                if (k == null || k.isBlank()) continue;
                final ConfigItem prev = newRaw.put(k, it);
                if (prev != null && log.isDebugEnabled()) {
                    log.debug("Duplicate key detected during refresh: key='{}' (last one wins)", k);
                }
            }

            if (newRaw.isEmpty()) {
                log.warn("Skip refresh: provider [{}] produced only invalid/blank keys, keeping previous cache ({} entries)",
                        ptype, baseCache.size());
                return;
            }

            final int oldSize = baseCache.size();

            // 3) 原子替换原始镜像
            this.baseCache = newRaw;

            // 4) 丢弃旧的类型化缓存，按需懒构建
            this.typedCache = new java.util.concurrent.ConcurrentHashMap<>();

            final long cost = System.currentTimeMillis() - start;
            log.info("Configuration refreshed successfully: {} -> {} entries in {} ms (provider: {})",
                    oldSize, baseCache.size(), cost, ptype);

        } catch (Exception e) {
            // 失败保留旧缓存
            log.warn("Refresh failed for provider [{}], keeping previous cache ({} entries). Will retry on next interval: {}",
                    provider.type(), baseCache.size(), e.getMessage(), e);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 优雅关闭工厂，释放所有资源。
     * <p>该方法执行以下操作：
     * <ol>
     *   <li>停止调度器，等待正在执行的任务完成（最多等待 5 秒）</li>
     *   <li>如果等待超时，强制关闭调度器</li>
     *   <li>关闭底层 {@link DataProvider}</li>
     * </ol>
     * <p>该方法具有幂等性，可以安全地多次调用。
     * <p>通常在 Spring 容器销毁时自动调用（通过 {@code destroyMethod = "close"}）。
     *
     * @see AutoCloseable#close()
     */
    @PreDestroy
    @Override
    public void close() {
        String providerType = (provider != null) ? provider.type() : "unknown";
        log.debug("Closing DefaultDataConfigFactory [{}]", providerType);

        // 停止调度器
        ScheduledExecutorService sch = this.scheduler;
        if (sch != null) {
            log.debug("Shutting down refresh scheduler");
            sch.shutdown();
            try {
                // 等待正在执行的任务完成，最多等待 5 秒
                if (!sch.awaitTermination(5, TimeUnit.SECONDS)) {
                    // 等待超时，强制关闭
                    log.warn("Refresh scheduler did not terminate within 5 seconds, forcing shutdown");
                    sch.shutdownNow();
                } else {
                    log.debug("Refresh scheduler terminated successfully");
                }
            } catch (InterruptedException ie) {
                // 线程被中断，恢复中断状态并强制关闭
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for scheduler termination, forcing shutdown");
                sch.shutdownNow();
            } finally {
                this.scheduler = null;
            }
        }

        // 关闭底层 DataProvider
        if (provider != null) {
            try {
                provider.close();
                log.debug("DataProvider [{}] closed successfully", providerType);
            } catch (Exception e) {
                // 上游关闭失败不影响工厂关闭，记录日志即可
                log.warn("Failed to close DataProvider [{}]: {}", providerType, e.getMessage(), e);
            }
        }

        log.info("DefaultDataConfigFactory [{}] closed successfully", providerType);
    }


    /**
     * 通过 SPI 机制加载指定类型的 DataProvider。
     *
     * @param type Provider 类型（不区分大小写），不能为 null
     * @return 对应的 DataProvider 实例，不会为 null
     * @throws IllegalStateException 如果找不到指定类型的 DataProvider
     */
    private static DataProvider loadDataProvider(String type) {
        Objects.requireNonNull(type, "provider type cannot be null");

        ServiceLoader<DataProvider> loader =
                ServiceLoader.load(DataProvider.class, Thread.currentThread().getContextClassLoader());

        List<String> availableTypes = new ArrayList<>();
        DataProvider foundProvider = null;

        for (DataProvider provider : loader) {
            String providerType = provider.type();
            availableTypes.add(providerType);

            if (type.equalsIgnoreCase(providerType)) {
                foundProvider = provider;
            }
        }

        if (foundProvider != null) {
            return foundProvider;
        }

        String typesList = availableTypes.isEmpty()
                ? "none"
                : String.join(", ", availableTypes);
        throw new IllegalStateException(
                String.format("No DataProvider found via SPI for type '%s'. Available types: %s",
                        type, typesList));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        this.start();
    }

}

