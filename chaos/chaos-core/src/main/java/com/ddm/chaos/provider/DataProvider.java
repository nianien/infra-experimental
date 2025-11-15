package com.ddm.chaos.provider;

import com.ddm.chaos.defined.ConfItem;
import com.ddm.chaos.defined.ConfRef;

import java.util.Map;

/**
 * 数据提供者接口，负责从数据源（数据库、Redis、HTTP API 等）拉取配置数据。
 *
 * <p><strong>职责：</strong>
 * <ul>
 *   <li>根据配置项引用（{@link ConfRef}）返回单条配置记录 {@link ConfItem}</li>
 *   <li>管理数据源生命周期（初始化、关闭）</li>
 *   <li>以 {@link #type()} 标识自身类型，便于 SPI 发现</li>
 * </ul>
 *
 * <p><strong>实现示例：</strong>
 * <pre>{@code
 * public class JdbcDataProvider implements DataProvider {
 *     private NamedParameterJdbcTemplate jdbc;
 *
 *     @Override
 *     public void init(Map<String, String> options) {
 *         String url = must(options, "jdbc-url");
 *         DataSource ds = DataSourceBuilder.create()
 *                 .url(url)
 *                 .username(options.getOrDefault("username", ""))
 *                 .password(options.getOrDefault("password", ""))
 *                 .build();
 *         this.jdbc = new NamedParameterJdbcTemplate(ds);
 *     }
 *
 *     @Override
 *     public ConfItem loadData(ConfRef ref) {
 *         return jdbc.queryForObject(SQL, paramsFrom(ref), ROW_MAPPER);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>注意事项：</strong>
 * <ul>
 *   <li>实现类应该是线程安全的</li>
 *   <li>遇到网络或数据源异常时请抛出异常，具体处理由上层决定</li>
 * </ul>
 *
 * @author liyifei
 * @since 1.0
 */
public interface DataProvider extends AutoCloseable {

    String type();


    /**
     * 根据配置引用加载对应的配置记录。
     *
     * @param ref 配置项引用（命名空间 + 分组 + key），不会为 null
     * @return 对应的配置项，如果不存在可抛出异常或返回 null（由实现决定）
     */
    ConfItem loadData(ConfRef ref);

    /**
     * 使用配置参数初始化数据源。
     *
     * <p>该方法在数据提供者使用前调用，用于：
     * <ul>
     *   <li>建立数据源连接（数据库连接、HTTP 客户端等）</li>
     *   <li>验证配置参数的有效性</li>
     *   <li>初始化必要的资源</li>
     * </ul>
     *
     * @param options 配置参数 Map，包含数据源所需的配置（如 url、username、password 等）
     * @throws Exception 如果初始化失败，抛出异常
     */
    default void init(Map<String, String> options) {
    }

    /**
     * 关闭数据提供者，释放相关资源。
     *
     * <p>该方法在数据提供者不再使用时调用，用于：
     * <ul>
     *   <li>关闭数据库连接</li>
     *   <li>关闭 HTTP 客户端</li>
     *   <li>释放其他资源</li>
     * </ul>
     *
     * <p>默认实现为空操作（no-op），实现类可根据需要重写。
     */
    @Override
    default void close() {
        // 默认无操作，由具体实现类重写
    }


}
