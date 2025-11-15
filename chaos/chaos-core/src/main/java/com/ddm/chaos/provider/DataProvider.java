package com.ddm.chaos.provider;

import com.ddm.chaos.defined.ConfItem;
import com.ddm.chaos.defined.ConfRef;

/**
 * 数据提供者接口，负责从数据源（数据库、Redis、HTTP API 等）拉取配置数据。
 *
 * <p><strong>职责：</strong>
 * <ul>
 *   <li>根据配置项引用（{@link ConfRef}）返回单条配置记录 {@link ConfItem}</li>
 *   <li>提供资源关闭能力（通过 {@link AutoCloseable} 接口）</li>
 * </ul>
 *
 * <p><strong>实现示例：</strong>
 * <pre>{@code
 * public class JdbcDataProvider implements DataProvider {
 *     private final NamedParameterJdbcTemplate jdbc;
 *
 *     public JdbcDataProvider(DataSource dataSource) {
 *         this.jdbc = new NamedParameterJdbcTemplate(dataSource);
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


    /**
     * 根据配置引用加载对应的配置记录。
     *
     * @param ref 配置项引用（命名空间 + 分组 + key），不会为 null
     * @return 对应的配置项，如果不存在可抛出异常或返回 null（由实现决定）
     */
    ConfItem loadData(ConfRef ref);


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
