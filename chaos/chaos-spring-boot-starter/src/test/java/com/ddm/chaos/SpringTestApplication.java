package com.ddm.chaos;

import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(SqlInitializationProperties.class) // 让 props 成为 Bean
public class SpringTestApplication {


    @Bean
    public SqlDataSourceScriptDatabaseInitializer dsChaosInitializer(
            DataSource ds,
            SqlInitializationProperties props) {
        return new SqlDataSourceScriptDatabaseInitializer(ds, props);
    }
}