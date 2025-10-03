package com.ddm.demo.down;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Down 模块独立服务器
 * 
 * 运行 down 模块相关的服务，监听端口 9092
 */
@SpringBootApplication(scanBasePackages = {
    "com.ddm.demo.down"
})
public class DownApplication {

    public static void main(String[] args) {
        SpringApplication.run(DownApplication.class, args);
    }
}
