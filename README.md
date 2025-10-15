# Argus Spring Boot Starter 与 Demo 使用指南

Argus 是一个 Spring Boot Starter，在 AWS ECS 上实现了自研的链路追踪与“泳道路由（lane-aware routing）”，并完全兼容 W3C Trace Context 标准：
- 通过 HTTP/gRPC 头 `traceparent` 和 `tracestate` 实现链路追踪与上下文传递
- 在 `tracestate` 的 vendor 成员中用 `ctx=lane:<laneName>` 透传泳道
- 当 gRPC 目标地址使用 `cloud:///...` 协议时，按泳道优先、默认兜底的负载均衡策略进行路由；当使用 `dns:///...` 或直连时，按系统默认策略

本仓库还包含 demo 模块，演示本地与 ECS 环境下如何使用。

---

### 目录
- 概览与特性
- 追踪与泳道透传
- 路由与负载均衡
- ECS/Cloud Map 集成
- 快速上手（依赖与自动配置）
- 配置示例（本地与 ECS）
- Demo 运行指南
- 版本与环境要求

---

## 概览与特性
- 链路追踪：兼容 W3C Trace Context
  - 入站解析/出站写回 `traceparent`、`tracestate`
  - MDC 注入 `traceId`/`spanId`/`lane` 便于日志关联
- 泳道路由：在 gRPC 客户端对下游按泳道优先轮询
  - `cloud:///service.namespace[:port]` 开启泳道感知；`dns:///` 使用默认
- ECS 集成：
  - 仅当存在 `ECS_CONTAINER_METADATA_URI_V4` 时启用
  - 自动注册 Cloud Map NameResolver，解析实例并携带 lane 属性
  - 可选实例注册器，将当前实例按私网 IP/端口/lane 注册到 Cloud Map
- 开箱即用：作为 Spring Boot Starter 自动装配，可零侵入集成

---

##

## 追踪与泳道透传

- 支持的头：`traceparent`、`tracestate`

- HTTP 入站：解析并回写标准头；MDC 注入

- gRPC 出站：根据当前 Trace 派生 nextHop，写出站头；空 lane 表示清除

- TraceInfo：沿用上游 `traceId/flags/lane`，生成新 `spanId`；无上游时创建根 Trace

- lane 的来源与格式：`tracestate: ctx=lane:<laneName>`
  - 入站解析：若 `tracestate` 中没有，则 `lane=null`
  - 出站写回：当 lane 为空或空白时清除 `ctx=lane` 成员

提示：若需要在入口（如 API 网关）指定泳道，只需按上述格式注入 `tracestate` 头即可被下游链路自动透传与路由。

---

## 路由与负载均衡

- NameResolver 与地址协议：支持 `cloud:///service.namespace[:port]`

- lane 感知轮询器：同 lane 优先，默认兜底；均空时报 UNAVAILABLE

- 选择逻辑：优先同 lane 的 READY 子通道，否则使用默认桶；轮询选择

- 协议行为：
  - `cloud:///...`：禁用 TXT/serviceConfig 覆盖，强制使用 `lane_round_robin`
  - `dns:///...` 或直连：保持默认（可通过 service config 自定义）

---

## ECS/Cloud Map 集成

- 启用条件：仅在容器环境提供 `ECS_CONTAINER_METADATA_URI_V4` 时加载 ECS 自动配置
- 解析与刷新：基于 AWS SDK `ServiceDiscoveryClient.discoverInstances` 获取实例列表，并在 `EquivalentAddressGroup.Attributes` 中设置 lane
- 注册（可选）：`EcsRegistrar` 在应用启动完成后，将本实例按 IP/端口/lane 注册至 Cloud Map

脚本辅助：
- `scripts/cloud_map.sh` 用于枚举 ECS 服务与 Cloud Map 信息，便于对照排查

---

## 快速上手（依赖与自动配置）

1) 引入依赖（以子模块 `demo/*` 为例，`argus` 作为 starter 已在父 POM 管理）
```xml
<dependency>
  <groupId>com.ddm</groupId>
  <artifactId>argus-spring-boot-starter</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

2) 自动装配生效
- Web 应用自动注册 `TraceFilter`；所有环境注册 `TraceInterceptor`
- 在 ECS 环境自动注册 Cloud Map NameResolver（`cloud:///` 可用）与 lane 负载均衡策略

3) 日志 MDC 格式建议（见 demo）：
```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{lane:--}] [%X{traceId:--}] %logger{36} - %msg%n"
```

---

## 配置示例（本地与 ECS）

- 本地开发（static/localhost 直连）
```yaml
grpc:
  client:
    user-service:
      address: static://localhost:8081
      negotiationType: plaintext
    order-service:
      address: static://localhost:8082
      negotiationType: plaintext
```

- ECS 环境（启用 `cloud:///` 与泳道路由），使用 `test` profile：
```yaml
grpc:
  client:
    user-service:
      address: "cloud:///user.test.local:${GRPC_SERVER_PORT:8081}"
    order-service:
      address: "cloud:///order.test.local:${GRPC_SERVER_PORT:8081}"
```

- 下游服务在 ECS 的客户端配置：
```yaml
grpc:
  client:
    user-service:
      address: "cloud:///user.test.local:${GRPC_SERVER_PORT:8081}"
```

说明：当入口请求（HTTP/gRPC）携带 `tracestate: ctx=lane:<laneName>` 时，同一条链路内的 gRPC 调用会优先路由到 lane 匹配的实例；若该泳道无 READY 实例，则回退到默认桶。

---

## Demo 运行指南

前置：JDK 21，Maven；本地演示默认直连端口 8081/8082。

- 编译
```bash
mvn -q -T1C -DskipTests package
```

- 启动 demo-user-rpc（gRPC: 8081）
```bash
mvn -q -f test/demo-user-rpc/pom.xml spring-boot:run
```

- 启动 demo-order-rpc（gRPC: 8082，作为客户端调用 user-service）
```bash
mvn -q -f test/demo-order-rpc/pom.xml spring-boot:run
```

- 启动 demo-web-api（HTTP: 8080，作为 web 入口与 gRPC 客户端）
```bash
mvn -q -f test/demo-web-api/pom.xml spring-boot:run
```

- 切换到 ECS 配置（启用 `cloud:///`）：
  - 方式一：激活 `test` profile（示例中为 `test`）
  - 方式二：在环境中提供 `ECS_CONTAINER_METADATA_URI_V4` 以启用 ECS 自动装配

- 注入泳道（可选）：从入口层（API 网关/上游）添加头：
```http
tracestate: ctx=lane:test-lane
```
随后查看日志中的 `%X{lane}` 与下游实例分布。

---

## 版本与环境要求
- Java 21+
- Spring Boot 3.3.x
- gRPC Spring Boot Starter（客户端/服务端集成）
- AWS ECS + Cloud Map（在云上启用 `cloud:///` 与泳道路由）

---

如需更多实现细节，可阅读以下核心代码：
- `com.ddm.argus.grpc.TraceFilter`、`TraceInterceptor`（W3C 头处理与 MDC）
- `com.ddm.argus.grpc.CloudMapNameResolverProvider`、`CloudMapNameResolver`（cloud:/// 解析与实例发现）
- `com.ddm.argus.grpc.LaneLoadBalancerProvider`、`LaneRoundRobinLoadBalancer`（泳道感知负载均衡）
- `com.ddm.argus.autoconfigure.*`（Starter 自动配置）
