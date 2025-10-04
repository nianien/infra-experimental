package com.ddm.demo.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Up 模块独立服务器
 * <p>
 * 运行 up 模块相关的服务，监听端口 9091
 */
@SpringBootApplication(scanBasePackages = {
        "com.ddm.demo.order",
})
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
