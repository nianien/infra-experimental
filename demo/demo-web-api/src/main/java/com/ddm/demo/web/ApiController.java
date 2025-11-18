package com.ddm.demo.web;

import com.ddm.demo.proto.order.*;
import com.ddm.demo.proto.user.*;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.ddm.chaos.annotation.Conf;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web API 控制器，提供 HTTP RESTful 接口封装 gRPC 服务调用。
 * 
 * <p>该控制器提供以下功能：
 * <ul>
 *   <li>用户管理：获取用户信息、创建用户</li>
 *   <li>订单管理：获取订单信息、创建订单、查询用户订单列表</li>
 *   <li>健康检查：检查服务状态和 gRPC 服务连接</li>
 * </ul>
 * 
 * <p><strong>异常处理：</strong>
 * <ul>
 *   <li>{@link StatusRuntimeException}：gRPC 调用失败，返回 400 Bad Request</li>
 *   <li>其他异常：返回 500 Internal Server Error</li>
 * </ul>
 * 
 * <p><strong>API 端点：</strong>
 * <ul>
 *   <li>{@code GET /api/}：首页，返回 API 信息</li>
 *   <li>{@code GET /api/health}：健康检查</li>
 *   <li>{@code GET /api/users/{userId}}：获取用户信息</li>
 *   <li>{@code POST /api/users}：创建用户</li>
 *   <li>{@code GET /api/orders/{orderId}}：获取订单信息</li>
 *   <li>{@code POST /api/orders}：创建订单</li>
 *   <li>{@code GET /api/users/{userId}/orders}：获取用户订单列表</li>
 *   <li>{@code GET /api/options}：获取动态配置信息（ConfigBean）</li>
 * </ul>
 * 
 * @author liyifei
 * @since 1.0
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    /**
     * 用户服务 gRPC 客户端 Stub。
     */
    @GrpcClient("user-service")
    private UserServiceGrpc.UserServiceBlockingStub userServiceStub;

    /**
     * 订单服务 gRPC 客户端 Stub。
     */
    @GrpcClient("order-service")
    private OrderServiceGrpc.OrderServiceBlockingStub orderServiceStub;

    /**
     * 配置 Bean，用于测试动态配置注入。
     */
    @Autowired(required = false)
    private ConfigBean configBean;

    /**
     * 首页，返回 API 基本信息。
     * 
     * @return API 信息 Map，包含服务名称、版本、描述和端点列表
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
                "health", "/api/health",
                "options", "/api/options"
        ));
        return response;
    }

    /**
     * 健康检查接口，检查服务状态和 gRPC 服务连接。
     * 
     * <p>该接口会尝试调用 user-service 和 order-service 进行连通性测试。
     * 即使 gRPC 调用失败，也会返回健康状态，只是标记对应的服务为 DOWN。
     * 
     * @return 健康状态 Map，包含整体状态、时间戳和各个服务的状态
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("timestamp", System.currentTimeMillis());

        // 测试 gRPC 服务连接
        Map<String, String> services = new HashMap<>();
        
        // 检查用户服务
        services.put("user-service", checkServiceHealth("user-service", () -> {
            GetUserRequest request = GetUserRequest.newBuilder()
                    .setUserId("health-check")
                    .build();
            userServiceStub.getUser(request);
        }));
        
        // 检查订单服务
        services.put("order-service", checkServiceHealth("order-service", () -> {
            GetOrderRequest request = GetOrderRequest.newBuilder()
                    .setOrderId("health-check")
                    .build();
            orderServiceStub.getOrder(request);
        }));

        response.put("services", services);
        return response;
    }
    
    /**
     * 检查单个 gRPC 服务的健康状态。
     * 
     * @param serviceName 服务名称
     * @param healthCheck 健康检查逻辑（Runnable）
     * @return 服务状态字符串，"UP" 表示正常，"DOWN: {error}" 表示异常
     */
    private String checkServiceHealth(String serviceName, Runnable healthCheck) {
        try {
            healthCheck.run();
            return "UP";
        } catch (Exception e) {
            log.debug("Health check failed for service '{}': {}", serviceName, e.getMessage());
            return "DOWN: " + e.getMessage();
        }
    }

    /**
     * 获取用户信息。
     * 
     * @param userId 用户 ID
     * @return 用户信息 Map，包含 userId、name、email、isActive
     * @throws StatusRuntimeException 如果 gRPC 调用失败
     */
    @GetMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> getUser(@PathVariable String userId) {
        log.debug("Getting user: {}", userId);
        
        return executeGrpcCall(() -> {
            GetUserRequest request = GetUserRequest.newBuilder()
                    .setUserId(userId)
                    .build();
            GetUserResponse response = userServiceStub.getUser(request);

            Map<String, Object> result = new HashMap<>();
            result.put("userId", response.getUserId());
            result.put("name", response.getName());
            result.put("email", response.getEmail());
            result.put("isActive", response.getIsActive());
            return result;
        }, "获取用户信息失败: userId=" + userId);
    }

    /**
     * 创建用户。
     * 
     * @param userRequest 用户请求 Map，必须包含 name 和 email 字段
     * @return 创建结果 Map，包含 userId、success、message
     */
    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, String> userRequest) {
        String name = userRequest.get("name");
        String email = userRequest.get("email");
        
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(createErrorResponse("参数错误", "name 不能为空"));
        }
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(createErrorResponse("参数错误", "email 不能为空"));
        }
        
        log.debug("Creating user: name={}, email={}", name, email);
        
        return executeGrpcCall(() -> {
            CreateUserRequest request = CreateUserRequest.newBuilder()
                    .setName(name)
                    .setEmail(email)
                    .build();
            CreateUserResponse response = userServiceStub.createUser(request);

            Map<String, Object> result = new HashMap<>();
            result.put("userId", response.getUserId());
            result.put("success", response.getSuccess());
            result.put("message", response.getMessage());
            return result;
        }, "创建用户失败: name=" + name + ", email=" + email);
    }

    /**
     * 获取订单信息。
     * 
     * @param orderId 订单 ID
     * @return 订单信息 Map，包含 orderId、userId、productName、amount、quantity、status、createdAt
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String orderId) {
        log.debug("Getting order: {}", orderId);
        
        return executeGrpcCall(() -> {
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
            return result;
        }, "获取订单信息失败: orderId=" + orderId);
    }

    /**
     * 创建订单。
     * 
     * @param orderRequest 订单请求 Map，必须包含 userId、productName、amount，可选包含 quantity（默认 1）
     * @return 创建结果 Map，包含 orderId、success、message
     */
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody Map<String, Object> orderRequest) {
        try {
            String userId = (String) orderRequest.get("userId");
            String productName = (String) orderRequest.get("productName");
            Object amountObj = orderRequest.get("amount");
            
            // 参数校验
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.badRequest().body(createErrorResponse("参数错误", "userId 不能为空"));
            }
            if (productName == null || productName.isBlank()) {
                return ResponseEntity.badRequest().body(createErrorResponse("参数错误", "productName 不能为空"));
            }
            if (amountObj == null) {
                return ResponseEntity.badRequest().body(createErrorResponse("参数错误", "amount 不能为空"));
            }
            
            double amount = ((Number) amountObj).doubleValue();
            int quantity = orderRequest.containsKey("quantity") ?
                    ((Number) orderRequest.get("quantity")).intValue() : 1;

            log.debug("Creating order: userId={}, productName={}, quantity={}, amount={}", 
                    userId, productName, quantity, amount);

            return executeGrpcCall(() -> {
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
                return result;
            }, "创建订单失败: userId=" + userId + ", productName=" + productName);
        } catch (ClassCastException e) {
            log.warn("Invalid request parameter provider", e);
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("参数类型错误", "amount 或 quantity 必须是数字类型"));
        }
    }

    /**
     * 获取用户订单列表。
     * 
     * @param userId 用户 ID
     * @return 订单列表 Map，包含 userId、orders 数组和 totalOrders
     */
    @GetMapping("/users/{userId}/orders")
    public ResponseEntity<Map<String, Object>> getUserOrders(@PathVariable String userId) {
        log.debug("Getting orders for user: {}", userId);
        
        return executeGrpcCall(() -> {
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
            return result;
        }, "获取用户订单列表失败: userId=" + userId);
    }

    /**
     * 获取动态配置信息（ConfigBean）。
     * 
     * <p>该接口返回通过 {@code @Conf} 注解注入的 Supplier 配置值。
     * 返回结构化的配置信息，包含配置键、当前值、默认值和元数据。
     * 
     * <p>返回格式：
     * <pre>{@code
     * {
     *   "timestamp": 1234567890,
     *   "configs": [
     *     {
     *       "key": "demo.name",
     *       "value": "当前值",
     *       "defaultValue": "这是默认值",
     *       "usingDefault": false,
     *       "type": "String"
     *     },
     *     {
     *       "key": "demo.age",
     *       "value": 25,
     *       "defaultValue": "-1",
     *       "usingDefault": false,
     *       "type": "Integer"
     *     }
     *   ],
     *   "summary": {
     *     "total": 2,
     *     "usingDefault": 0,
     *     "usingCustom": 2
     *   }
     * }
     * }</pre>
     * 
     * @return 配置信息 Map，包含配置列表、摘要和时间戳
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        log.debug("Getting configuration from ConfigBean");
        
        if (configBean == null) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "ConfigBean not found");
            error.put("message", "ConfigBean is not available in Spring context");
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        }
        
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("timestamp", System.currentTimeMillis());

            List<Map<String, Object>> configs = new ArrayList<>();
            int usingDefaultCount = 0;
            int usingCustomCount = 0;

            // 通过反射遍历 ConfigBean 中的 @Conf Supplier 字段，动态打印
            for (Field field : ConfigBean.class.getDeclaredFields()) {
                Conf conf = field.getAnnotation(Conf.class);
                if (conf == null) {
                    continue;
                }
                if (!java.util.function.Supplier.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                field.setAccessible(true);
                Object valueObj = null;
                try {
                    @SuppressWarnings("unchecked")
                    java.util.function.Supplier<Object> supplier = (java.util.function.Supplier<Object>) field.get(configBean);
                    if (supplier != null) {
                        valueObj = supplier.get();
                    }
                } catch (Exception ex) {
                    log.warn("Failed to get config value for key='{}'", conf.key(), ex);
                }

                String defaultValue = conf.defaultValue();
                boolean usingDefault = (valueObj == null) || String.valueOf(valueObj).equals(defaultValue);
                if (usingDefault) {
                    usingDefaultCount++;
                } else {
                    usingCustomCount++;
                }

                // 解析字段泛型，推断类型名称
                String typeName = "Object";
                try {
                    Type gtype = field.getGenericType();
                    if (gtype instanceof ParameterizedType pt) {
                        Type[] args = pt.getActualTypeArguments();
                        if (args.length == 1) {
                            String t = args[0].getTypeName();
                            typeName =args[0].getTypeName();
                        }
                    } else if (valueObj != null) {
                        typeName = valueObj.getClass().getSimpleName();
                    }
                } catch (Exception ignore) {
                }

                Map<String, Object> row = new HashMap<>();
                row.put("key", conf.key());
                row.put("value", valueObj);
                row.put("defaultValue", defaultValue);
                row.put("usingDefault", usingDefault);
                row.put("type", typeName);
                configs.add(row);
            }

            // 添加配置列表
            result.put("configs", configs);

            // 添加摘要信息
            Map<String, Object> summary = new HashMap<>();
            summary.put("total", configs.size());
            summary.put("usingDefault", usingDefaultCount);
            summary.put("usingCustom", usingCustomCount);
            result.put("summary", summary);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to get configuration values from ConfigBean", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "获取配置失败");
            error.put("message", e.getMessage());
            error.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }


    /**
     * 执行 gRPC 调用并统一处理异常。
     * 
     * @param grpcCall gRPC 调用逻辑，返回结果 Map
     * @param errorContext 错误上下文描述，用于日志记录
     * @return ResponseEntity，成功返回 200 OK，失败返回错误响应
     */
    private ResponseEntity<Map<String, Object>> executeGrpcCall(
            java.util.function.Supplier<Map<String, Object>> grpcCall,
            String errorContext) {
        try {
            Map<String, Object> result = grpcCall.get();
            return ResponseEntity.ok(result);
        } catch (StatusRuntimeException e) {
            log.warn("gRPC call failed [{}]: status={}, description={}", 
                    errorContext, e.getStatus().getCode(), e.getStatus().getDescription());
            return ResponseEntity.badRequest()
                    .body(createGrpcErrorResponse(e));
        } catch (Exception e) {
            log.error("Unexpected error [{}]", errorContext, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("服务内部错误", e.getMessage()));
        }
    }

    /**
     * 创建 gRPC 错误响应。
     * 
     * @param e StatusRuntimeException 异常
     * @return 错误响应 Map
     */
    private Map<String, Object> createGrpcErrorResponse(StatusRuntimeException e) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", "gRPC服务调用失败");
        error.put("status", e.getStatus().getCode().name());
        String description = e.getStatus().getDescription();
        error.put("message", description != null ? description : e.getStatus().getCode().name());
        return error;
    }

    /**
     * 创建通用错误响应。
     * 
     * @param error 错误类型
     * @param message 错误消息
     * @return 错误响应 Map
     */
    private Map<String, Object> createErrorResponse(String error, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", error);
        response.put("message", message);
        return response;
    }
}
