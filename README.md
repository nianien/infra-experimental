# Infra Lab

基础设施实验性项目，包含多个独立的基础设施组件和工具。

## 📦 项目结构

```
infra-lab/                    # 根聚合工程（version=0，仅作为 aggregator）
├── chaos/                    # Chaos 配置中心模块
│   ├── chaos-core/          # 核心库
│   ├── chaos-client/        # 客户端（Spring Boot Starter）
│   └── chaos-server/        # 服务端（gRPC Server）
├── argus/                    # Argus 分布式追踪模块
├── atlas/                    # Atlas 基础架构平台（原 chaos-web）
└── demo/                     # 演示和示例模块
    ├── demo-proto/          # Protocol Buffers 定义
    ├── demo-user-rpc/       # 用户服务示例
    ├── demo-order-rpc/      # 订单服务示例
    └── demo-web-api/        # Web API 示例
```

## 🚀 快速开始

### 构建项目

```bash
# 构建整个项目
mvn clean install

# 构建特定模块
mvn -pl chaos -am clean install
```

### 运行示例

```bash
# 运行 demo-web-api
cd demo/demo-web-api
mvn spring-boot:run
```

## 📚 模块说明

### Chaos 配置中心

动态配置管理框架，支持 JDBC 和 gRPC 两种数据源。

- **chaos-core**：核心库，提供配置解析、类型转换等功能
- **chaos-client**：Spring Boot Starter，支持 `@Conf` 注解注入配置
- **chaos-server**：gRPC 服务端，提供配置查询接口

详细文档： [chaos/README.md](chaos/README.md)

### Argus 分布式追踪

基于 gRPC 的分布式追踪框架，支持 ECS 服务发现。

详细文档： [argus/README.md](argus/README.md)

### Atlas 基础架构平台

统一的基础架构管理平台，包含配置中心、服务发现、链路追踪等功能。

## 🎯 版本管理

本项目采用 **多模块独立版本管理** 策略，每个「次顶级 parent」模块拥有自己独立的版本号。

### 核心规则

1. **根 POM**：版本固定为 `0`，只作为 aggregator，不对外发布
2. **次顶级模块**（如 `chaos`、`argus`、`atlas`、`demo`）：
   - 使用 `${revision}` 作为版本号
   - 发布时通过 `flatten-maven-plugin` 裁掉 `infra-lab:0` 这个 parent
3. **子模块**：
   - `parent = com.ddm:chaos:${revision}`
   - 发布时只解析 `${revision}` 为具体版本号，保留 parent 结构

### 版本升级

**升级某个模块版本（例如 Chaos 模块）：**

1. 编辑 `chaos/pom.xml`，修改 `<revision>` 属性：
   ```xml
   <properties>
     <revision>2.0-SNAPSHOT</revision>
   </properties>
   ```

2. 同步更新根 POM 的跨模块依赖版本（如果其他模块依赖 Chaos）：
   ```xml
   <!-- pom.xml -->
   <properties>
     <chaos.version>2.0-SNAPSHOT</chaos.version>
   </properties>
   ```

**✅ 优点：**
- 只需修改一处（次顶级 parent 的 `<revision>`），所有子模块自动跟随
- 子模块的 `<parent><version>` 永远写成 `${revision}`，不需要手动修改

### 示例

**次顶级 Parent POM：**

```xml
<!-- chaos/pom.xml -->
<project>
  <parent>
    <groupId>com.ddm</groupId>
    <artifactId>infra-lab</artifactId>
    <version>0</version>
  </parent>
  <artifactId>chaos</artifactId>
  <version>${revision}</version>
  <properties>
    <revision>1.0-SNAPSHOT</revision>  <!-- 只改这里就能全树升级版本 -->
  </properties>
</project>
```

**子模块 POM：**

```xml
<!-- chaos/chaos-core/pom.xml -->
<project>
  <parent>
    <groupId>com.ddm</groupId>
    <artifactId>chaos</artifactId>
    <version>${revision}</version>  <!-- 永远不用改 -->
  </parent>
  <artifactId>chaos-core</artifactId>
</project>
```

### 跨模块依赖

- **同模块内依赖**：版本由父 POM 的 `<revision>` 自动管理，无需指定版本
- **跨模块依赖**：版本由根 POM 的 `dependencyManagement` 管理

## 🛠️ 技术栈

- **Java 21**
- **Spring Boot 3.3.4**
- **gRPC 1.67.1**
- **Protocol Buffers 4.28.3**
- **Maven 3.x**

## 📝 开发规范

1. **版本管理**：遵循本文档中的版本管理规范
2. **代码风格**：遵循 Java 标准代码风格
3. **提交信息**：使用清晰的提交信息，说明修改内容

## 📄 许可证

[待定]

## 🤝 贡献

欢迎提交 Issue 和 Pull Request。
