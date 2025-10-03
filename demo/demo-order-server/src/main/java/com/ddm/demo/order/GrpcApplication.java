package com.ddm.demo.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Up 模块独立服务器
 * 
 * 运行 up 模块相关的服务，监听端口 9091
 */
@SpringBootApplication(scanBasePackages = {
        "com.ddm.demo.order"
})
public class GrpcApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrpcApplication.class, args);
    }
}
