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
 * DefaultDataSupplierFactory（两层缓存 + 惰性类型化）
 * <p>
 * 设计要点：
 * - rawCache：保存“原始值”的全量快照，刷新时整体替换（写时复制），读路径零锁。
 * - typedCache：保存 <key,type> 的“已类型化”结果，按需懒构建；刷新后丢弃旧容器以强制基于新 raw 重新构建。
 * - 刷新语义：loadAll() 成功 → 先替换 rawCache → 再“换新” typedCache 容器（不逐条清除，避免全表写入/竞争）。
 * - 并发：tryLock 防止并发刷新；typedCache 使用 computeIfAbsent 保证每个 <key,type> 仅转换一次。
 * - 可用性优先：刷新失败/结果为空 → 保留旧镜像，不中断读路径。
 */
public final class DefaultDataSupplierFactory implements DataSupplierFactory, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultDataSupplierFactory.class);

    /**
     * 上游数据提供者（DB/HTTP/本地文件等），只负责“拿原始值”的全量 Map
     */
    private final DataProvider provider;

    /**
     * 刷新互斥锁：避免“启动立即刷新 + 周期刷新”或外部并发调用导致的重入
     */
    private final ReentrantLock refreshLock = new ReentrantLock();

    /**
     * 第一层缓存：原始快照（整体替换；初始化为空快照）
     */
    private volatile Map<String, Object> rawCache = Map.of();

    /**
     * 第二层缓存：类型化结果（<key,type> → 已转换对象或 NULL_SENTINEL），按需懒构建
     */
    private volatile ConcurrentMap<TypedKey, Object> typedCache = new ConcurrentHashMap<>();

    /**
     * 由于 ConcurrentMap 不允许 null 值，用哨兵占位“转换结果为 null”的情形
     */
    private static final Object NULL = new Object();

    /**
     * 刷新周期（毫秒）；<=0 表示只同步拉取一次，不开启调度器
     */
    private Duration refreshInterval = Duration.ofSeconds(60);

    /**
     * 单线程调度器（daemon 线程），负责周期刷新
     */
    private ScheduledExecutorService scheduler;

    public DefaultDataSupplierFactory(DataProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * 设置刷新周期（秒，支持小数）；<=0 仅首次拉取
     */
    public void setRefreshInterval(Duration duration) {
        this.refreshInterval = duration;
    }


    /**
     * 启动：先同步刷新一次，再按固定周期刷新。
     * 多次调用仅第一次生效（幂等防护）。
     */
    public synchronized void startRefresh() {
        if (scheduler != null) return; // 已启动
        refreshAll();                  // 立即拉一版，保障可用
        long refreshIntervalMs = refreshInterval.toMillis();
        if (refreshIntervalMs > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ddm-data-refresh");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((th, ex) -> log.error("Uncaught in refresh thread", ex));
                return t;
            });
            // 首次延迟=周期，避免与“立即刷新”背靠背
            scheduler.scheduleAtFixedRate(this::refreshAll, refreshIntervalMs, refreshIntervalMs, TimeUnit.MILLISECONDS);
            log.info("DefaultDataSupplierFactory started (interval={}ms)", refreshIntervalMs);
        } else {
            log.info("DefaultDataSupplierFactory started (no scheduler)");
        }
    }

    /**
     * 获取一个按 <key,type> 绑定的 Supplier：
     * - 第一次调用时对 raw 值做类型转换并缓存；
     * - 后续调用零开销，直接返回已缓存的结果；
     * - 刷新成功后 typedCache 会被换新（旧结果失效，按需重新构建）。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Supplier<T> getSupplier(String key, Class<T> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        return () -> {
            Object v = typedCache.computeIfAbsent(new TypedKey(key, type), tk -> {
                Object raw = rawCache.get(tk.key);
                if (raw == null) return NULL;
                try {
                    Object converted = cast(raw, tk.type);
                    return (converted == null) ? NULL : converted;
                } catch (Exception ex) {
                    // 转换失败按 null 处理，避免后续反复抛异常
                    log.debug("Cast failed for key={}, type={}", key, type.getName(), ex);
                    return NULL;
                }
            });
            return (v == NULL) ? null : (T) v;
        };
    }

    /**
     * 刷新流程（两阶段）：
     * 1) provider.loadAll() 读取新全量数据；
     * 2) 原子替换 rawCache 引用；
     * 3) 换新 typedCache 容器（丢弃旧容器，避免 clear 全表写入与竞争）。
     * 失败时保留旧镜像，保证可用性。
     */
    private void refreshAll() {
        if (!refreshLock.tryLock()) {
            log.debug("Skip concurrent refresh");
            return;
        }
        try {
            Map<String, Object> newRaw = provider.loadAll();
            if (newRaw == null || newRaw.isEmpty()) {
                log.warn("Skip refresh: provider returned null/empty, keep previous caches");
                return;
            }
            // 1) 原子替换原始快照（如需强制只读，可改为 Map.copyOf(newRaw)）
            this.rawCache = Map.copyOf(newRaw);
            ;
            // 2) 丢弃旧类型化缓存，换新容器（新数据按需懒构建）
            this.typedCache = new ConcurrentHashMap<>();
            log.info("Refreshed rawCache: size={}", rawCache.size());
        } catch (Exception e) {
            log.warn("Refresh failed, keep previous caches (will retry).", e);
        } finally {
            refreshLock.unlock();
        }
    }

    /**
     * 优雅关闭：停止调度线程并关闭上游 provider。
     * 可重复调用；在容器销毁或集成测试中调用。
     */
    @Override
    public void close() {
        ScheduledExecutorService sch = this.scheduler;
        if (sch != null) {
            sch.shutdown();
            try {
                if (!sch.awaitTermination(5, TimeUnit.SECONDS)) sch.shutdownNow();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sch.shutdownNow();
            } finally {
                this.scheduler = null;
            }
        }
        try {
            provider.close();
        } catch (Exception ignore) {
            // 上游关闭失败不影响工厂关闭
        }
        log.info("DefaultDataSupplierFactory closed");
    }

    /**
     * <key,type> 组合键：同 key 且同 type 视为同一条 typed 缓存项
     */
    private record TypedKey(String key, Class<?> type) {
        TypedKey {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(type, "type");
        }
        // 默认 record 的 equals/hashCode 足够；若极端热点可手写 31*hash 版本以规避 varargs 分配
    }
}