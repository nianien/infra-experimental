package com.ddm.chaos.factory;

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
 *
 * <p>实现特性：
 * <ul>
 *   <li>使用 Caffeine LoadingCache 缓存配置数据，键为 ConfRef</li>
 *   <li>采用 refreshAfterWrite 策略，异步刷新缓存，不影响读取性能</li>
 *   <li>支持负缓存：不存在的配置项使用 MISSING_DATA 标记，避免重复查询</li>
 *   <li>类型转换结果缓存在 ConfData 内部</li>
 * </ul>
 *
 * <p><strong>缓存机制：</strong>
 * <ul>
 *   <li>配置数据（ConfData）按 ConfRef 缓存在 Caffeine LoadingCache 中</li>
 *   <li>缓存刷新采用 refreshAfterWrite 策略，超过 TTL 后异步刷新，读取时返回旧值</li>
 *   <li>不存在的配置项使用 MISSING_DATA 作为负缓存，避免重复查询 DataProvider</li>
 *   <li>类型转换结果缓存在 ConfData 内部的 ConcurrentHashMap 中，键为 ConfRef + Type</li>
 * </ul>
 *
 * <p><strong>线程安全：</strong>
 * <ul>
 *   <li>Caffeine LoadingCache 是线程安全的</li>
 *   <li>ConfData 内部的类型转换缓存使用 ConcurrentHashMap，线程安全</li>
 *   <li>刷新操作在独立的线程池中执行，不影响主线程</li>
 * </ul>
 *
 * @author liyifei
 * @see ConfigFactory
 * @since 1.0
 */
public final class DefaultConfigFactory implements ConfigFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfigFactory.class);

    /**
     * 缺省哨兵（负缓存标记：表示 provider 没有这个 key）。
     * <p>
     * 当 DataProvider.loadData() 返回 null 时，使用此对象作为缓存值，
     * 避免重复查询不存在的配置项。
     */
    private static final ConfData MISSING_DATA = new ConfData("", "", new String[0]);

    /**
     * 刷新线程池大小。
     */
    private static final int REFRESH_POOL_SIZE = 2;

    /**
     * 刷新线程池，用于执行异步缓存刷新任务。
     */
    private final ExecutorService refreshPool;

    /**
     * 配置数据缓存，键为 ConfRef，值为 ConfData。
     * <p>
     * 使用 Caffeine LoadingCache，支持 refreshAfterWrite 策略。
     */
    private final LoadingCache<ConfRef, ConfData> cache;

    /**
     * 数据提供者，负责从数据源加载配置项。
     */
    private final DataProvider provider;

    /**
     * 配置属性，包含 TTL 和 profiles 等信息。
     */
    private final ConfigProperties props;

    /**
     * 构造配置工厂。
     * <p>
     * 初始化过程：
     * <ol>
     *   <li>创建刷新线程池（大小为 REFRESH_POOL_SIZE）</li>
     *   <li>构建 Caffeine LoadingCache，设置 refreshAfterWrite 策略</li>
     *   <li>配置缓存加载器为 this::loadData</li>
     * </ol>
     *
     * @param provider 数据提供者，不能为 null
     * @param props    配置属性，ttl 不能为 null
     * @throws NullPointerException 如果 provider 或 props.ttl() 为 null
     */
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
     * 加载配置（被 Caffeine LoadingCache 调用）。
     * <p>
     * 该方法在以下情况被调用：
     * <ul>
     *   <li>缓存中不存在该 ConfRef 时（首次加载）</li>
     *   <li>缓存超过 TTL 后首次访问时（异步刷新）</li>
     * </ul>
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>调用 provider.loadData(ref) 加载配置项</li>
     *   <li>如果 item 为 null，返回 MISSING_DATA（负缓存）</li>
     *   <li>如果 item 存在，创建 ConfData 并返回</li>
     *   <li>如果 provider 抛出异常，包装为 IllegalStateException 抛出（Caffeine 会保留旧值）</li>
     * </ol>
     *
     * @param ref 配置引用
     * @return 配置数据，如果不存在则返回 MISSING_DATA
     * @throws IllegalStateException 如果从 provider 加载数据失败
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

            return new ConfData(item, props.profiles());

        } catch (Exception e) {
            log.error("Failed to load config {} from provider {}, keeping old value",
                    ref, provider.getClass().getSimpleName(), e);
            throw new IllegalStateException("Failed to load config " + ref, e);
        }
    }

    /**
     * 为指定的配置描述符创建类型化的 Supplier。
     * <p>
     * 返回的 Supplier 是线程安全的，可以多线程并发调用 get() 方法。
     *
     * <p>Supplier.get() 方法执行流程：
     * <ol>
     *   <li>调用 cache.get(ref) 获取 ConfData
     *       <ul>
     *         <li>如果缓存中存在，直接返回</li>
     *         <li>如果缓存中不存在，触发 loadData(ref) 加载（同步）</li>
     *         <li>如果缓存超过 TTL，触发异步刷新，返回旧值</li>
     *       </ul>
     *   </li>
     *   <li>如果返回 MISSING_DATA（负缓存），返回 desc.defaultValue()</li>
     *   <li>否则调用 data.getValue(desc) 进行类型转换
     *       <ul>
     *         <li>类型转换结果缓存在 ConfData 内部</li>
     *         <li>如果转换失败，返回 desc.defaultValue()</li>
     *       </ul>
     *   </li>
     *   <li>如果上述过程抛出异常，捕获后返回 desc.defaultValue()</li>
     * </ol>
     *
     * @param <T>  目标类型
     * @param desc 配置描述符，不能为 null
     * @return 类型化的 Supplier 实例，线程安全
     * @throws NullPointerException 如果 desc 为 null
     */
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

    /**
     * 创建刷新线程池。
     * <p>
     * 线程池特性：
     * <ul>
     *   <li>固定大小为 REFRESH_POOL_SIZE（2）</li>
     *   <li>线程名为 "config-refresh"</li>
     *   <li>线程设置为守护线程（daemon）</li>
     * </ul>
     *
     * @return 刷新线程池
     */
    private static ExecutorService createRefreshExecutor() {
        return Executors.newFixedThreadPool(REFRESH_POOL_SIZE, r -> {
            Thread t = new Thread(r, "config-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 关闭工厂，释放相关资源。
     * <p>
     * 执行以下操作（按顺序）：
     * <ol>
     *   <li>调用 refreshPool.shutdownNow() 关闭刷新线程池，中断所有正在执行的任务</li>
     *   <li>调用 provider.close() 关闭底层 DataProvider</li>
     * </ol>
     * <p>
     * 如果关闭过程中发生异常，会记录日志但不会中断关闭流程。
     * <p>
     * 该方法由 Spring 容器在销毁 Bean 时自动调用（通过 @PreDestroy 注解）。
     */
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

    /**
     * 预览配置值，用于日志输出。
     * <p>
     * 如果值长度超过 maxLength，会截断并添加 "...(truncated)" 后缀。
     *
     * @param v         配置值
     * @param maxLength 最大长度
     * @return 预览字符串
     */
    private static String previewValue(String v, int maxLength) {
        if (v == null) return "null";
        if (v.length() <= maxLength) return v;
        return v.substring(0, maxLength) + "...(truncated)";
    }
}