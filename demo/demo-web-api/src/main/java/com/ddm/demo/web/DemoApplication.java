package com.ddm.demo.web;

import com.ddm.demo.proto.order.OrderServiceGrpc;
import com.ddm.demo.proto.user.UserServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Atlas Demo Web 应用程序
 * <p>
 * 用于测试 gRPC 客户端调用，包含链路追踪功能
 * 集成了 gRPC 客户端测试功能
 */
@SpringBootApplication(scanBasePackages = {
        "com.ddm.demo.web"
})
public class DemoApplication {

    @GrpcClient("user-service")
    private UserServiceGrpc.UserServiceBlockingStub userServiceStub;

    @GrpcClient("order-service")
    private OrderServiceGrpc.OrderServiceBlockingStub orderServiceStub;

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }


}
