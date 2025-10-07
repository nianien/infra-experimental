# Argus Spring Boot Starter

Argus 是一个面向 Spring Boot + gRPC 的 Starter，在 AWS ECS 上实现了自研的“泳道路由（lane-aware routing）”与分布式链路追踪能力，且完全兼容 W3C Trace Context：
- Trace 通过 `traceparent` 与 `tracestate` 传播
- Lane 通过 `tracestate: ctx=lane:<laneName>` 传播
- gRPC 目标为 `cloud:///...` 时按 lane 优先路由；为 `dns:///...` 或直连时使用系统默认

---

## 功能特性
- 开箱即用的追踪：自动解析/写回 `traceparent`/`tracestate`，注入日志 MDC
- lane 感知负载均衡：同泳道优先、默认兜底的轮询策略
- ECS/Cloud Map 集成：容器内自动启用 `cloud:///` 解析与实例注册
- Spring Boot Starter：最小侵入、条件装配、与 `net.devh.boot.grpc` 生态兼容

---

## 快速上手

### 1) 添加依赖
```xml
<dependency>
  <groupId>com.ddm</groupId>
  <artifactId>argus-spring-boot-starter</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2) 基本配置
- Web 应用推荐日志格式（包含 traceId/lane）：
```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{lane:--}] [%X{traceId:--}] %logger{36} - %msg%n"
```

### 3) gRPC 客户端地址
- 本地直连（static）：
```yaml
grpc:
  client:
    order-service:
      address: static://localhost:9092
      negotiationType: plaintext
```
- 使用 Cloud Map（ECS，下述为 `test` profile）：
```yaml
spring:
  config:
    activate:
      on-profile: test

grpc:
  client:
    order-service:
      address: "cloud:///order.test.local:${GRPC_SERVER_PORT:9091}"
```

---

## 工作原理（简述）
- TraceAutoConfiguration：自动注册追踪组件；Web 环境注册 `TraceFilter`，所有环境注册 `TraceInterceptor`。
- TraceFilter：HTTP 入站解析 `traceparent`/`tracestate`，回写标准头，注入日志 MDC。
- TraceInterceptor：gRPC 入站解析、出站写入 `traceparent`/`tracestate`，透传/清理 lane 信息。
- EcsAutoConfiguration：仅在存在 `ECS_CONTAINER_METADATA_URI_V4` 时启用；注册 Cloud Map 解析器与（可选）实例注册器。
- CloudMapNameResolverProvider：支持 `cloud:///service.namespace[:port]` 协议，创建对应解析器。
- CloudMapNameResolver：从 Cloud Map 发现实例，将 lane 写入地址属性供负载均衡使用。
- GrpcLbAutoConfiguration：当目标为 `cloud:///` 时禁用服务端 service-config 覆盖，默认启用 `lane_round_robin` 策略。
- LaneLoadBalancerProvider/LaneRoundRobinLoadBalancer：按 lane 优先、默认兜底的轮询；无可用子通道时报 UNAVAILABLE。

---

## ECS / Cloud Map 集成
- 启用条件：容器内存在 `ECS_CONTAINER_METADATA_URI_V4`。
- 地址协议：`cloud:///service.namespace[:port]`。
- 泳道注入：入口/网关可通过请求头注入 `tracestate: ctx=lane:<laneName>`；链路将自动透传并按泳道优先路由。

---

## 配置示例一览
- 本地：`static://localhost:PORT` 直连
```yaml
grpc:
  client:
    user-service:
      address: static://localhost:9091
      negotiationType: plaintext
    order-service:
      address: static://localhost:9092
      negotiationType: plaintext
```

- ECS：`cloud:///service.namespace[:port]`
```yaml
grpc:
  client:
    user-service:
      address: "cloud:///user.test.local:${GRPC_SERVER_PORT:9091}"
```

---

## 故障排查（Troubleshooting）
- 目标是 `cloud:///...` 但未生效 lane 路由：确认容器内存在 `ECS_CONTAINER_METADATA_URI_V4`，并检查日志是否注册了 CloudMapNameResolverProvider 与 `lane_round_robin`。
- 透传失败：确认入口是否正确注入 `traceparent`/`tracestate`，以及中间环节是否保留并传递了头部。
- 无实例可用：Cloud Map 中对应服务是否存在、健康检查通过、目标命名空间与端口正确。

---

## 版本与要求
- Java 21+
- Spring Boot 3.3.x
- gRPC Spring Boot Starter 2.15.x（`net.devh.boot.grpc` 生态）
- AWS ECS + Cloud Map（在云上启用 `cloud:///` 与泳道路由）

---

如需在业务代码中强制设置/覆盖 lane，可在入口处按 `tracestate: ctx=lane:<laneName>` 规范注入，或在必要时扩展拦截器写入自定义 lane 值。
