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
 * @author : liyifei
 * @created : 2025/11/15, Saturday
 * Copyright (c) 2004-2029 All Rights Reserved.
 **/
@SpringBootApplication(scanBasePackages = {
})
public class ConfigApplication {


    @Bean
    public DataProvider jdbcDataProvider(DataSource dataSource) {
        return new JdbcDataProvider(dataSource);
    }

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.chaos")
    public DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }

    public static void main(String[] args) {
        SpringApplication.run(ConfigApplication.class, args);
    }
}
