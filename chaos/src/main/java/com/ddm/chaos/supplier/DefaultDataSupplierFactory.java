package com.ddm.chaos.supplier;

import com.ddm.chaos.provider.DataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static com.ddm.chaos.utils.Converters.cast;

/**
 * DataSupplierFactory 的默认实现，采用两层缓存架构和惰性类型化策略。
 * 
 * <p><strong>核心设计：</strong>
 * <ul>
 *   <li><strong>两层缓存架构</strong>：
 *     <ol>
 *       <li><strong>rawCache</strong>：保存原始值的全量快照，使用 volatile 引用实现无锁读取</li>
 *       <li><strong>typedCache</strong>：保存类型化结果，按需懒构建，避免不必要的类型转换</li>
 *     </ol>
 *   </li>
 *   <li><strong>写时复制（Copy-on-Write）</strong>：刷新时整体替换 rawCache 引用，保证读路径零锁</li>
 *   <li><strong>惰性类型化</strong>：只在第一次请求时进行类型转换，后续直接返回缓存结果</li>
 *   <li><strong>可用性优先</strong>：刷新失败时保留旧数据，保证系统可用性</li>
 * </ul>
 * 
 * <p><strong>并发控制：</strong>
 * <ul>
 *   <li>读路径：完全无锁，通过 volatile 引用保证可见性</li>
 *   <li>写路径：使用 {@code ReentrantLock.tryLock()} 防止并发刷新</li>
 *   <li>类型转换：使用 {@code ConcurrentHashMap.computeIfAbsent()} 保证每个 &lt;key,type&gt; 仅转换一次</li>
 * </ul>
 * 
 * <p><strong>刷新策略：</strong>
 * <ul>
 *   <li>启动时立即同步刷新一次，保证数据可用</li>
 *   <li>支持定时刷新，刷新间隔可配置</li>
 *   <li>刷新时先替换 rawCache，再清空 typedCache（不逐条清除，避免全表写入和竞争）</li>
 *   <li>刷新失败时保留旧镜像，不中断读路径</li>
 * </ul>
 * 
 * <p><strong>使用示例：</strong>
 * <pre>{@code
 * DataProvider provider = new JdbcDataProvider();
 * provider.initialize(config);
 * 
 * DefaultDataSupplierFactory factory = new DefaultDataSupplierFactory(provider);
 * factory.setRefreshInterval(Duration.ofSeconds(60));
 * factory.startRefresh();
 * 
 * Supplier<String> nameSupplier = factory.getSupplier("app.name", String.class);
 * String appName = nameSupplier.get();
 * }</pre>
 * 
 * @author liyifei
 * @since 1.0
 * @see DataSupplierFactory
 */
public final class DefaultDataSupplierFactory implements DataSupplierFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataSupplierFactory.class);

    /**
     * 上游数据提供者，负责从数据源（数据库、HTTP API 等）拉取原始配置数据。
     */
    private final DataProvider provider;

    /**
     * 刷新互斥锁，用于防止并发刷新导致的数据不一致。
     * 
     * <p>使用 {@code tryLock()} 而非 {@code lock()}，避免阻塞读操作。
     * 如果刷新正在进行，新的刷新请求会被跳过。
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /**
     * 第一层缓存：原始配置值的全量快照。
     * 
     * <p>特点：
     * <ul>
     *   <li>使用 {@code volatile} 关键字保证可见性</li>
     *   <li>刷新时整体替换引用，实现写时复制（Copy-on-Write）</li>
     *   <li>初始化为空 Map（不可变）</li>
     *   <li>读操作完全无锁，性能优异</li>
     * </ul>
     */
    private volatile Map<String, Object> rawCache = Map.of();

    /**
     * 第二层缓存：类型化结果的缓存。
     * 
     * <p>特点：
     * <ul>
     *   <li>Key：{@code TypedKey(key, type)}，由配置键和目标类型组成</li>
     *   <li>Value：已转换的对象或 {@code NULL} 哨兵值</li>
     *   <li>按需懒构建：只在第一次请求时进行类型转换</li>
     *   <li>刷新后清空：丢弃旧容器，强制基于新的 rawCache 重新构建</li>
     * </ul>
     */
    private volatile ConcurrentMap<TypedKey, Object> typedCache = new ConcurrentHashMap<>();

    /**
     * NULL 哨兵值。
     * 
     * <p>由于 {@code ConcurrentHashMap} 不允许 null 值，使用此哨兵值表示"转换结果为 null"的情况。
     * 这样可以区分"配置不存在"和"配置值为 null"两种情况。
     */
    private static final Object NULL = new Object();

    /**
     * 刷新间隔，默认 60 秒。
     * 
     * <p>如果设置为 0 或负数，表示禁用自动刷新，仅在启动时加载一次。
     */
    private Duration refreshInterval = Duration.ofSeconds(60);

    /**
     * 单线程调度器，负责定时刷新配置数据。
     * 
     * <p>使用 daemon 线程，不会阻止 JVM 退出。
     */
    private ScheduledExecutorService scheduler;

    /**
     * 构造函数。
     * 
     * @param provider 数据提供者实例，不能为 null
     * @throws NullPointerException 如果 provider 为 null
     */
    public DefaultDataSupplierFactory(DataProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
    }

    /**
     * 设置刷新间隔。
     * 
     * <p>如果设置的间隔小于等于 0，则禁用自动刷新，仅在启动时加载一次配置。
     * 
     * <p>注意：必须在调用 {@link #startRefresh()} 之前设置此值。
     * 
     * @param duration 刷新间隔，支持 Duration 格式（如 60s、5m、1h 等）
     */
    public void setRefreshInterval(Duration duration) {
        this.refreshInterval = duration;
    }

    /**
     * 启动自动刷新任务。
     * 
     * <p>该方法执行以下操作：
     * <ol>
     *   <li>立即同步刷新一次，保证启动时数据可用</li>
     *   <li>如果刷新间隔大于 0，启动定时刷新任务</li>
     *   <li>创建 daemon 线程的调度器，不会阻止 JVM 退出</li>
     * </ol>
     * 
     * <p>该方法具有幂等性：多次调用仅第一次生效。
     * 
     * <p>刷新线程首次延迟等于刷新间隔，避免与"立即刷新"背靠背执行。
     */
    public synchronized void startRefresh() {
        if (scheduler != null) {
            // 已启动，直接返回（幂等性保证）
            return;
        }
        
        // 立即同步刷新一次，保证启动时数据可用
        refreshAll();
        
        long refreshIntervalMs = refreshInterval.toMillis();
        if (refreshIntervalMs > 0) {
            // 创建单线程调度器
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ddm-data-refresh");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((th, ex) -> 
                    log.error("Uncaught exception in refresh thread", ex));
                return t;
            });
            
            // 启动定时刷新任务（首次延迟等于周期，避免与立即刷新背靠背）
            scheduler.scheduleAtFixedRate(
                this::refreshAll, 
                refreshIntervalMs, 
                refreshIntervalMs, 
                TimeUnit.MILLISECONDS
            );
            log.info("DefaultDataSupplierFactory started with refresh interval: {}ms", refreshIntervalMs);
        } else {
            log.info("DefaultDataSupplierFactory started without scheduler (one-time load only)");
        }
    }

    /**
     * 根据配置键和目标类型获取对应的 Supplier。
     * 
     * <p>返回的 Supplier 具有以下特性：
     * <ul>
     *   <li>线程安全：可以在多线程环境中安全使用</li>
     *   <li>类型安全：自动将原始值转换为目标类型</li>
     *   <li>惰性转换：只在第一次调用 get() 时进行类型转换</li>
     *   <li>缓存结果：后续调用直接返回缓存的结果</li>
     * </ul>
     * 
     * <p>转换流程：
     * <ol>
     *   <li>检查 typedCache 中是否存在该 &lt;key,type&gt; 的缓存</li>
     *   <li>如果不存在，从 rawCache 获取原始值</li>
     *   <li>使用 {@link com.ddm.chaos.utils.Converters#cast(Object, Class)} 进行类型转换</li>
     *   <li>将转换结果缓存到 typedCache 中</li>
     *   <li>返回转换后的值（如果转换失败或配置不存在，返回 null）</li>
     * </ol>
     * 
     * @param <T> 目标类型
     * @param key 配置键，不能为 null
     * @param type 目标类型，不能为 null
     * @return Supplier&lt;T&gt; 实例，永不返回 null
     *         调用其 get() 方法获取配置值，如果配置不存在或转换失败则返回 null
     * @throws NullPointerException 如果 key 或 type 为 null
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> getSupplier(String key, Class<T> type) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        
        return () -> {
            // 使用 computeIfAbsent 保证原子性和唯一转换
            Object v = typedCache.computeIfAbsent(new TypedKey(key, type), tk -> {
                // 从原始缓存中获取值
                Object raw = rawCache.get(tk.key);
                if (raw == null) {
                    // 配置不存在，返回 NULL 哨兵
                    return NULL;
                }
                
                try {
                    // 进行类型转换
                    Object converted = cast(raw, tk.type);
                    // 如果转换结果为 null，使用 NULL 哨兵
                    return (converted == null) ? NULL : converted;
                } catch (Exception ex) {
                    // 转换失败，记录日志并使用 NULL 哨兵（避免后续反复抛出异常）
                    log.debug("Type conversion failed for key={}, type={}", 
                             key, type.getName(), ex);
                    return NULL;
                }
            });
            
            // 将 NULL 哨兵转换回 null
            return (v == NULL) ? null : (T) v;
        };
    }

    /**
     * 刷新配置数据（两阶段原子替换）。
     * 
     * <p>刷新流程：
     * <ol>
     *   <li>从 DataProvider 加载新的全量配置数据</li>
     *   <li>如果数据有效（非空），执行两阶段替换：
     *     <ul>
     *       <li>阶段1：原子替换 rawCache 引用（使用不可变 Map）</li>
     *       <li>阶段2：清空 typedCache，丢弃旧容器</li>
     *     </ul>
     *   </li>
     * </ol>
     * 
     * <p>并发控制：
     * <ul>
     *   <li>使用 {@code tryLock()} 防止并发刷新</li>
     *   <li>如果刷新正在进行，新的刷新请求会被跳过</li>
     * </ul>
     * 
     * <p>可用性保证：
     * <ul>
     *   <li>如果刷新失败或数据为空，保留旧镜像</li>
     *   <li>读操作不会被阻塞，始终可以访问旧数据</li>
     * </ul>
     */
    private void refreshAll() {
        // 尝试获取锁，如果获取失败则跳过本次刷新（避免并发刷新）
        if (!refreshLock.tryLock()) {
            log.debug("Skip concurrent refresh request");
            return;
        }
        
        try {
            // 从数据源加载新的配置数据
            Map<String, Object> newRaw = provider.loadAll();
            
            // 验证数据有效性
            if (newRaw == null || newRaw.isEmpty()) {
                log.warn("Skip refresh: provider returned null or empty data, keeping previous cache");
                return;
            }
            
            // 阶段1：原子替换原始快照引用（使用不可变 Map 保证线程安全）
            this.rawCache = Map.copyOf(newRaw);
            
            // 阶段2：丢弃旧类型化缓存，换新容器（新数据按需懒构建）
            // 注意：不逐条清除，避免全表写入和竞争
            this.typedCache = new ConcurrentHashMap<>();
            
            log.info("Configuration refreshed successfully: {} entries", rawCache.size());
            
        } catch (Exception e) {
            // 刷新失败时保留旧镜像，保证系统可用性
            log.warn("Refresh failed, keeping previous cache (will retry on next interval)", e);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 优雅关闭工厂，释放所有资源。
     * 
     * <p>该方法执行以下操作：
     * <ol>
     *   <li>停止调度器，等待正在执行的任务完成（最多等待 5 秒）</li>
     *   <li>如果等待超时，强制关闭调度器</li>
     *   <li>关闭底层 DataProvider</li>
     * </ol>
     * 
     * <p>该方法具有幂等性，可以安全地多次调用。
     * 通常在 Spring 容器销毁时自动调用（通过 {@code destroyMethod = "close"}）。
     */
    @Override
    public void close() {
        // 停止调度器
        ScheduledExecutorService sch = this.scheduler;
        if (sch != null) {
            sch.shutdown();
            try {
                // 等待正在执行的任务完成，最多等待 5 秒
                if (!sch.awaitTermination(5, TimeUnit.SECONDS)) {
                    // 等待超时，强制关闭
                    sch.shutdownNow();
                }
            } catch (InterruptedException ie) {
                // 线程被中断，恢复中断状态并强制关闭
                Thread.currentThread().interrupt();
                sch.shutdownNow();
            } finally {
                this.scheduler = null;
            }
        }
        
        // 关闭底层 DataProvider
        try {
            provider.close();
        } catch (Exception e) {
            // 上游关闭失败不影响工厂关闭，记录日志即可
            log.debug("Failed to close provider", e);
        }
        
        log.info("DefaultDataSupplierFactory closed successfully");
    }

    /**
     * 类型化键（TypedKey），用于标识唯一的类型化缓存项。
     * 
     * <p>由配置键（key）和目标类型（type）组成，确保同一个配置键在不同目标类型下有独立的缓存。
     * 
     * <p>示例：
     * <ul>
     *   <li>{@code TypedKey("app.port", String.class)} → 缓存字符串类型的端口值</li>
     *   <li>{@code TypedKey("app.port", Integer.class)} → 缓存整数类型的端口值</li>
     * </ul>
     * 
     * <p>使用 Java Record 自动生成 equals()、hashCode() 和 toString() 方法，
     * 性能足够满足大多数场景。在极端热点场景下，可考虑手写 hashCode 以规避 varargs 分配。
     * 
     * @param key 配置键，不能为 null
     * @param type 目标类型，不能为 null
     */
    private record TypedKey(String key, Class<?> type) {
        /**
         * 紧凑构造函数，验证参数非空。
         */
        TypedKey {
            Objects.requireNonNull(key, "key cannot be null");
            Objects.requireNonNull(type, "type cannot be null");
        }
        
        // 默认 record 的 equals/hashCode 足够；
        // 若极端热点可手写 31*hash 版本以规避 varargs 分配
    }
}
