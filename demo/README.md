# Demo 模块

Demo 模块提供了完整的使用示例，演示如何在本地和 ECS 环境下使用 infra-lab 中的各个组件。

## 📦 模块结构

```
demo/
├── demo-proto/          # Protocol Buffers 定义
│   ├── user.proto       # 用户服务定义
│   └── order.proto      # 订单服务定义
├── demo-user-rpc/       # 用户服务 gRPC 服务端（端口 8081）
├── demo-order-rpc/      # 订单服务 gRPC 服务端（端口 8082，作为客户端调用 user-service）
└── demo-web-api/        # Web API 服务（HTTP: 8080，作为 web 入口与 gRPC 客户端）
```

## 🚀 快速开始

### 前置要求
- JDK 21
- Maven
- MySQL（可选，用于配置中心演示）

### 编译项目
```bash
mvn -q -T1C -DskipTests package
```

### 启动服务

#### 1. 启动 demo-user-rpc（gRPC: 8081）
```bash
mvn -q -f demo/demo-user-rpc/pom.xml spring-boot:run
```

#### 2. 启动 demo-order-rpc（gRPC: 8082）
```bash
mvn -q -f demo/demo-order-rpc/pom.xml spring-boot:run
```

#### 3. 启动 demo-web-api（HTTP: 8080）
```bash
mvn -q -f demo/demo-web-api/pom.xml spring-boot:run
```

## 📚 功能演示

### Argus 分布式追踪与泳道路由

Demo 模块演示了 Argus 的以下功能：
- **链路追踪**：通过 HTTP/gRPC 头 `traceparent` 和 `tracestate` 实现链路追踪
- **泳道路由**：通过 `tracestate: ctx=lane:<laneName>` 实现泳道感知路由
- **本地环境**：使用 `static://localhost:PORT` 直连
- **ECS 环境**：使用 `cloud:///service.namespace[:port]` 进行服务发现

详细使用说明请参考 [argus/README.md](../argus/README.md)。

### Chaos 配置中心

Demo 模块演示了 Chaos 配置中心的使用：
- **配置注入**：通过 `@Conf` 注解注入配置
- **多环境配置**：支持泳道覆盖配置（variants）
- **动态配置**：支持配置的动态更新

详细使用说明请参考 [chaos/README.md](../chaos/README.md)。

## 🔧 配置说明

### Argus 配置

#### 本地开发配置

默认配置使用本地直连方式：

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

#### ECS 环境配置

切换到 ECS 配置（启用 `cloud:///` 与泳道路由）：
- **方式一**：激活 `test` profile
- **方式二**：在环境中提供 `ECS_CONTAINER_METADATA_URI_V4` 以启用 ECS 自动装配

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

#### 注入泳道（可选）

从入口层（API 网关/上游）添加头：
```http
tracestate: ctx=lane:test-lane
```

随后查看日志中的 `%X{lane}` 与下游实例分布。

### Chaos 配置中心

#### 方式一：JDBC 模式（直连数据库）

```yaml
spring:
  datasource:
    chaos:
      url: jdbc:mysql://localhost:3306/test
      username: root
      password: root
      hikari:
        maximum-pool-size: 10
        connection-timeout: 3000

chaos:
  config-center:
    profiles: [ "gray", "hotfix" ]  # 泳道标识数组，按优先级顺序排列
    ttl: 30S                        # 配置缓存刷新时间
```

#### 方式二：gRPC 模式（远程服务）

```yaml
grpc:
  client:
    chaos-service:
      address: static://localhost:9090
      negotiationType: plaintext

chaos:
  config-center:
    profiles: [ "gray", "hotfix" ]  # 泳道标识数组，按优先级顺序排列
    ttl: 30S                        # 配置缓存刷新时间
```

#### 使用 @Conf 注解注入配置

```java
@Component
@Getter
public class ConfigBean {
    @Conf(namespace = "com.ddm", group = "cfd", key = "demo.name", defaultValue = "这是默认值")
    private Supplier<String> name;
    
    @Conf(namespace = "com.ddm", group = "cfd", key = "demo.age", defaultValue = "-1")
    private Supplier<Integer> age;
    
    @Conf(namespace = "com.ddm", group = "cfd", key = "demo.whitelist", defaultValue = "u001,u002")
    private Supplier<List<String>> whitelist;
}
```

**配置说明**：
- `profiles`：泳道标识数组，用于配置项的变体匹配，按优先级顺序排列
- `ttl`：配置缓存 TTL，控制配置刷新频率
- `@Conf`：通过注解自动注入配置值，支持类型转换和默认值

## 📝 日志格式

推荐使用以下日志格式，包含 traceId 和 lane 信息：

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{lane:--}] [%X{traceId:--}] %logger{36} - %msg%n"
```

## 🧪 测试

### 测试链路追踪

1. 启动所有服务
2. 访问 `http://localhost:8080/api/orders`
3. 查看日志中的 `traceId` 和 `lane` 信息

### 测试泳道路由

1. 在 ECS 环境中部署服务
2. 通过 API 网关或上游服务注入 `tracestate: ctx=lane:test-lane` 头
3. 观察请求是否路由到对应的泳道实例

### 测试配置中心

1. 确保数据库已初始化（JDBC 模式）或配置中心服务已启动（gRPC 模式）
2. 在配置中心创建配置项：
   - namespace: `com.ddm`
   - group: `cfd`
   - key: `demo.name`、`demo.age`、`demo.whitelist`
3. 启动 demo-web-api 服务
4. 访问配置相关的 API 端点，验证配置是否正确注入
5. 修改配置中心的值，观察配置是否自动刷新（根据 TTL 设置）

## 📖 相关文档

- [Argus 使用指南](../argus/README.md) - 分布式追踪与泳道路由
- [Chaos 使用指南](../chaos/README.md) - 配置中心
- [项目根目录 README](../README.md) - 项目概览
