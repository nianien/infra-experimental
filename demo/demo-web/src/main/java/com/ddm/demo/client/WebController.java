package com.ddm.demo.client;

import com.ddm.argus.trace.TraceContext;
import com.ddm.demo.proto.order.*;
import com.ddm.demo.proto.user.*;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web控制器
 * <p>
 * 提供HTTP接口调用gRPC服务
 */
@RestController
@RequestMapping("/api")
public class WebController {
    private static final Logger logger = LoggerFactory.getLogger(WebController.class);

    @GrpcClient("user-service")
    private UserServiceGrpc.UserServiceBlockingStub userServiceStub;

    @GrpcClient("order-service")
    private OrderServiceGrpc.OrderServiceBlockingStub orderServiceStub;

    /**
     * 首页
     */
    @GetMapping("/")
    public Map<String, Object> home() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "Atlas Demo Web API");
        response.put("version", "1.0.0");
        response.put("description", "gRPC客户端Web接口");
        response.put("endpoints", Map.of(
                "users", "/api/users",
                "orders", "/api/orders",
                "health", "/api/health"
        ));
        return response;
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", System.currentTimeMillis());

        // 测试gRPC服务连接
        Map<String, String> services = new HashMap<>();
        try {
            GetUserRequest request = GetUserRequest.newBuilder().setUserId("health-check").build();
            userServiceStub.getUser(request);
            services.put("user-service", "UP");
        } catch (Exception e) {
            services.put("user-service", "DOWN: " + e.getMessage());
        }

        try {
            GetOrderRequest request = GetOrderRequest.newBuilder().setOrderId("health-check").build();
            orderServiceStub.getOrder(request);
            services.put("order-service", "UP");
        } catch (Exception e) {
            services.put("order-service", "DOWN: " + e.getMessage());
        }

        response.put("services", services);
        return response;
    }

    /**
     * 获取用户信息
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable String userId) {
        try {
            // 调试：检查当前的 trace context
            String currentTraceId = TraceContext.CTX_TRACE_ID.get();
            String currentSpanId = TraceContext.CTX_SPAN_ID.get();
            String mdcTraceId = MDC.get(TraceContext.MDC_TRACE_ID);
            logger.info("HTTP API: Getting user {} - Context traceId={}, spanId={}, MDC traceId={}", 
                       userId, currentTraceId, currentSpanId, mdcTraceId);

            GetUserRequest request = GetUserRequest.newBuilder()
                    .setUserId(userId)
                    .build();
            GetUserResponse response = userServiceStub.getUser(request);

            Map<String, Object> result = new HashMap<>();
            result.put("userId", response.getUserId());
            result.put("name", response.getName());
            result.put("email", response.getEmail());
            result.put("isActive", response.getIsActive());

            return ResponseEntity.ok(result);
        } catch (StatusRuntimeException e) {
            logger.warn("gRPC call failed: {}", e.getStatus());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "gRPC服务调用失败");
            error.put("status", e.getStatus().getCode().name());
            error.put("message", e.getStatus().getDescription());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务内部错误");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 创建用户
     */
    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, String> userRequest) {
        try {
            String name = userRequest.get("name");
            String email = userRequest.get("email");

            logger.info("HTTP API: Creating user {} ({})", name, email);

            CreateUserRequest request = CreateUserRequest.newBuilder()
                    .setName(name)
                    .setEmail(email)
                    .build();
            CreateUserResponse response = userServiceStub.createUser(request);

            Map<String, Object> result = new HashMap<>();
            result.put("userId", response.getUserId());
            result.put("success", response.getSuccess());
            result.put("message", response.getMessage());

            return ResponseEntity.ok(result);
        } catch (StatusRuntimeException e) {
            logger.warn("gRPC call failed: {}", e.getStatus());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "gRPC服务调用失败");
            error.put("status", e.getStatus().getCode().name());
            error.put("message", e.getStatus().getDescription());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务内部错误");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 获取订单信息
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        try {
            logger.info("HTTP API: Getting order {}", orderId);

            GetOrderRequest request = GetOrderRequest.newBuilder()
                    .setOrderId(orderId)
                    .build();
            GetOrderResponse response = orderServiceStub.getOrder(request);

            Map<String, Object> result = new HashMap<>();
            result.put("orderId", response.getOrderId());
            result.put("userId", response.getUserId());
            result.put("productName", response.getProductName());
            result.put("amount", response.getAmount());
            result.put("quantity", response.getQuantity());
            result.put("status", response.getStatus());
            result.put("createdAt", response.getCreatedAt());

            return ResponseEntity.ok(result);
        } catch (StatusRuntimeException e) {
            logger.warn("gRPC call failed: {}", e.getStatus());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "gRPC服务调用失败");
            error.put("status", e.getStatus().getCode().name());
            error.put("message", e.getStatus().getDescription());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务内部错误");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 创建订单
     */
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> orderRequest) {
        try {
            String userId = (String) orderRequest.get("userId");
            String productName = (String) orderRequest.get("productName");
            Double amount = ((Number) orderRequest.get("amount")).doubleValue();
            Integer quantity = orderRequest.containsKey("quantity") ?
                    ((Number) orderRequest.get("quantity")).intValue() : 1;

            logger.info("HTTP API: Creating order for user {} - {} x{} ({})",
                    userId, productName, quantity, amount);

            CreateOrderRequest request = CreateOrderRequest.newBuilder()
                    .setUserId(userId)
                    .setProductName(productName)
                    .setAmount(amount)
                    .setQuantity(quantity)
                    .build();
            CreateOrderResponse response = orderServiceStub.createOrder(request);

            Map<String, Object> result = new HashMap<>();
            result.put("orderId", response.getOrderId());
            result.put("success", response.getSuccess());
            result.put("message", response.getMessage());

            return ResponseEntity.ok(result);
        } catch (StatusRuntimeException e) {
            logger.warn("gRPC call failed: {}", e.getStatus());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "gRPC服务调用失败");
            error.put("status", e.getStatus().getCode().name());
            error.put("message", e.getStatus().getDescription());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务内部错误");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 获取用户订单列表
     */
    @GetMapping("/users/{userId}/orders")
    public ResponseEntity<Map<String, Object>> getUserOrders(@PathVariable String userId) {
        try {
            logger.info("HTTP API: Getting orders for user {}", userId);

            GetUserOrdersRequest request = GetUserOrdersRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            List<Map<String, Object>> orders = new ArrayList<>();
            orderServiceStub.getUserOrders(request).forEachRemaining(orderResponse -> {
                Map<String, Object> order = new HashMap<>();
                order.put("orderId", orderResponse.getOrderId());
                order.put("productName", orderResponse.getProductName());
                order.put("amount", orderResponse.getAmount());
                order.put("quantity", orderResponse.getQuantity());
                order.put("status", orderResponse.getStatus());
                order.put("createdAt", orderResponse.getCreatedAt());
                orders.add(order);
            });

            Map<String, Object> result = new HashMap<>();
            result.put("userId", userId);
            result.put("orders", orders);
            result.put("totalOrders", orders.size());

            return ResponseEntity.ok(result);
        } catch (StatusRuntimeException e) {
            logger.warn("gRPC call failed: {}", e.getStatus());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "gRPC服务调用失败");
            error.put("status", e.getStatus().getCode().name());
            error.put("message", e.getStatus().getDescription());
            return ResponseEntity.badRequest().body(error);
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "服务内部错误");
            error.put("message", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
