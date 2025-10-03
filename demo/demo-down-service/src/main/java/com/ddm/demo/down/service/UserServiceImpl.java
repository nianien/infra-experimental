package com.ddm.demo.down.service;

import com.ddm.demo.proto.user.*;
import com.ddm.demo.proto.user.UserServiceGrpc.UserServiceImplBase;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 用户服务实现 - 下游服务
 * 使用@GrpcService注解自动注册为gRPC服务
 */
@GrpcService
public class UserServiceImpl extends UserServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    private final Map<String, User> userDb = new HashMap<>();

    /**
     * 用户数据模型
     */
    public record User(String id, String name, String email, boolean active) {}

    /**
     * 初始化用户数据
     */
    @PostConstruct
    void initUserData() {
        userDb.put("user1", new User("user1", "张三", "zhangsan@example.com", true));
        userDb.put("user2", new User("user2", "李四", "lisi@example.com", true));
        userDb.put("user3", new User("user3", "王五", "wangwu@example.com", false));
        logger.info("User Service (Downstream) initialized with {} users", userDb.size());
    }

    @Override
    public void getUser(GetUserRequest request, StreamObserver<GetUserResponse> responseObserver) {
        logger.info("User Service (Downstream) received GetUser request for user_id: {}", request.getUserId());

        User user = userDb.get(request.getUserId());
        GetUserResponse.Builder responseBuilder = GetUserResponse.newBuilder();

        if (user != null) {
            responseBuilder
                    .setUserId(user.id())
                    .setName(user.name())
                    .setEmail(user.email())
                    .setIsActive(user.active());
            logger.info("User Service (Downstream) sending response for user: {}", user.name());
        } else {
            responseBuilder.setIsActive(false);
            logger.warn("User Service (Downstream): User not found for user_id: {}", request.getUserId());
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void createUser(CreateUserRequest request, StreamObserver<CreateUserResponse> responseObserver) {
        logger.info("User Service (Downstream) received CreateUser request for name: {}", request.getName());

        String userId = "user" + UUID.randomUUID().toString().substring(0, 8);
        User newUser = new User(userId, request.getName(), request.getEmail(), true);
        userDb.put(userId, newUser);

        CreateUserResponse response = CreateUserResponse.newBuilder()
                .setUserId(userId)
                .setSuccess(true)
                .build();

        logger.info("User Service (Downstream) created new user: {} with id: {}", newUser.name(), userId);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
