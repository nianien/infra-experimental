package com.ddm.chaos.provider;

import java.util.Map;

/**
 * 数据提供者接口，负责从数据源（数据库、Redis、HTTP API 等）拉取配置数据。
 *
 * <p>该接口定义了配置数据的获取抽象，实现类需要：
 * <ul>
 *   <li>通过 {@link #initialize(Map)} 方法初始化数据源连接</li>
 *   <li>通过 {@link #loadAll()} 方法获取全量配置快照</li>
 *   <li>实现 {@link #close()} 方法释放资源</li>
 * </ul>
 *
 * <p><strong>实现示例：</strong>
 * <pre>{@code
 * public class JdbcDataProvider implements DataProvider {
 *     private JdbcTemplate jdbc;
 *
 *     @Override
 *     public void initialize(Map<String, String> config) {
 *         String url = config.get("url");
 *         this.jdbc = new JdbcTemplate(...);
 *     }
 *
 *     @Override
 *     public Map<String, Object> loadAll() {
 *         // 从数据库查询配置并返回
 *         return jdbc.query(...);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>注意事项：</strong>
 * <ul>
 *   <li>实现类应该保证线程安全性</li>
 *   <li>{@link #loadAll()} 方法应返回不可变或只读的 Map，避免外部修改</li>
 *   <li>异常情况下应返回空 Map 而不是抛出异常，以保证系统可用性</li>
 * </ul>
 *
 * @author liyifei
 * @since 1.0
 * @see JdbcDataProvider
 */
public interface DataProvider extends AutoCloseable {

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
     * @param config 配置参数 Map，包含数据源所需的配置（如 url、username、password 等）
     * @throws Exception 如果初始化失败，抛出异常
     */
    void initialize(Map<String, String> config) throws Exception;

    /**
     * 拉取全量配置快照。
     *
     * <p>该方法从数据源获取所有配置项，返回一个 Map，其中：
     * <ul>
     *   <li>Key：配置项的键（String 类型）</li>
     *   <li>Value：配置项的值（可以是 String、Number、JSON 字符串等原始类型）</li>
     * </ul>
     *
     * <p><strong>实现要求：</strong>
     * <ul>
     *   <li>返回的 Map 应该只包含有效的配置项（已启用、未过期等）</li>
     *   <li>如果数据源为空或查询失败，应返回空 Map 而不是 null</li>
     *   <li>建议返回不可变或只读的 Map，防止外部修改</li>
     * </ul>
     *
     * @return 配置键值对的 Map，Key 为配置键，Value 为配置的原始值
     *         如果无配置或查询失败，返回空 Map（不返回 null）
     * @throws Exception 如果拉取过程中发生严重错误，可以抛出异常
     *                   但建议捕获异常并返回空 Map，保证系统可用性
     */
    Map<String, Object> loadAll() throws Exception;

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
     *
     * @throws Exception 如果关闭过程中发生错误，可以抛出异常
     */
    @Override
    default void close() throws Exception {
        // 默认无操作，由具体实现类重写
    }
}
