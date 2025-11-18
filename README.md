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

本项目采用 **集中式版本管理** 策略，所有模块版本在根 POM 中统一管理，实现一处修改、全局生效。

### 版本管理架构

```
根 POM (infra-lab:0)
  ├── 定义版本属性 (chaos.version, argus.version, ...)
  │
  ├── 一级模块 (chaos, argus, atlas, demo)
  │   ├── version: ${revision}
  │   └── revision: ${xxx.version}  ← 引用根 POM 的属性
  │       │
  │       └── 子模块 (chaos-core, argus-core, ...)
  │           └── parent.version: ${revision}  ← 引用一级模块的 revision
```

### 核心规则

1. **根 POM** (`pom.xml`)
   - 版本固定为 `0`，仅作为聚合器（aggregator），不对外发布
   - 在 `<properties>` 中定义所有一级模块的版本属性：
     ```xml
     <properties>
         <chaos.version>1.0-SNAPSHOT</chaos.version>
         <argus.version>1.0-SNAPSHOT</argus.version>
         <atlas.version>1.0-SNAPSHOT</atlas.version>
         <demo.version>1.0-SNAPSHOT</demo.version>
     </properties>
     ```

2. **一级模块** (`chaos/pom.xml`, `argus/pom.xml`, 等)
   - `<version>${revision}</version>`：使用 revision 占位符
   - `<revision>${xxx.version}</revision>`：引用根 POM 中定义的版本属性
   - 发布时通过 `flatten-maven-plugin` 的 `oss` 模式裁掉 `infra-lab:0` 这个 parent

3. **子模块** (`chaos/chaos-core/pom.xml`, 等)
   - `<parent><version>${revision}</version></parent>`：引用一级模块的 revision
   - 发布时只解析 `${revision}` 为具体版本号，保留 parent 结构

### 版本升级流程

**升级某个模块的版本（例如将 Chaos 从 1.0-SNAPSHOT 升级到 2.0-SNAPSHOT）：**

1. **修改根 POM** (`pom.xml`)：
   ```xml
   <properties>
       <chaos.version>2.0-SNAPSHOT</chaos.version>  <!-- 只改这里 -->
       <argus.version>1.0-SNAPSHOT</argus.version>
       <atlas.version>1.0-SNAPSHOT</atlas.version>
       <demo.version>1.0-SNAPSHOT</demo.version>
   </properties>
   ```

2. **自动生效**：
   - 一级模块 `chaos/pom.xml` 中的 `${chaos.version}` 自动更新
   - 所有 Chaos 子模块的版本自动跟随更新
   - 跨模块依赖的版本在根 POM 的 `dependencyManagement` 中自动更新

**✅ 优势：**
- ✅ **一处修改，全局生效**：只需修改根 POM 的版本属性
- ✅ **版本一致性**：同一模块的所有子模块版本自动保持一致
- ✅ **维护简单**：无需手动修改多个 POM 文件
- ✅ **发布友好**：通过 flatten-maven-plugin 自动处理版本占位符

### 配置示例

**根 POM** (`pom.xml`)：
```xml
<project>
    <groupId>com.ddm</groupId>
    <artifactId>infra-lab</artifactId>
    <version>0</version>
    <packaging>pom</packaging>
    
    <properties>
        <chaos.version>1.0-SNAPSHOT</chaos.version>
        <argus.version>1.0-SNAPSHOT</argus.version>
        <atlas.version>1.0-SNAPSHOT</atlas.version>
        <demo.version>1.0-SNAPSHOT</demo.version>
    </properties>
    
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.ddm</groupId>
                <artifactId>chaos-core</artifactId>
                <version>${chaos.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

**一级模块** (`chaos/pom.xml`)：
```xml
<project>
    <parent>
        <groupId>com.ddm</groupId>
        <artifactId>infra-lab</artifactId>
        <version>0</version>
    </parent>
    <artifactId>chaos</artifactId>
    <version>${revision}</version>
    
    <properties>
        <revision>${chaos.version}</revision>  <!-- 引用根 POM 的属性 -->
        <flatten.mode>oss</flatten.mode>
    </properties>
</project>
```

**子模块** (`chaos/chaos-core/pom.xml`)：
```xml
<project>
    <parent>
        <groupId>com.ddm</groupId>
        <artifactId>chaos</artifactId>
        <version>${revision}</version>  <!-- 引用一级模块的 revision -->
    </parent>
    <artifactId>chaos-core</artifactId>
</project>
```

### 依赖管理

- **同模块内依赖**：版本由一级模块的 `<revision>` 自动管理，子模块无需指定版本
- **跨模块依赖**：版本由根 POM 的 `dependencyManagement` 统一管理，使用 `${xxx.version}` 属性

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
