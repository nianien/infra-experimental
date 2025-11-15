package com.ddm.chaos;

import com.ddm.chaos.provider.DataProvider;
import com.ddm.chaos.provider.JdbcDataProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 配置中心服务端应用主类。
 * <p>
 * 该应用提供基于 gRPC 的配置服务，通过 {@link JdbcDataProvider} 从数据库读取配置数据。
 * <p>
 * <strong>功能特性：</strong>
 * <ul>
 *   <li>提供 gRPC 配置服务接口（{@link com.ddm.chaos.server.ConfigServiceImpl}）</li>
 *   <li>使用 JDBC 数据提供者从数据库加载配置</li>
 *   <li>支持 Spring Boot 自动配置</li>
 * </ul>
 *
 * @author liyifei
 * @since 1.0
 */
@SpringBootApplication(scanBasePackages = {
        "com.ddm.chaos.server"
})
public class ConfigApplication {

    /**
     * 创建 JDBC 数据提供者 Bean。
     * <p>
     * 使用配置的数据源创建 JdbcDataProvider 实例，供 gRPC 服务使用。
     *
     * @param dataSource 数据源，由 Spring 自动注入
     * @return DataProvider 实例
     */
    @Bean
    public DataProvider jdbcDataProvider(DataSource dataSource) {
        return new JdbcDataProvider(dataSource);
    }

    /**
     * 创建数据源 Bean。
     * <p>
     * 从配置属性 {@code spring.datasource.chaos.*} 中读取数据源配置。
     *
     * @return DataSource 实例
     */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.chaos")
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * 应用入口方法。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ConfigApplication.class, args);
    }
}
