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
      address: static://localhost:8082
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
      address: "cloud:///order.test.local:${GRPC_SERVER_PORT:8081}"
```

---

## 工作原理

### ECS 元数据获取流程
```mermaid
flowchart TD
    A[应用启动] --> B{ECS 元数据环境变量<br/>ECS_CONTAINER_METADATA_URI_V4 存在?}
    B -- 否 --> Z["退出元信息流程（非 ECS 环境）"]
    B -- 是 --> C[GET $META/task<br/>取 clusterArn, taskArn, self containerName, 其他元信息]
    C --> D{"是否配置 Cloud Map FQDN<br/>(service[.lane].namespace)?"}
    D -- 是 --> E["DNS SRV 解析<br/>优先 _grpc._tcp.fqdn，失败再 _tcp.fqdn"]
    E -- 成功 --> EO["得到 Host+Port（Cloud Map）"]
    E -- 失败 --> F[继续走 ECS API 兜底]
    D -- 否 --> F["跳过 Cloud Map，走 ECS API"]

    F --> G["DescribeTasks(clusterArn, taskArn)"]
    G --> H{"在 containers[] 找到<br/>本容器(按 name 匹配)?"}
    H -- 否 --> Z2["返回空/告警：找不到目标容器"]
    H -- 是 --> I["尝试从 container.networkInterfaces[0]<br/>取 privateIpv4Address，并记录 attachmentId"]
    I --> J{IP 为空?}
    J -- 否 --> P1[已有 IP]
    J -- 是 --> K["从 task.attachments 中按 ENI attachmentId 匹配<br/>取 details[name=privateIPv4Address] 得到 IP"]
    K --> P1[已有 IP]

    P1 --> L{"从 container.networkBindings[0]<br/>可取到 containerPort?"}
    L -- 是 --> M["得到 Port（运行时端口）"]
    L -- 否 --> N["DescribeTaskDefinition(task.taskDefinitionArn)"]
    N --> O["在 containerDefinitions[] 按名称匹配<br/>取 portMappings[0].containerPort"]
    O --> M["得到 Port（定义端口）"]

    M --> R["注入/返回 HostPort(IP, Port)"]
    EO --> R
```

### ECS 实例注册流程
```mermaid
flowchart TD
    A[ApplicationReadyEvent] --> B[获取 ECS 实例信息]
    B --> C{containerPort 是否有效?}
    C -- 否 --> D[记录警告日志<br/>跳过端口注册]
    C -- 是 --> E[构建实例属性]
    E --> F[注册到 Cloud Map]
    F --> G[注册成功]
    D --> H[完成注册流程]
    G --> H
```

### 服务实例发现和路由流程

#### 服务注册阶段
```mermaid
graph TD
    A[ECS 任务启动] --> B[EcsEnvPostProcessor]
    B --> C[获取 ECS 任务元数据]
    C --> D[解析容器端口和 Lane 信息]
    D --> E[注入到 Spring Environment]
    E --> F[ApplicationReadyEvent 触发]
    F --> G[EcsRegistrar 执行]
    G --> H[注册到 AWS Cloud Map]
    H --> I[设置实例属性: IP, Port, Lane]
```

#### 服务发现阶段
```mermaid
graph TD
    A[gRPC 客户端启动] --> B[CloudMapNameResolver 初始化]
    B --> C[解析服务名称: service.namespace]
    C --> D[创建 ServiceDiscoveryClient]
    D --> E[调用 discoverInstances API]
    E --> F[获取实例列表]
    F --> G[转换为 EquivalentAddressGroup]
    G --> H[设置 Lane 属性到 EAG]
    H --> I[去重和排序]
    I --> J[通知 LoadBalancer]
```

#### Lane-aware 路由阶段
```mermaid
graph TD
    A[gRPC 请求到达] --> B[TraceInterceptor 拦截]
    B --> C[解析 traceparent/tracestate]
    C --> D[提取 Lane 信息]
    D --> E[设置 TraceInfo 到 Context]
    E --> F[LaneRoundRobinLoadBalancer.pickSubchannel]
    F --> G{检查 Lane 信息}
    G -->|有 Lane| H[查找对应 Lane 的 READY 子通道]
    G -->|无 Lane| I[查找默认 Lane 的 READY 子通道]
    H --> J{目标 Lane 有可用实例?}
    J -->|是| K[在目标 Lane 中轮询选择]
    J -->|否| L[回退到默认 Lane]
    I --> M[在默认 Lane 中轮询选择]
    L --> N{默认 Lane 有可用实例?}
    N -->|是| O[在默认 Lane 中轮询选择]
    N -->|否| P[返回 UNAVAILABLE 错误]
    K --> Q[返回选中的子通道]
    M --> Q
    O --> Q
```

### 核心组件说明
- TraceAutoConfiguration：自动注册追踪组件；Web 环境注册 `TraceFilter`，所有环境注册 `TraceInterceptor`。
- TraceFilter：HTTP 入站解析 `traceparent`/`tracestate`，回写标准头，注入日志 MDC。
- TraceInterceptor：gRPC 入站解析、出站写入 `traceparent`/`tracestate`，透传/清理 lane 信息。
- EcsAutoConfiguration：仅在存在 `ECS_CONTAINER_METADATA_URI_V4` 时启用；注册 Cloud Map 解析器与（可选）实例注册器。
- CloudMapNameResolverProvider：支持 `cloud:///service.namespace[:port]` 协议，创建对应解析器。
- CloudMapNameResolver：从 Cloud Map 发现实例，将 lane 写入地址属性供负载均衡使用。
- GrpcLbAutoConfiguration：当目标为 `cloud:///` 时禁用服务端 service-config 覆盖，默认启用 `lane_round_robin` 策略。
- LaneLoadBalancerProvider/LaneRoundRobinLoadBalancer：按 lane 优先、默认兜底的轮询；无可用子通道时报 UNAVAILABLE。

### 路由机制详解

#### 1. 服务注册流程
1. **ECS 任务启动** - ECS 任务在指定泳道启动，任务定义包含 Lane 标签
2. **元数据获取** - `EcsEnvPostProcessor` 获取任务元数据，解析容器端口、IP、Lane 等信息
3. **Cloud Map 注册** - `EcsRegistrar` 监听 `ApplicationReadyEvent`，将实例信息注册到 AWS Cloud Map，设置属性：`IPV4`, `PORT`, `LANE`

#### 2. 服务发现流程
1. **NameResolver 初始化** - 解析目标服务：`service.namespace[:port]`，创建 `ServiceDiscoveryClient`
2. **实例发现** - 调用 `discoverInstances` API，获取所有健康实例
3. **EAG 转换** - 将实例转换为 `EquivalentAddressGroup`，设置 Lane 属性到 EAG Attributes，按 Lane、Host、Port 排序去重

#### 3. 路由选择流程
1. **请求拦截** - `TraceInterceptor` 拦截出站请求，解析 `traceparent` 和 `tracestate` header，提取 Lane 信息
2. **负载均衡选择** - `LaneRoundRobinLoadBalancer` 接收选择请求，根据 Lane 信息选择目标子通道池，使用轮询算法选择具体实例
3. **回退机制** - 优先选择指定 Lane 的实例，如果指定 Lane 无可用实例，回退到默认 Lane，如果默认 Lane 也无实例，返回 UNAVAILABLE

#### 4. 关键数据结构
- **TraceInfo**: 包含 `traceId`, `parentId`, `spanId`, `flags`, `lane` 信息
- **ScKey**: 子通道键，包含地址列表和泳道标识
- **EAG**: 等效地址组，包含地址和 Lane 属性

---

## ECS / Cloud Map 集成
- 启用条件：容器内存在 `ECS_CONTAINER_METADATA_URI_V4`。
- 地址协议：`cloud:///service.namespace[:port]`。
- 泳道注入：入口/网关可通过请求头注入 `tracestate: ctx=lane:<laneName>`；链路将自动透传并按泳道优先路由。

### 使用示例

#### 1. 服务端注册
```java
// 自动注册到 Cloud Map
@SpringBootApplication
public class MyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyServiceApplication.class, args);
    }
}
```

#### 2. 客户端调用
```java
// 设置 Lane 信息
TraceInfo traceInfo = TraceInfo.root("canary");
Context ctx = Context.current()
    .withValue(TraceContext.CTX_TRACE_INFO, traceInfo);

// gRPC 调用会自动路由到对应 Lane
MyServiceGrpc.MyServiceBlockingStub stub = 
    MyServiceGrpc.newBlockingStub(channel);
Response response = stub.myMethod(request);
```

#### 3. HTTP 请求
```bash
# 通过 tracestate header 指定 Lane
curl -H "tracestate: ctx=lane:canary" \
     http://my-service/api/endpoint
```

### 优势特性
1. **✅ Lane-aware 路由**: 基于泳道的精确路由
2. **✅ 自动服务发现**: 通过 Cloud Map 自动发现实例
3. **✅ 健康检查**: 只路由到健康的实例
4. **✅ 回退机制**: 智能回退到默认 Lane
5. **✅ 分布式追踪**: 完整的 W3C 标准追踪支持
6. **✅ 负载均衡**: 轮询算法确保负载分散
7. **✅ 动态更新**: 实例变化时自动更新路由表

---

## 配置示例一览

### 本地开发（static/localhost 直连）
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

### ECS 环境（启用 `cloud:///` 与泳道路由）
使用 `test` profile：
```yaml
spring:
  config:
    activate:
      on-profile: test

grpc:
  client:
    user-service:
      address: "cloud:///user.test.local:${GRPC_SERVER_PORT:8081}"
    order-service:
      address: "cloud:///order.test.local:${GRPC_SERVER_PORT:8081}"
```

### 下游服务在 ECS 的客户端配置
```yaml
grpc:
  client:
    user-service:
      address: "cloud:///user.test.local:${GRPC_SERVER_PORT:8081}"
```

**说明**：当入口请求（HTTP/gRPC）携带 `tracestate: ctx=lane:<laneName>` 时，同一条链路内的 gRPC 调用会优先路由到 lane 匹配的实例；若该泳道无 READY 实例，则回退到默认桶。

---

## 端口解析机制

### 端口获取优先级
1. **运行时端口** - 从 `container.networkBindings[0].containerPort` 获取
2. **定义端口** - 从 Task Definition 的 `portMappings[0].containerPort` 获取
3. **默认端口** - 如果以上都失败，使用默认端口 80

### 端口注册到 Cloud Map
- 实例注册时会同时设置 `port` 和 `grpcPort` 属性
- gRPC 客户端优先使用 `grpcPort` 属性
- 如果 `grpcPort` 为空或无效，会回退到 `port` 属性
- 最终回退到默认端口 80

### 常见端口问题
- **连接被拒绝**: 检查容器端口是否正确注册到 Cloud Map
- **端口不匹配**: 确认 Task Definition 中的端口映射配置
- **默认端口问题**: 检查是否因为端口解析失败而使用了默认端口 80

---

## 故障排查（Troubleshooting）

### 服务发现问题
- **目标是 `cloud:///...` 但未生效 lane 路由**：确认容器内存在 `ECS_CONTAINER_METADATA_URI_V4`，并检查日志是否注册了 CloudMapNameResolverProvider 与 `lane_round_robin`。
- **无实例可用**：Cloud Map 中对应服务是否存在、健康检查通过、目标命名空间与端口正确。

### 端口连接问题
- **连接被拒绝 (Connection refused)**：
  - 检查 Cloud Map 中实例的 `grpcPort` 属性是否正确
  - 确认容器端口是否与 Task Definition 中的端口映射一致
  - 查看应用日志中是否有 "containerPort is null" 的警告
- **端口不匹配**：
  - 验证 Task Definition 中的 `portMappings` 配置
  - 检查容器是否在正确的端口上监听
  - 确认 `AppProtocol: grpc` 的端口映射配置

### 链路追踪问题
- **透传失败**：确认入口是否正确注入 `traceparent`/`tracestate`，以及中间环节是否保留并传递了头部。
- **Lane 信息丢失**：检查 `tracestate: ctx=lane:<laneName>` 是否正确注入和传递。

### 调试建议
1. **检查 ECS 元数据**：访问 `http://169.254.170.2/v4/metadata` 确认容器信息
2. **查看 Cloud Map 注册**：在 AWS 控制台检查服务实例的属性和健康状态
3. **启用详细日志**：设置 `logging.level.com.ddm.argus=DEBUG` 查看详细执行流程

---

## 版本与要求
- Java 21+
- Spring Boot 3.3.x
- gRPC Spring Boot Starter 2.15.x（`net.devh.boot.grpc` 生态）
- AWS ECS + Cloud Map（在云上启用 `cloud:///` 与泳道路由）

---

如需在业务代码中强制设置/覆盖 lane，可在入口处按 `tracestate: ctx=lane:<laneName>` 规范注入，或在必要时扩展拦截器写入自定义 lane 值。

---

## Demo 运行指南

本仓库的 `demo` 模块提供了完整的使用示例，演示如何在本地和 ECS 环境下使用 Argus。

### 前置要求
- JDK 21
- Maven
- 本地演示默认直连端口 8081/8082

### 编译项目
```bash
mvn -q -T1C -DskipTests package
```

### 启动服务

#### 1. 启动 demo-user-rpc（gRPC: 8081）
```bash
mvn -q -f demo/demo-user-rpc/pom.xml spring-boot:run
```

#### 2. 启动 demo-order-rpc（gRPC: 8082，作为客户端调用 user-service）
```bash
mvn -q -f demo/demo-order-rpc/pom.xml spring-boot:run
```

#### 3. 启动 demo-web-api（HTTP: 8080，作为 web 入口与 gRPC 客户端）
```bash
mvn -q -f demo/demo-web-api/pom.xml spring-boot:run
```

### 切换到 ECS 配置（启用 `cloud:///`）
- **方式一**：激活 `test` profile（示例中为 `test`）
- **方式二**：在环境中提供 `ECS_CONTAINER_METADATA_URI_V4` 以启用 ECS 自动装配

### 注入泳道（可选）
从入口层（API 网关/上游）添加头：
```http
tracestate: ctx=lane:test-lane
```
随后查看日志中的 `%X{lane}` 与下游实例分布。

详细示例代码请参考 `demo` 模块。
