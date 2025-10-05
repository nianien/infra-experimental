# Hermes Spring Boot Starter

AWS ECS 服务发现的 Spring Boot Starter，提供零侵入式的服务注册功能。

## 🚀 功能特性

- **自动服务注册**: 在 ECS 环境下自动将服务注册到 AWS Service Discovery (Cloud Map)
- **零侵入式集成**: 通过 Spring Boot 自动配置，无需手动配置
- **灵活配置**: 支持丰富的配置选项，适应不同环境需求
- **智能检测**: 自动检测运行环境，仅在 ECS 环境下执行注册
- **重试机制**: 内置重试逻辑，提高注册成功率

## 📦 依赖引入

```xml
<dependency>
    <groupId>com.ddm</groupId>
    <artifactId>hermes-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

## ⚙️ 配置选项

### 环境条件

Hermes 仅在 `AWS_DEFAULT_REGION` 环境变量存在时自动启用：
- ✅ **有 AWS_DEFAULT_REGION**：自动加载 Hermes 服务发现功能
- ❌ **无 AWS_DEFAULT_REGION**：自动跳过，不影响应用启动

### 基本配置

```yaml
hermes:
  enabled: true                    # 启用 Hermes 服务发现
  region: us-west-2               # AWS 区域（可选，默认从 ECS 元数据获取）
```

### 服务发现配置

```yaml
hermes:
  service-discovery:
    timeout-seconds: 6            # 服务发现操作超时时间（秒）
    max-retry-attempts: 5         # 最大重试次数
    retry-delay-ms: 1000          # 重试延迟（毫秒）
```

### ECS 配置

```yaml
hermes:
  ecs:
    metadata-timeout-seconds: 2   # ECS 元数据操作超时时间（秒）
    max-retry-attempts: 20        # 获取私有 IP 的最大重试次数
    retry-delay-ms: 800           # IP 轮询重试延迟（毫秒）
    lane-tag-key: lane            # Lane 标签键名
```

## 🔧 工作原理

### 自动注册流程

1. **环境检测**: 检查 `ECS_CONTAINER_METADATA_URI_V4` 环境变量
2. **元数据获取**: 从 ECS 元数据服务获取集群和任务信息
3. **服务发现**: 查询 ECS 服务配置，获取 Cloud Map 服务 ID
4. **IP 获取**: 轮询获取容器的私有 IP 地址
5. **Lane 识别**: 从服务标签或名称后缀获取 Lane 信息
6. **实例注册**: 将服务实例注册到 AWS Service Discovery

### 注册属性

服务实例将注册以下属性：

- `AWS_INSTANCE_IPV4`: 容器私有 IP 地址
- `AWS_INSTANCE_PORT`: 容器端口
- `lane`: Lane 标识（从标签或服务名后缀获取）

## 🏗️ 架构组件

### 核心类

- **`HermesAutoConfiguration`**: Spring Boot 自动配置类
- **`HermesProperties`**: 配置属性类
- **`LaneBootstrap`**: 服务注册启动器

### 自动配置条件

- `@ConditionalOnClass(LaneBootstrap.class)`: 确保 LaneBootstrap 类存在
- `@ConditionalOnProperty(prefix = "hermes", name = "enabled", havingValue = "true", matchIfMissing = true)`: 支持通过配置启用/禁用
- `@ConditionalOnAwsRegion`: 仅在 `AWS_DEFAULT_REGION` 环境变量存在时启用

## 📋 使用示例

### 1. 基本使用

只需添加依赖，无需额外配置：

```java
@SpringBootApplication
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

### 2. 自定义配置

```yaml
hermes:
  enabled: true
  region: us-west-2
  service-discovery:
    timeout-seconds: 10
    max-retry-attempts: 3
  ecs:
    lane-tag-key: environment
```

### 3. 禁用 Hermes

```yaml
hermes:
  enabled: false
```

## 🔍 日志监控

### 关键日志

- `Hermes: HermesAutoConfiguration constructor called` - 自动配置加载
- `Hermes: Auto-configuring LaneBootstrap for AWS ECS service discovery` - 组件配置
- `LaneRegistrar: not in ECS (no metadata). Skip.` - 非 ECS 环境跳过
- `LaneRegistrar OK. serviceId=xxx, instanceId=xxx, ip=xxx, port=xxx, lane=xxx, region=xxx` - 注册成功

### 日志级别

建议在生产环境中将 Hermes 日志级别设置为 INFO：

```yaml
logging:
  level:
    com.ddm.hermes: INFO
```

## 🚨 注意事项

### 环境要求

- 必须在 AWS ECS 环境中运行
- 需要适当的 IAM 权限访问 ECS 和 Service Discovery
- 服务必须配置 Cloud Map 注册

### IAM 权限

服务需要以下 IAM 权限：

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "ecs:DescribeTasks",
                "ecs:DescribeServices",
                "ecs:DescribeTaskDefinition",
                "ecs:ListTagsForResource"
            ],
            "Resource": "*"
        },
        {
            "Effect": "Allow",
            "Action": [
                "servicediscovery:RegisterInstance"
            ],
            "Resource": "arn:aws:servicediscovery:*:*:service/*"
        }
    ]
}
```

### 故障排除

1. **注册失败**: 检查 IAM 权限和网络连接
2. **IP 获取超时**: 调整 `ecs.max-retry-attempts` 和 `ecs.retry-delay-ms`
3. **服务发现超时**: 调整 `service-discovery.timeout-seconds`

## 🔄 版本历史

### v1.0.0 (2025-10-05)

- ✨ 初始版本发布
- 🚀 支持 Spring Boot 3.x
- 🔧 完整的自动配置支持
- ⚙️ 丰富的配置选项
- 🔄 智能重试机制
- 📝 完整的文档和示例

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](../../LICENSE) 文件了解详情。
