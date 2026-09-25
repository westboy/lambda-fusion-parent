 # Lambda Fusion Config

Lambda Fusion Config 是 Lambda Fusion 框架中的动态配置管理模块，基于 Lambda Cloud 构建，提供企业级配置管理能力，支持数据库驱动的配置存储、热刷新机制和多租户配置隔离。

## 模块概述

本项目是 Lambda Fusion 框架的核心配置管理模块，专为企业级微服务应用设计，提供统一的配置管理、动态刷新、加密存储等功能。作为 Lambda Cloud 生态的一部分，与其他模块无缝集成，支持应用在不重启的情况下动态更新配置。

### 核心特性

- **数据库驱动配置**：基于数据库的配置存储和检索
- **实时热刷新**：每30秒自动检测配置变更并刷新（可配置）
- **配置加密支持**：支持 AES/RSA 加密存储敏感配置
- **多租户隔离**：基于应用名称的配置隔离机制
- **Nacos 集成**：支持分布式配置管理
- **Spring Cloud Config 集成**：无缝集成 Spring 配置体系
- **批量操作**：支持配置的批量查询和更新
- **权限控制**：基于 Sa-Token 的角色权限管理
- **操作审计**：完整的配置变更日志记录

## 技术栈

### 核心技术
- **Java 21+**
- **Spring Boot 3.x**
- **Spring Cloud** - 分布式配置
- **MyBatis Plus** - ORM 框架
- **Lambda Cloud** - 基础框架依赖

### 数据存储
- **MySQL/PostgreSQL** - 关系型数据库
- **HikariCP** - 数据库连接池
- **Liquibase** - 数据库版本管理

### Lambda Cloud Starter 集成
- **lambda-fusion-core** - 框架核心库
- **lambda-cloud-starter-datasource** - 数据源属性支持
- **lambda-cloud-starter-logger** - 操作日志记录

### 外部集成
- **Nacos** - 配置中心（可选）
- **Hutool** - 加密工具库
- **Caffeine** - 本地缓存

## 项目结构

```
lambda-fusion-config/
├── src/main/java/com/lambda/fusion/
│   ├── autoconfig/
│   │   ├── ConfigAutoConfiguration.java      # Spring 自动配置入口
│   │   └── ConfigProperties.java             # 配置属性绑定
│   └── config/
│       ├── controller/                       # REST API 控制器
│       │   ├── ConfigController.java         # 主配置管理 API
│       │   ├── ConfigOptionController.java   # 配置选项 API
│       │   └── ServerConfigController.java   # 服务器配置端点
│       ├── datasource/                       # 数据库属性源实现
│       │   ├── DatabaseBasedProperties.java
│       │   ├── DataBaseBasedPropertySource.java
│       │   └── DatabaseBasedPropertySourceLocator.java
│       ├── environment/
│       │   └── DatabaseBasedEnvironment.java
│       ├── exception/
│       │   └── ConfigLoadException.java      # 配置加载异常
│       ├── mapper/                           # MyBatis 数据访问层
│       │   ├── ConfigMapper.java
│       │   └── ConfigOptionMapper.java
│       ├── model/                            # 数据模型和 DTO
│       │   ├── ConfigEntity.java             # 配置实体
│       │   ├── ConfigOptionEntity.java       # 配置选项实体
│       │   ├── SaveConfig.java               # 保存配置 DTO
│       │   ├── UpdateConfig.java             # 更新配置 DTO
│       │   ├── BatchUpdateConfig.java        # 批量更新 DTO
│       │   ├── QueryConfig.java              # 查询配置 DTO
│       │   ├── QueryConfigList.java          # 列表查询 DTO
│       │   └── QueryConfigPage.java          # 分页查询 DTO
│       ├── refresh/
│       │   └── DatabaseContextRefresher.java # 自动刷新调度器
│       ├── service/                          # 业务逻辑层
│       │   ├── ConfigService.java            # 配置服务接口
│       │   ├── ConfigChangedService.java     # 配置变更通知接口
│       │   └── impl/
│       │       ├── ConfigServiceImpl.java    # 配置服务实现
│       │       ├── ConfigOptionServiceImpl.java
│       │       └── NacosConfigService.java   # Nacos 配置服务
│       ├── utils/
│       │   ├── EncryptUtils.java             # AES/RSA 加密工具
│       │   └── DataSourcePropertyUtils.java  # 数据源属性解析
│       ├── ConfigConfigure.java              # 组件扫描配置
│       └── ConfigConstants.java              # 常量定义
└── src/main/resources/
    ├── mapper/
    │   └── ConfigsMapper.xml                 # MyBatis SQL 映射
    └── META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## 核心功能详解

### 1. 配置管理 (Configuration Management)

**主要功能：**
- 配置的 CRUD 操作（创建、读取、更新、删除）
- 配置选项管理（枚举类型配置的选项值）
- 批量配置操作
- 动态配置刷新
- 配置加密存储

**核心类：**
- `ConfigService` - 配置业务逻辑服务
- `ConfigController` - 配置 API 控制器
- `ConfigEntity` - 配置数据实体
- `ConfigServiceImpl` - 配置服务实现

### 2. 数据库属性源 (Database Property Source)

**主要功能：**
- 实现 Spring 的 `PropertySourceLocator` 接口
- 从数据库加载配置到 Spring 环境
- 支持独立的数据源配置
- 处理配置值的加密/解密
- 创建 HikariCP 连接池进行高效数据库访问

**核心类：**
- `DatabaseBasedPropertySourceLocator` - 数据库属性源定位器
- `DataBaseBasedPropertySource` - 数据库属性源实现
- `DatabaseBasedProperties` - 数据库属性配置

### 3. 动态配置刷新 (Dynamic Configuration Refresh)

**主要功能：**
- 定时检测配置变更（默认30秒间隔）
- 自动刷新 Spring 上下文
- 支持手动触发刷新
- 配置变更事件通知
- 并发控制防止重复刷新

**核心类：**
- `DatabaseContextRefresher` - 数据库上下文刷新器
- `ConfigChangedService` - 配置变更服务接口

### 4. 配置加密 (Configuration Encryption)

**主要功能：**
- AES 加密配置值（ECB 模式，PKCS7 填充）
- RSA 加密会话密钥
- 加密值标记（"ENC(...)" 前缀）
- 属性加载时自动解密

**核心类：**
- `EncryptUtils` - 加密解密工具类

## API 接口

### 配置管理 API (`/config`)

| 方法 | 路径 | 描述 | 权限 |
|------|------|------|------|
| GET | `/page` | 分页查询配置列表 | 公开 |
| GET | `/` | 条件查询配置列表 | 公开 |
| GET | `/systems` | 获取当前应用配置 | 公开 |
| POST | `/manager` | 创建新配置 | ROLE_DEV |
| GET | `/manager/{id}` | 获取配置详情 | ROLE_DEV |
| PUT | `/manager/{id}` | 更新配置 | ROLE_DEV |
| DELETE | `/manager/{id}` | 删除配置 | ROLE_DEV |
| GET | `/manager/{id}/options` | 获取配置选项 | ROLE_DEV |
| PATCH | `/manager/apply` | 触发配置刷新 | ROLE_DEV |
| POST | `/batch` | 批量查询配置 | 公开 |
| PUT | `/batch` | 批量更新配置 | 公开 |

### 服务器配置 API (`/public/config`)

| 方法 | 路径 | 描述 | 权限 |
|------|------|------|------|
| GET | `/server` | 获取服务器配置（加密） | 公开 |

## 配置说明

### 基础配置 (`lambda.fusion.config`)

```yaml
lambda:
  fusion:
    config:
      # 系统信息配置
      title: "快速开发平台"
      copyright: "版权 © Lambda Fusion"
      version: "2026.1.1-SNAPSHOT"
      
      # 数据库查询 SQL 配置
      database:
        selectConfigsSql: "SELECT property_key, property_value, application FROM la_configs WHERE application = ? OR application = 'public'"
        checkConfigsChangedSql: "SELECT MAX(update_time) FROM la_configs WHERE application = ? OR application = 'public'"
      
      # 安全配置
      security:
        configEncryptEnabled: false    # 是否启用配置加密
        formVerifyEnabled: false       # 是否启用表单验证
        privateKey: ""                 # RSA 私钥
        publicKey: ""                  # RSA 公钥
      
      # 自定义配置
      customize:
        tenantEnabled: false           # 是否启用租户
        roleEnabled: true              # 是否启用角色
        dataRoleEnabled: false         # 是否启用数据角色
      
      # 自动刷新配置
      auto-refresh:
        enabled: true                  # 是否启用自动刷新
```

### 数据源配置 (`lambda.fusion.config.datasource`)

```yaml
lambda:
  fusion:
    config:
      datasource:
        url: jdbc:mysql://localhost:3306/lambda_config
        username: root
        password: password
        driver-class-name: com.mysql.cj.jdbc.Driver
        # HikariCP 连接池配置
        maximum-pool-size: 1
        minimum-idle: 1
        connection-timeout: 30000
        idle-timeout: 600000
        max-lifetime: 1800000
```

## 数据库表结构

### LA_CONFIGS 表（配置主表）

```sql
CREATE TABLE LA_CONFIGS (
  PROPERTY_ID VARCHAR(64) PRIMARY KEY,      -- 配置ID
  PROPERTY_KEY VARCHAR(255) NOT NULL,      -- 配置键
  PROPERTY_VALUE LONGTEXT,                 -- 配置值
  PROPERTY_NAME VARCHAR(255),              -- 配置名称
  PROPERTY_TYPE INT,                       -- 配置类型（1:布尔 2:枚举 3:字符串 4:数字）
  PROPERTY_DESC VARCHAR(500),              -- 配置描述
  APPLICATION VARCHAR(64),                 -- 应用名称
  UPDATE_TIME TIMESTAMP,                   -- 更新时间
  UNIQUE KEY uk_key_app (PROPERTY_KEY, APPLICATION)
);
```

### LA_CONFIG_OPTIONS 表（配置选项表）

```sql
CREATE TABLE LA_CONFIG_OPTIONS (
  OPTION_ID VARCHAR(64) PRIMARY KEY,       -- 选项ID
  PROPERTY_ID VARCHAR(64) NOT NULL,        -- 配置ID（外键）
  OPTION_VALUE VARCHAR(255),               -- 选项值
  OPTION_NAME VARCHAR(255),                -- 选项名称
  OPTION_DESC VARCHAR(500),                -- 选项描述
  APPLICATION VARCHAR(64),                 -- 应用名称
  FOREIGN KEY (PROPERTY_ID) REFERENCES LA_CONFIGS(PROPERTY_ID)
);
```

## 使用说明

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-config</artifactId>
    <version>2026.1.1-SNAPSHOT</version>
</dependency>
```

### 2. 启用框架

Lambda Fusion 模块使用 Spring Boot 自动配置机制，只需添加依赖即可自动启用：

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

模块会通过 `org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件自动加载配置。

### 3. 配置数据库

确保数据库连接配置正确，Liquibase 会自动创建所需的表结构。

### 4. 使用配置

在应用中直接使用 `@Value` 注解或 `Environment` 获取配置：

```java
@Component
public class MyService {
    
    @Value("${app.feature.enabled:false}")
    private boolean featureEnabled;
    
    @Autowired
    private Environment environment;
    
    public void doSomething() {
        String configValue = environment.getProperty("app.config.key");
        // 使用配置值
    }
}
```

## 开发指南

### 创建配置

通过 REST API 创建配置：

```java
// 创建布尔类型配置
POST /config/manager
{
  "key": "app.feature.enabled",
  "value": "true",
  "name": "功能开关",
  "description": "启用/禁用功能",
  "type": 1,  // 布尔类型
  "options": [
    {"value": "true", "name": "启用", "description": "启用功能"},
    {"value": "false", "name": "禁用", "description": "禁用功能"}
  ]
}

// 创建枚举类型配置
POST /config/manager
{
  "key": "app.log.level",
  "value": "INFO",
  "name": "日志级别",
  "description": "应用日志级别配置",
  "type": 2,  // 枚举类型
  "options": [
    {"value": "DEBUG", "name": "调试", "description": "调试级别"},
    {"value": "INFO", "name": "信息", "description": "信息级别"},
    {"value": "WARN", "name": "警告", "description": "警告级别"},
    {"value": "ERROR", "name": "错误", "description": "错误级别"}
  ]
}
```

### 更新配置

```java
// 更新配置值
PUT /config/manager/{id}
{
  "value": "false",
  "description": "更新后的描述"
}

// 批量更新配置
PUT /config/batch
{
  "application": "my-app",
  "configs": [
    {"id": "config-id-1", "value": "new-value-1"},
    {"id": "config-id-2", "value": "new-value-2"}
  ]
}
```

### 触发配置刷新

```java
// 手动触发配置刷新
PATCH /config/manager/apply
```

### 监听配置变更

实现 `ConfigChangedService` 接口监听配置变更：

```java
@Component
public class MyConfigChangeListener implements ConfigChangedService {
    
    @Override
    public void execute() {
        // 处理配置变更逻辑
        log.info("配置已更新，执行相关业务逻辑");
    }
}
```

### 使用 @RefreshScope

对于需要动态刷新的 Bean，使用 `@RefreshScope` 注解：

```java
@Component
@RefreshScope
public class DynamicConfigBean {
    
    @Value("${app.dynamic.config:default}")
    private String dynamicConfig;
    
    public String getDynamicConfig() {
        return dynamicConfig;
    }
}
```

### 配置加密

启用配置加密功能：

```yaml
lambda:
  fusion:
    config:
      security:
        configEncryptEnabled: true
        privateKey: "your-rsa-private-key"
        publicKey: "your-rsa-public-key"
```

加密的配置值会以 `ENC(...)` 格式存储，系统会自动解密。

### 多租户配置

通过应用名称实现配置隔离：

```java
// 应用特定配置
INSERT INTO LA_CONFIGS (PROPERTY_ID, PROPERTY_KEY, PROPERTY_VALUE, APPLICATION) 
VALUES ('id1', 'app.config', 'app-specific-value', 'my-app');

// 公共配置（所有应用共享）
INSERT INTO LA_CONFIGS (PROPERTY_ID, PROPERTY_KEY, PROPERTY_VALUE, APPLICATION) 
VALUES ('id2', 'common.config', 'shared-value', 'public');
```

## 工作原理

### 配置加载流程

```
应用启动
    ↓
ConfigAutoConfiguration（自动配置）
    ↓
DatabaseBasedPropertySourceLocator.locate()
    ↓
从 LA_CONFIGS 表加载配置
    ↓
解密配置值（如果启用加密）
    ↓
注册为 Spring PropertySource
    ↓
DatabaseContextRefresher 启动（10秒延迟，30秒间隔）
    ↓
每30秒检查：
  - 检查配置校验和
  - 如有变更 → ContextRefresher.refresh()
  - 更新 @RefreshScope Bean
  - 通知 ConfigChangedService 监听器
```

### 配置刷新机制

1. **启动阶段**：
   - `DatabaseBasedPropertySourceLocator` 从数据库加载配置
   - 创建 HikariCP 连接池（最大1个连接，最小1个空闲连接）
   - 注册为 Spring 属性源
   - `DatabaseContextRefresher` 启动定时任务

2. **运行阶段**：
   - 每30秒检查配置变更
   - 比较配置校验和与之前的值
   - 如有变更，调用 `ContextRefresher.refresh()`
   - Spring 更新 `@RefreshScope` Bean 的新值

3. **手动刷新**：
   - 调用 `PATCH /config/manager/apply` 端点
   - 立即触发刷新，无需等待定时任务
