package com.ddm.chaos.provider;

import com.ddm.chaos.defined.ConfInfo;

import java.util.Map;

/**
 * 数据提供者接口，负责从数据源（数据库、Redis、HTTP API 等）拉取配置数据。
 *
 * <p>该接口定义了配置数据的获取抽象，实现类需要：
 * <ul>
 *   <li>通过 {@link #init(Map)} 方法初始化数据源连接</li>
 *   <li>实现 {@link #close()} 方法释放资源</li>
 * </ul>
 *
 * <p><strong>实现示例：</strong>
 * <pre>{@code
 * public class JdbcDataProvider implements DataProvider {
 *     private NamedParameterJdbcTemplate jdbc;
 *
 *     @Override
 *     public void init(Map<String, String> options) {
 *         String url = options.get("jdbc-url");
 *         DataSource ds = new DriverManagerDataSource(url, ...);
 *         this.jdbc = new NamedParameterJdbcTemplate(ds);
 *     }
 *
 *     @Override
 *     public ConfItem loadData(ConfInfo info) {
 *         // 从数据库查询配置并返回
 *         return jdbc.queryForObject(sql, params, rowMapper);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>注意事项：</strong>
 * <ul>
 *   <li>实现类应该保证线程安全性</li>
 *   <li>异常情况下应返回空 List 而不是抛出异常，以保证系统可用性</li>
 * </ul>
 *
 * @author liyifei
 * @since 1.0
 */
public interface DataProvider extends AutoCloseable {

    String type();

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
    void init(Map<String, String> options);

    /**
     * 拉取全量配置快照。
     *
     * <p>该方法从数据源获取所有配置项，返回一个 {@link ConfItem} 列表。
     * <p>每个 {@link ConfItem} 包含：
     * <ul>
     *   <li>配置键（key）</li>
     *   <li>默认值（value）</li>
     *   <li>变体配置（variant，JSON 字符串）</li>
     *   <li>标签（tags）</li>
     *   <li>已解析的生效值（resolvedValue）</li>
     * </ul>
     *
     * <p><strong>实现要求：</strong>
     * <ul>
     *   <li>返回的 List 应该只包含有效的配置项（已启用、未过期等）</li>
     *   <li>如果数据源为空或查询失败，应返回空 List 而不是 null</li>
     *   <li>建议返回不可变或只读的 List，防止外部修改</li>
     * </ul>
     *
     * @return 配置项列表，如果无配置或查询失败，返回空 List（不返回 null）
     * @throws Exception 如果拉取过程中发生严重错误，可以抛出异常
     *                   但建议捕获异常并返回空 List，保证系统可用性
     */

    ConfItem loadData(ConfInfo info);

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
