# Infra Experimental

基于 Spring Boot 3 与 gRPC 的基础设施实验项目。当前仅提供一个统一的 Starter：
- Argus（包含分布式链路追踪 + 基于 AWS ECS/Cloud Map 的服务发现与 gRPC 解析/负载均衡）
- Demo 模块（web/order/user）与 proto 定义

## 目录结构
```
infra-experimental/
├── argus/                  # 统一 Starter（Tracing + ECS/Cloud Map）
├── demo/
│   ├── demo-proto/         # protobuf 与 gRPC 代码生成
│   ├── demo-user-service/  # 用户服务（gRPC）
│   ├── demo-order-service/ # 订单服务（gRPC）
│   └── demo-web/           # Web 应用（调用下游 gRPC）
└── scripts/                # 构建与运行脚本
```

## 版本与技术栈
- Java 21
- Spring Boot 3.3.4
- gRPC 1.64.0（由父 POM 统一 BOM 管理）
- Protobuf 3.25.3
- AWS SDK v2（Hermes 仅在 ECS 环境下生效）

## Argus（统一 Starter）
能力概览：
- 分布式链路追踪：遵循 W3C Trace Context（traceparent），提供 gRPC 拦截器与 Servlet Filter，自动透传并写入 MDC
- ECS/Cloud Map：在 ECS 环境下（检测 `ECS_CONTAINER_METADATA_URI_V4`）注入实例元数据、可选激活 profiles（来自 Service Tag），并注册自定义 gRPC NameResolver（Cloud Map 优先、DNS 回退）及可配置的客户端负载均衡策略
- Spring Boot 3 自动装配（基于 `AutoConfiguration.imports`）

引入：
```xml
<dependency>
  <groupId>com.ddm</groupId>
  <artifactId>argus-spring-boot-starter</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

链路日志（可选，包含 traceId/spanId）：
```yaml
logging:
  level.com.ddm.argus: DEBUG
  pattern.console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId}] %logger{36} - %msg%n"
```

ECS/Cloud Map 启用条件：
- 环境变量 `ECS_CONTAINER_METADATA_URI_V4` 存在时自动生效；否则自动跳过（对本地/非 ECS 环境零侵入）

gRPC 解析与负载均衡（示例，按需启用与调整）：
```yaml
# 仅示例，实际前缀与键名以代码实现为准（保持向后兼容）
argus:
  cloudmap:
    resolver:
      enabled: true        # 启用 Cloud Map + DNS 混合解析
      refreshInterval: 10s # 解析刷新间隔
      logDnsFallback: true # Cloud Map 失败时是否记录 DNS 回退
  grpc:
    loadbalancer:
      enabled: true
      policy: round_robin
      clientPolicy:
        user-service: pick_first
```

## Hermes（ECS/Cloud Map Starter）
功能：
- 从 ECS Metadata v4 读取任务/服务信息
- 可选激活 Spring Profiles（来自 Service Tag: profile）
- 为 gRPC 注册自定义 NameResolver（Cloud Map 优先，DNS 回退）

激活条件：
- 环境变量 `ECS_CONTAINER_METADATA_URI_V4` 存在时自动生效（非 ECS 环境自动跳过）

引入：
```xml
<dependency>
  <groupId>com.ddm</groupId>
  <artifactId>hermes-spring-boot-starter</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

主要配置（可选）：
```yaml
hermes:
  cloudmap:
    resolver:
      enabled: true
      refreshInterval: 10s
      logDnsFallback: true
  grpc:
    loadbalancer:
      enabled: true
      policy: round_robin
      clientPolicy:
        user-service: pick_first
```

## 快速开始
构建：
```bash
mvn clean install -DskipTests
```

运行 demo：
```bash
# 用户服务（9091）
cd demo/demo-user-service && mvn spring-boot:run

# 订单服务（9092）
cd demo/demo-order-service && mvn spring-boot:run

# Web（8080）
cd demo/demo-web && mvn spring-boot:run
```

访问：
- Web: http://localhost:8080
- gRPC: localhost:9091（user）、localhost:9092（order）

## 模块依赖建议
- 应用层选择 gRPC 运行时（如 grpc-netty-shaded）与 `net.devh` 的 gRPC Spring Boot Starters
- Argus 作为 Starter 仅暴露最小 API 依赖，Spring/Servlet 标注 provided，避免与应用实现绑定

## 许可证
MIT（见 LICENSE）

## 致谢
- Spring Boot / Spring Framework
- gRPC / Protocol Buffers
- AWS SDK v2

—— 本项目仅用于学习与实验，请在生产环境前充分评估与测试 ——