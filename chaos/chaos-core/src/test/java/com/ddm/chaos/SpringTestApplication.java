package com.ddm.chaos;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(SqlInitializationProperties.class) // 让 props 成为 Bean
public class SpringTestApplication {


    @Bean
    @ConditionalOnProperty(
            prefix = "chaos.config-center.provider",
            name = "type",
            havingValue = "jdbc"
    )
    @ConfigurationProperties(prefix = "chaos.config-center.provider.options")
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }

    // 绑定 SQL 初始化器到 ds-chaos（使用三参构造更稳）
    @Bean
    public SqlDataSourceScriptDatabaseInitializer dsChaosInitializer(
            DataSource ds,
            SqlInitializationProperties props) {
        return new SqlDataSourceScriptDatabaseInitializer(ds, props);
    }
}