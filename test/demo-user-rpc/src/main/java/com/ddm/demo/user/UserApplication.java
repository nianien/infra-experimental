package com.ddm.demo.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Down 模块独立服务器
 * <p>
 * 运行 down 模块相关的服务，监听端口
 */
@SpringBootApplication(scanBasePackages = {
        "com.ddm.demo.user",
})
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
