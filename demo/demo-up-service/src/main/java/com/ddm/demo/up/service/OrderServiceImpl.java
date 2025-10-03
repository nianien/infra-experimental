package com.ddm.demo.up.service;

import com.ddm.demo.proto.order.*;
import com.ddm.demo.proto.order.OrderServiceGrpc.OrderServiceImplBase;
import com.ddm.demo.proto.user.GetUserRequest;
import com.ddm.demo.proto.user.GetUserResponse;
import com.ddm.demo.proto.user.UserServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import net.devh.boot.grpc.client.inject.GrpcClient;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订单服务实现 - 上游服务
 * 使用@GrpcService注解自动注册为gRPC服务
 * 通过 gRPC 客户端调用下游的 UserService 进行用户验证
 */
@GrpcService
public class OrderServiceImpl extends OrderServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    /**
     * gRPC 客户端，调用下游的 UserService (端口 9092)
     * 使用 @Lazy 实现懒加载，避免启动时立即连接
     */
    @GrpcClient("user-service")
    @org.springframework.context.annotation.Lazy
    private UserServiceGrpc.UserServiceBlockingStub userServiceStub;

    private final Map<String, Order> orderDb = new ConcurrentHashMap<>();
    private final Map<String, List<String>> userOrdersDb = new ConcurrentHashMap<>();

    /**
     * 订单数据模型
     */
    public record Order(String orderId, String userId, String productName, double amount, String status) {
    }

    /**
     * 初始化订单数据
     */
    @PostConstruct
    void initOrderData() {
        // 初始化一些测试订单数据
        String orderId1 = "order" + UUID.randomUUID().toString().substring(0, 8);
        Order order1 = new Order(orderId1, "user1", "iPhone 15", 999.99, "PENDING");
        orderDb.put(orderId1, order1);
        userOrdersDb.computeIfAbsent("user1", k -> new ArrayList<>()).add(orderId1);

        logger.info("Order Service (Upstream) initialized with {} orders", orderDb.size());
    }

    @Override
    public void createOrder(CreateOrderRequest request, StreamObserver<CreateOrderResponse> responseObserver) {
        logger.info("Order Service (Upstream) received CreateOrder request for user_id: {}, product: {}",
                request.getUserId(), request.getProductName());

        try {
            // 调用下游的 UserService 验证用户
            logger.info("Order Service (Upstream) calling downstream User Service to validate user: {}", request.getUserId());
            GetUserRequest userRequest = GetUserRequest.newBuilder()
                    .setUserId(request.getUserId())
                    .build();

            GetUserResponse userResponse = userServiceStub.getUser(userRequest);

            if (!userResponse.getIsActive()) {
                logger.warn("Order Service (Upstream): User not found or inactive for user_id: {}", request.getUserId());
                CreateOrderResponse response = CreateOrderResponse.newBuilder()
                        .setSuccess(false)
                        .setMessage("User not found or inactive")
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                return;
            }

            logger.info("Order Service (Upstream): User validation successful for user: {}", userResponse.getName());

            // 创建订单
            String orderId = "order" + UUID.randomUUID().toString().substring(0, 8);
            Order newOrder = new Order(orderId, request.getUserId(), request.getProductName(),
                    request.getAmount(), "PENDING");
            orderDb.put(orderId, newOrder);
            userOrdersDb.computeIfAbsent(request.getUserId(), k -> new ArrayList<>()).add(orderId);

            logger.info("Order Service (Upstream) created new order: {} for user: {}", orderId, userResponse.getName());

            CreateOrderResponse response = CreateOrderResponse.newBuilder()
                    .setOrderId(orderId)
                    .setSuccess(true)
                    .setMessage("Order created successfully for user: " + userResponse.getName())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (StatusRuntimeException e) {
            logger.error("Order Service (Upstream): Failed to validate user: {}", e.getStatus());
            CreateOrderResponse response = CreateOrderResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Failed to validate user: " + e.getStatus().getDescription())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<GetOrderResponse> responseObserver) {
        logger.info("Order Service (Upstream) received GetOrder request for order_id: {}", request.getOrderId());

        Order order = orderDb.get(request.getOrderId());
        GetOrderResponse.Builder responseBuilder = GetOrderResponse.newBuilder();

        if (order != null) {
            responseBuilder
                    .setOrderId(order.orderId())
                    .setUserId(order.userId())
                    .setProductName(order.productName())
                    .setAmount(order.amount())
                    .setStatus(order.status());
            logger.info("Order Service (Upstream) sending response for order: {}", order.orderId());
        } else {
            logger.warn("Order Service (Upstream): Order not found for order_id: {}", request.getOrderId());
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Order not found: " + request.getOrderId())
                    .asRuntimeException());
            return;
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getUserOrders(GetUserOrdersRequest request, StreamObserver<GetUserOrdersResponse> responseObserver) {
        logger.info("Order Service (Upstream) received GetUserOrders request for user_id: {}", request.getUserId());

        try {
            // 验证用户存在
            GetUserRequest userRequest = GetUserRequest.newBuilder()
                    .setUserId(request.getUserId())
                    .build();

            GetUserResponse userResponse = userServiceStub.getUser(userRequest);

            if (!userResponse.getIsActive()) {
                logger.warn("Order Service (Upstream): User not found for getUserOrders: {}", request.getUserId());
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("User not found: " + request.getUserId())
                        .asRuntimeException());
                return;
            }

            logger.info("Order Service (Upstream): Found user: {} for orders query", userResponse.getName());

            // 获取用户的所有订单
            List<String> userOrderIds = userOrdersDb.getOrDefault(request.getUserId(), new ArrayList<>());
            int orderCount = 0;

            for (String orderId : userOrderIds) {
                Order order = orderDb.get(orderId);
                if (order != null) {
                    GetUserOrdersResponse response = GetUserOrdersResponse.newBuilder()
                            .setOrderId(order.orderId())
                            .setProductName(order.productName())
                            .setAmount(order.amount())
                            .setStatus(order.status())
                            .build();
                    responseObserver.onNext(response);
                    orderCount++;
                }
            }

            logger.info("Order Service (Upstream) sent {} orders for user: {}", orderCount, userResponse.getName());
            responseObserver.onCompleted();

        } catch (StatusRuntimeException e) {
            logger.error("Order Service (Upstream): Failed to validate user for getUserOrders: {}", e.getStatus());
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to validate user: " + e.getStatus().getDescription())
                    .asRuntimeException());
        }
    }
}
