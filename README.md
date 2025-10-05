# Infrastructure Experimental

一个基于 Spring Boot 3.x 和 gRPC 的微服务基础设施实验项目，包含分布式链路追踪、服务发现和演示应用。

## 🏗️ 项目架构

```
infra-experimental/
├── argus/                    # 分布式链路追踪框架
│   ├── src/main/java/        # 核心追踪组件
│   └── src/main/resources/   # 自动配置文件
├── hermes/                   # AWS 服务发现组件
│   └── src/main/java/        # ECS 服务发现实现
├── demo/                     # 演示应用集合
│   ├── demo-proto/          # Protocol Buffers 定义
│   ├── demo-user-service/   # 用户服务 (gRPC)
│   ├── demo-order-service/  # 订单服务 (gRPC)
│   └── demo-web/            # Web 前端应用
└── scripts/                  # 部署和运维脚本
```

## 🚀 核心组件

### Argus - 分布式链路追踪

**功能特性：**
- 基于 W3C Trace Context 标准的链路追踪
- 支持 gRPC 和 HTTP 协议的自动追踪
- Spring Boot 3.x 自动配置
- 零侵入式集成

**核心组件：**
- `TraceInterceptor`: gRPC 拦截器，自动处理链路上下文传播
- `TraceFilter`: HTTP 过滤器，处理 Web 请求的链路追踪
- `TraceContext`: 链路上下文管理
- `ArgusAutoConfiguration`: Spring Boot 自动配置

**使用方式：**
```xml
<dependency>
    <groupId>com.ddm</groupId>
    <artifactId>argus-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### Hermes - AWS 服务发现

**功能特性：**
- 基于 AWS ECS 的服务发现
- 支持 AWS Service Discovery
- 轻量级 HTTP 客户端
- Spring Boot 3.x 自动配置
- 零侵入式集成

**核心组件：**
- `LaneBootstrap`: ECS 服务发现启动器
- `HermesProperties`: 配置属性类
- `HermesAutoConfiguration`: Spring Boot 自动配置

**使用方式：**
```xml
<dependency>
    <groupId>com.ddm</groupId>
    <artifactId>hermes-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

**配置示例：**
```yaml
hermes:
  enabled: true
  region: us-west-2
  service-discovery:
    timeout-seconds: 6
    max-retry-attempts: 5
    retry-delay-ms: 1000
  ecs:
    metadata-timeout-seconds: 2
    max-retry-attempts: 20
    retry-delay-ms: 800
    lane-tag-key: lane
```

**环境条件：**
- 仅在 `AWS_DEFAULT_REGION` 环境变量存在时自动启用
- 在非 AWS 环境下自动跳过，不影响应用启动

### Demo 应用

**服务架构：**
```
demo-web (HTTP) → demo-order-service (gRPC) → demo-user-service (gRPC)
```

**服务说明：**
- **demo-web**: Spring Boot Web 应用，提供 HTTP API 和前端界面
- **demo-order-service**: 订单服务，处理订单相关业务逻辑
- **demo-user-service**: 用户服务，管理用户信息和认证

## 🛠️ 技术栈

- **Java**: 21
- **Spring Boot**: 3.3.4
- **gRPC**: 1.64.0
- **Protocol Buffers**: 3.25.3
- **Maven**: 3.9.2
- **AWS SDK**: v2 (ECS, Service Discovery)

## 📋 环境要求

- Java 21+
- Maven 3.6+
- Docker (可选，用于容器化部署)

## 🚀 快速开始

### 1. 构建项目

```bash
# 克隆项目
git clone <repository-url>
cd infra-experimental

# 构建所有模块
mvn clean install -DskipTests
```

### 2. 运行演示应用

#### 启动用户服务
```bash
cd demo/demo-user-service
mvn spring-boot:run
# 服务将在端口 9091 启动
```

#### 启动订单服务
```bash
cd demo/demo-order-service
mvn spring-boot:run
# 服务将在端口 9092 启动
```

#### 启动 Web 应用
```bash
cd demo/demo-web
mvn spring-boot:run
# 应用将在端口 8080 启动
```

### 3. 访问应用

- **Web 界面**: http://localhost:8080
- **用户服务**: localhost:9091 (gRPC)
- **订单服务**: localhost:9092 (gRPC)

## 🔧 配置说明

### 链路追踪配置

Argus 支持以下配置选项：

```yaml
# application.yml
logging:
  level:
    com.ddm.argus: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:--}] %logger{36} - %msg%n"
```

### gRPC 服务配置

```yaml
# application.yml
grpc:
  server:
    port: 9091
  client:
    user-service:
      address: 'static://localhost:9091'
```

## 📊 链路追踪

### 追踪格式

项目使用 W3C Trace Context 标准：

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

### 日志格式

所有日志都包含链路追踪信息：

```
2025-10-05 04:39:04.870 [main] INFO [4bf92f3577b34da6a3ce929d0e0e4736] c.d.d.user.service.UserServiceImpl - User Service initialized
```

### 追踪流程

1. **HTTP 请求**: `TraceFilter` 解析 `traceparent` 头，创建或继承链路上下文
2. **gRPC 调用**: `TraceInterceptor` 自动传播链路上下文到下游服务
3. **日志记录**: 所有日志自动包含 `traceId` 和 `spanId`

## 🐳 Docker 部署

### 构建镜像

```bash
# 构建用户服务镜像
cd demo/demo-user-service
docker build -t demo-user-service:latest .

# 构建订单服务镜像
cd ../demo-order-service
docker build -t demo-order-service:latest .

# 构建 Web 应用镜像
cd ../demo-web
docker build -t demo-web:latest .
```

### 运行容器

```bash
# 启动用户服务
docker run -d -p 9091:9091 --name user-service demo-user-service:latest

# 启动订单服务
docker run -d -p 9092:9092 --name order-service demo-order-service:latest

# 启动 Web 应用
docker run -d -p 8080:8080 --name web-app demo-web:latest
```

## 🧪 测试

### 单元测试

```bash
# 运行所有测试
mvn test

# 运行特定模块测试
mvn test -pl argus
```

### 集成测试

```bash
# 启动所有服务进行集成测试
./scripts/build.sh
```

## 📈 监控和运维

### 健康检查

所有服务都提供健康检查端点：

- **gRPC 服务**: 使用 gRPC Health Checking Protocol
- **Web 应用**: Spring Boot Actuator 健康检查

### 日志监控

建议使用以下工具进行日志聚合和监控：

- **ELK Stack**: Elasticsearch + Logstash + Kibana
- **Prometheus + Grafana**: 指标监控
- **Jaeger**: 分布式追踪可视化

## 🤝 贡献指南

### 开发流程

1. Fork 项目
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范

- 使用 Java 21 特性
- 遵循 Spring Boot 最佳实践
- 添加适当的单元测试
- 更新相关文档

## 📝 更新日志

### v1.0.0 (2025-10-05)

- ✨ 初始版本发布
- 🚀 支持 Spring Boot 3.x
- 🔍 实现分布式链路追踪
- 🌐 支持 gRPC 和 HTTP 协议
- 📦 提供完整的演示应用

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 📞 联系方式

- 项目维护者: [nianien]
- 邮箱: [nianien@gmail.com]
- 项目地址: [https://github.com/nianien/infra-experimental]

## 🙏 致谢

感谢以下开源项目的支持：

- [Spring Boot](https://spring.io/projects/spring-boot)
- [gRPC](https://grpc.io/)
- [Protocol Buffers](https://developers.google.com/protocol-buffers)
- [AWS SDK](https://aws.amazon.com/sdk-for-java/)

---

**注意**: 这是一个实验性项目，用于学习和研究微服务架构和分布式系统。在生产环境中使用前，请进行充分的测试和评估。