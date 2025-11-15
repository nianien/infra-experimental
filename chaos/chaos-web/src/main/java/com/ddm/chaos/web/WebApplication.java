package com.ddm.chaos.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Atlas Demo Web 应用程序
 * <p>
 * 用于测试 gRPC 客户端调用，包含链路追踪功能
 * 集成了 gRPC 客户端测试功能
 */
@SpringBootApplication(scanBasePackages = {
})
public class WebApplication {


    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }


}
