package com.ddm.demo.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Down 模块独立服务器
 * 
 * 运行 down 模块相关的服务，监听端口 9092
 */
@SpringBootApplication(scanBasePackages = {
        "com.ddm.demo.user",
        "com.ddm.argus.ecs"
})
public class GrpcApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrpcApplication.class, args);
    }
}
