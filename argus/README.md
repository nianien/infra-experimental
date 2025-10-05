# Argus Spring Boot Starter

Argus Spring Boot Starter 提供分布式链路追踪的自动配置功能。

## 功能特性

- **自动配置**: 自动加载 `TraceFilter` 和 `TraceInterceptor`
- **条件化加载**: `TraceFilter` 仅在 Web 环境下加载
- **零配置**: 引入依赖即可使用，无需额外配置

## 使用方法

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.ddm</groupId>
    <artifactId>argus-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. 自动配置

引入依赖后，Spring Boot 会自动配置：

- **TraceInterceptor**: gRPC 拦截器，用于 gRPC 调用的链路追踪
- **TraceFilter**: HTTP 过滤器，用于 HTTP 请求的链路追踪（仅在 Web 环境下）

### 3. 配置说明

#### Web 环境
- 自动加载 `TraceFilter`，处理 HTTP 请求的链路追踪
- 自动加载 `TraceInterceptor`，处理 gRPC 调用的链路追踪

#### 非 Web 环境（如 gRPC 服务）
- 仅加载 `TraceInterceptor`，处理 gRPC 调用的链路追踪

## 自动配置类

- `ArgusAutoConfiguration`: 主配置类
- `WebConfiguration`: Web 环境专用配置

## 条件化配置

- `@ConditionalOnWebApplication`: 仅在 Web 环境下生效
- `@ConditionalOnMissingBean`: 避免重复配置
- `@ConditionalOnClass`: 确保相关类存在

## 日志输出

启动时会输出配置日志：

```
Argus: Auto-configuring TraceFilter for web environment
Argus: Auto-configuring TraceInterceptor
```

## 依赖要求

- Spring Boot 3.3.4+
- gRPC Spring Boot Starter 2.15.0+
- Java 21+
