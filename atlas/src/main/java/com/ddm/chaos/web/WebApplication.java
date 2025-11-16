package com.ddm.chaos.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Chaos 配置中心 Web 管理应用。
 * <p>
 * 提供配置管理的 Web 界面和 RESTful API。
 *
 * @author liyifei
 * @since 1.0
 */
@SpringBootApplication(scanBasePackages = {
        "com.ddm.chaos.web"
})
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
