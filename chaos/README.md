# Chaos 配置中心

Chaos 是一个轻量级的分布式配置中心，支持多环境配置管理、动态配置更新和类型安全的配置注入。

## 📦 模块说明

### chaos-core
核心功能模块，提供配置中心的基础能力：
- **配置定义**：`ConfRef`（配置引用）、`ConfItem`（配置项）、`ConfDesc`（配置描述符）、`ConfKey`（缓存键）
- **数据提供者接口**：`DataProvider` 接口，定义了配置数据的获取方式
- **数据提供者实现**：
  - `JdbcDataProvider`：基于 JDBC 的数据库配置提供者
  - `GrpcDataProvider`：基于 gRPC 的远程配置提供者
- **工具类**：`Converters`（类型转换）、配置解析等
- **注解**：`@Conf` 注解，用于声明式配置注入

### chaos-client
客户端模块，提供 Spring Boot Starter，支持自动配置：
- **自动配置**：`ChaosAutoConfiguration` 自动配置 `ConfigFactory` 和 `ConfigResolver`
- **配置工厂**：`DefaultConfigFactory` 提供配置缓存和刷新机制
- **配置解析器**：`ConfigResolver` 支持通过 `@Conf` 注解自动注入配置值
- **支持两种数据提供方式**：
  - **JDBC 模式**：直接连接数据库读取配置
  - **gRPC 模式**：通过 gRPC 调用远程配置服务

### chaos-server
服务端模块，提供基于 gRPC 的配置服务：
- **gRPC 服务**：`ConfigServiceImpl` 实现配置查询接口
- **数据源**：使用 `JdbcDataProvider` 从数据库读取配置数据
- **独立部署**：可作为独立的配置中心服务运行

### atlas (原 chaos-web)
Web 管理界面模块，提供配置的 Web 管理功能：
- **RESTful API**：`ConfigController` 提供配置的 CRUD 接口
- **Web 界面**：`config.html` 提供可视化的配置管理界面
- **功能特性**：
  - 命名空间管理
  - 配置分组管理
  - 配置项管理（支持多环境配置）
  - 配置项编辑和查看

## 🚀 快速开始

### 1. 数据库初始化

使用 `chaos-core/src/main/resources/schema-mysql.sql` 初始化数据库表结构：

```sql
-- 创建命名空间表
CREATE TABLE config_namespace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    owner VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 创建配置分组表
CREATE TABLE config_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    namespace VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group_name (namespace, name)
);

-- 创建配置项表
CREATE TABLE config_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    namespace VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    `key` VARCHAR(255) NOT NULL,
    value TEXT,
    variant JSON,
    type VARCHAR(50) DEFAULT 'string',
    enabled BOOLEAN DEFAULT TRUE,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ns_gp_key (namespace, group_name, `key`)
);
```

### 2. 客户端配置

在 Spring Boot 应用的 `application.yml` 中配置 Chaos 客户端。

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
    profiles: [ "gray", "hotfix" ]  # 环境标签
    ttl: 30S                     # 缓存刷新时间
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
    profiles: [ "gray", "hotfix" ]
    ttl: 30S
```

> **注意**：具体配置示例请参考 `demo/demo-web-api/src/main/resources/application.yml`

### 3. 添加依赖

在 `pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>com.ddm</groupId>
    <artifactId>chaos-client</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 4. 使用配置

#### 方式一：使用 `@Conf` 注解

```java
@Component
public class MyService {
    
    @Conf(namespace = "chaos", group = "cfd", key = "ttl")
    private Duration ttl;
    
    @Conf(namespace = "chaos", group = "cfd", key = "maxRetries", defaultValue = "3")
    private Integer maxRetries;
    
    @Conf(namespace = "chaos", group = "cfd", key = "whitelist", defaultValue = "[]")
    private List<String> whitelist;
}
```

#### 方式二：使用 `ConfigFactory`

```java
@Service
public class MyService {
    
    @Autowired
    private ConfigFactory configFactory;
    
    public void doSomething() {
        Duration ttl = configFactory.get(
            ConfRef.of("chaos", "cfd", "ttl"),
            Duration.class,
            Duration.ofSeconds(30)
        );
    }
}
```

## 🔧 配置说明

### chaos.config-center.profiles
环境标签列表，用于多环境配置覆盖。例如：
- `["gray"]`：灰度环境
- `["gray", "hotfix"]`：灰度 + 热修复环境

配置项可以通过 `variant` 字段存储不同环境的配置值，标签匹配时会优先使用环境配置。

### chaos.config-center.ttl
配置缓存刷新时间，格式支持：
- `30S`：30 秒
- `1M`：1 分钟
- `1H`：1 小时


## 🌐 Web 管理界面

启动 `atlas` 模块后，访问 `http://localhost:8080/` 即可打开配置管理界面。

功能包括：
- 命名空间管理
- 配置分组管理
- 配置项管理
- 多环境配置编辑

## 📚 架构说明

```
┌─────────────┐
│  atlas  │  Web 管理界面（原 chaos-web）
└──────┬──────┘
       │
       ▼
┌─────────────┐
│chaos-server │  gRPC 配置服务
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Database   │  配置存储
└─────────────┘

┌─────────────┐
│chaos-client │  Spring Boot Starter
└──────┬──────┘
       │
       ├─────────────┐
       │             │
       ▼             ▼
┌─────────────┐ ┌─────────────┐
│    JDBC     │ │    gRPC     │
│   Provider  │ │   Provider  │
└─────────────┘ └─────────────┘
       │             │
       └──────┬──────┘
              ▼
       ┌─────────────┐
       │  Database   │
       └─────────────┘
```

## 🔍 数据提供者选择

### JDBC 模式
- **优点**：简单直接，无需额外服务
- **适用场景**：单应用或小规模部署
- **配置**：直接配置数据源

### gRPC 模式
- **优点**：集中管理，支持多客户端
- **适用场景**：多应用共享配置，需要集中管理
- **配置**：配置 gRPC 客户端连接到 `chaos-server`

## 📄 License

Internal use only.

