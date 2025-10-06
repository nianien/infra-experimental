package com.ddm.demo.client;

import com.ddm.argus.grpc.TraceContext;
import com.ddm.argus.grpc.TraceContext.TraceInfo;
import com.ddm.demo.proto.order.*;
import com.ddm.demo.proto.user.*;
import io.grpc.Context;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Atlas Demo Web 应用程序
 * <p>
 * 用于测试 gRPC 客户端调用，包含链路追踪功能
 * 集成了 gRPC 客户端测试功能
 */
@SpringBootApplication(scanBasePackages = {
        "com.ddm.demo.client"
})
public class WebApplication implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(WebApplication.class);

    @GrpcClient("user-service")
    private UserServiceGrpc.UserServiceBlockingStub userServiceStub;

    @GrpcClient("order-service")
    private OrderServiceGrpc.OrderServiceBlockingStub orderServiceStub;

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }

    @Override
    public void run(String... args) {
        TraceInfo nextHop = TraceInfo.root("test");

        // 设置TraceId到Context和MDC
        Context contextWithTraceId = Context.current()
                .withValue(TraceContext.CTX_TRACE_INFO, nextHop);
        MDC.put(TraceContext.MDC_TRACE_ID, nextHop.traceId());
        try {
            // 在TraceId Context中执行所有调用
            contextWithTraceId.run(() -> {
                testUserService();
                testOrderService();
            });
        } finally {
            MDC.remove(TraceContext.MDC_TRACE_ID);
        }

        logger.info("Atlas Demo Web Application gRPC client tests completed!");
    }

    private void testUserService() {
        logger.info("=== Testing User Service ===");

        try {
            // 测试获取用户
            logger.info("Testing GetUser for existing user");
            GetUserRequest request = GetUserRequest.newBuilder()
                    .setUserId("user1")
                    .build();
            GetUserResponse response = userServiceStub.getUser(request);
            logger.info("User found: {} ({}) - Active: {}",
                    response.getName(), response.getEmail(), response.getIsActive());

            // 测试创建用户
            logger.info("Testing CreateUser");
            CreateUserRequest createRequest = CreateUserRequest.newBuilder()
                    .setName("测试用户")
                    .setEmail("test@example.com")
                    .build();
            CreateUserResponse createResponse = userServiceStub.createUser(createRequest);
            logger.info("User created: {} - Success: {}",
                    createResponse.getUserId(), createResponse.getSuccess());

        } catch (StatusRuntimeException e) {
            logger.warn("User Service RPC failed: {}", e.getStatus());
        }
    }

    private void testOrderService() {
        logger.info("=== Testing Order Service ===");

        try {
            // 测试创建订单
            logger.info("Testing CreateOrder for existing user");
            CreateOrderRequest request = CreateOrderRequest.newBuilder()
                    .setUserId("user1")
                    .setProductName("iPhone 15 Pro")
                    .setAmount(1299.99)
                    .build();
            CreateOrderResponse response = orderServiceStub.createOrder(request);
            logger.info("Order created: {} - Success: {} - Message: {}",
                    response.getOrderId(), response.getSuccess(), response.getMessage());

            if (response.getSuccess()) {
                // 测试获取订单
                logger.info("Testing GetOrder");
                GetOrderRequest getRequest = GetOrderRequest.newBuilder()
                        .setOrderId(response.getOrderId())
                        .build();
                GetOrderResponse getResponse = orderServiceStub.getOrder(getRequest);
                logger.info("Order details: {} - Product: {} - Amount: {} - Status: {}",
                        getResponse.getOrderId(), getResponse.getProductName(),
                        getResponse.getAmount(), getResponse.getStatus());
            }

            // 测试获取用户订单列表
            logger.info("Testing GetUserOrders");
            GetUserOrdersRequest ordersRequest = GetUserOrdersRequest.newBuilder()
                    .setUserId("user1")
                    .build();

            orderServiceStub.getUserOrders(ordersRequest).forEachRemaining(orderResponse -> {
                logger.info("User order: {} - Product: {} - Amount: {} - Status: {}",
                        orderResponse.getOrderId(), orderResponse.getProductName(),
                        orderResponse.getAmount(), orderResponse.getStatus());
            });

        } catch (StatusRuntimeException e) {
            logger.warn("Order Service RPC failed: {}", e.getStatus());
        }
    }
}
