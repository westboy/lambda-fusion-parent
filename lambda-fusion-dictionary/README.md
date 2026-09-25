# Lambda Fusion Dict

Lambda Fusion Dict 是 Lambda Fusion 框架中的数据字典管理模块，基于 Lambda Cloud 构建，提供统一的数据字典管理系统，支持静态和动态字典、多数据源（SQL、HTTP/URL、枚举）、层级组织、多租户和缓存机制。

## 模块概述

本项目是 Lambda Fusion 框架的核心数据字典管理模块，专为企业级微服务应用设计，提供统一的字典数据管理、多种数据源支持、动态解析等功能。作为 Lambda Cloud 生态的一部分，与其他模块无缝集成，支持复杂的字典管理需求。

### 核心特性

- **多种字典类型**：支持静态字典、URL字典、SQL字典、枚举字典
- **层级组织**：支持多级字典分类和字典项的树形结构
- **动态解析**：实时从SQL查询、HTTP接口、Java枚举获取字典数据
- **多租户支持**：基于租户ID的数据隔离机制
- **状态管理**：字典项的启用/禁用、可选择/不可选择状态控制
- **权限控制**：基于用户角色的字典管理权限
- **缓存优化**：Redis集成和内存缓存支持
- **多数据库支持**：MySQL、Oracle、PostgreSQL等数据库优化
- **扩展架构**：插件式解析器架构，支持自定义数据源

## 技术栈

### 核心技术
- **Java 21+**
- **Spring Boot 3.x**
- **MyBatis Plus** - ORM 框架
- **Lambda Cloud** - 基础框架依赖

### 数据存储
- **MySQL/PostgreSQL/Oracle** - 关系型数据库
- **Redis** - 缓存存储
- **Liquibase** - 数据库版本管理

### Lambda Cloud Starter 集成
- **lambda-fusion-core** - 框架核心库
- **lambda-cloud-starter-security** - 安全框架
- **lambda-cloud-starter-mybatis** - MyBatis 集成
- **lambda-cloud-starter-redis** - Redis 集成
- **lambda-cloud-starter-datasource** - 数据源管理
- **lambda-cloud-starter-logger** - 日志框架
- **lambda-cloud-starter-liquibase** - 数据库版本管理
- **lambda-cloud-starter-dubbo** - Dubbo RPC 框架（可选）

### 外部依赖
- **JSQLParser** - SQL 解析和验证
- **Hutool** - 工具库
- **MapStruct** - 对象映射（通过 core）

## 项目结构

```
lambda-fusion-dict/
├── src/main/java/com/lambda/fusion/
│   ├── autoconfig/
│   │   ├── DictionaryAutoConfiguration.java  # Spring Boot 自动配置
│   │   └── DictionaryProperties.java         # 配置属性绑定
│   └── dict/
│       ├── controller/                       # REST API 控制器
│       │   ├── DictTypeController.java       # 字典类型管理 API
│       │   └── DictInfoController.java       # 字典项管理 API
│       ├── service/                          # 业务逻辑层
│       │   ├── DictTypeService.java          # 字典类型服务接口
│       │   ├── DictInfoService.java          # 字典项服务接口
│       │   └── impl/                         # 服务实现类
│       ├── mapper/                           # MyBatis 数据访问层
│       │   ├── DictTypeMapper.java           # 字典类型数据访问
│       │   ├── DictInfoMapper.java           # 字典项数据访问
│       │   └── DictSqlMapper.java            # SQL 字典查询
│       ├── model/                            # 数据模型
│       │   ├── DictType.java                 # 字典类型实体
│       │   ├── DictTypeTree.java             # 字典类型树形结构
│       │   ├── DictInfo.java                 # 字典项实体
│       │   ├── DynamicDictSource.java        # 动态字典数据源
│       │   ├── QueryDictTypePage.java        # 字典类型分页查询 DTO
│       │   ├── QueryDictInfoPage.java        # 字典项分页查询 DTO
│       │   └── OperationDictState.java       # 字典状态操作 DTO
│       ├── support/                          # 核心支持基础设施
│       │   ├── resolve/                      # 动态字典解析器
│       │   │   ├── DictSourceResolver.java   # 解析器接口
│       │   │   ├── SqlDictResolve.java       # SQL 字典解析器
│       │   │   └── UrlDictResolve.java       # URL 字典解析器
│       │   ├── registry/
│       │   │   └── DictRegistry.java         # 字典注册中心
│       │   ├── scanner/
│       │   │   └── DictEnumScanner.java      # 枚举字典扫描器
│       │   ├── DictHolder.java               # 字典持有者
│       │   ├── DictUsage.java                # 字典用途枚举
│       │   └── DictValueType.java            # 字典值类型枚举
│       ├── DictConfigure.java                # 模块配置类
│       └── DictConstants.java                # 常量定义
└── src/main/resources/
    ├── mapper/                               # MyBatis XML 映射
    │   ├── DictTypeMapper.xml
    │   └── DictInfoMapper.xml
    └── META-INF/db/changelogs/
        └── lambda-dict-changelog.xml         # Liquibase 数据库迁移
```

## 核心功能详解

### 1. 字典类型管理 (Dictionary Type Management)

**主要功能：**
- 字典分类的 CRUD 操作
- 支持层级字典类型（父子关系）
- 多种数据类型支持（静态、URL、SQL、枚举）
- 动态字典解析和组合
- 权限控制（开发者 vs 普通用户）

**核心类：**
- `DictTypeService` - 字典类型业务逻辑服务
- `DictTypeController` - 字典类型 API 控制器
- `DictTypeTree` - 字典类型树形结构实体

### 2. 字典项管理 (Dictionary Item Management)

**主要功能：**
- 字典项的 CRUD 操作
- 状态管理（启用/禁用、可选择/不可选择）
- 树形结构支持（父子关系）
- 多租户数据隔离
- 分页查询和条件过滤

**核心类：**
- `DictInfoService` - 字典项业务逻辑服务
- `DictInfoController` - 字典项 API 控制器
- `DictInfo` - 字典项实体（实现 TreeNode 接口）

### 3. 动态字典解析 (Dynamic Dictionary Resolution)

**支持的字典类型：**

#### 静态字典 (dataType = 0/null)
- 传统的键值对存储在数据库中
- 通过 `la_dict_info` 表管理

#### URL 字典 (dataType = 1)
- 从 HTTP/HTTPS 端点获取数据
- 支持外部 URL 和本地端点
- 自动处理认证（Bearer Token）
- 可配置远程主机前缀

#### SQL 字典 (dataType = 2)
- 通过 SQL 查询获取数据
- 使用 JSQLParser 进行 SQL 验证
- 支持标准列名映射：key, val, sel, pid, id, rank
- 支持层级数据和级别信息

#### 枚举字典 (dataType = 3)
- 基于 Java 枚举的字典
- 使用 `@DictMapper` 注解标记
- 自动扫描和注册
- 运行时访问通过 DictRegistry

**核心类：**
- `DictSourceResolver` - 解析器接口（插件架构）
- `SqlDictResolve` - SQL 字典解析器
- `UrlDictResolve` - URL 字典解析器
- `DictEnumScanner` - 枚举字典扫描器
- `DictRegistry` - 字典注册中心

### 4. 字典注册中心 (Dictionary Registry)

**主要功能：**
- 线程安全的 ConcurrentHashMap 存储
- 字典名称到 DictHolder 对象的映射
- 字典名称到 DictTypeTree 对象的映射
- 枚举字典注册支持
- 静态访问方法

**核心类：**
- `DictRegistry` - 中央字典注册表
- `DictHolder` - 字典持有者对象

## API 接口

### 字典类型管理 API (`/dictType`)

| 方法 | 路径 | 描述 | 权限 |
|------|------|------|------|
| GET | `/` | 获取所有字典分类 | 公开 |
| POST | `/` | 添加字典类型 | 登录用户 |
| PUT | `/` | 更新字典类型 | 登录用户 |
| DELETE | `/{id}` | 删除字典类型 | 登录用户 |
| GET | `/page` | 字典类型分页查询 | 公开 |
| GET | `/tree/composite` | 查询树形结构字典类型（动态） | 公开 |
| GET | `/dict/composite` | 动态字典查询 | 公开 |
| GET | `/dict/enum` | 获取所有枚举字典 | 公开 |

### 字典项管理 API (`/dictInfo`)

| 方法 | 路径 | 描述 | 权限 |
|------|------|------|------|
| GET | `/page` | 字典项分页查询 | 公开 |
| POST | `/` | 添加字典详细信息 | 登录用户 |
| PUT | `/{id}` | 更新字典详细信息 | 登录用户 |
| DELETE | `/{id}` | 删除字典详细信息 | 登录用户 |
| PUT | `/{id}/enable` | 启用字典 | 登录用户 |
| PUT | `/{id}/disable` | 禁用字典 | 登录用户 |
| PUT | `/{id}/selectable` | 设置可选择 | 登录用户 |
| PUT | `/{id}/unselectable` | 设置不可选择 | 登录用户 |
| GET | `/composite` | 获取所有启用的动态字典 | 公开 |
| GET | `/dict/tree/{dictType}` | 查询树形结构数据项 | 公开 |
| GET | `/dict/tree/{type}/data` | 查询包含子集的数据项 | 公开 |
| GET | `/tree/{parentId}` | 根据父节点查询数据项树 | 公开 |
| GET | `/data/select` | 数据项条件查询 | 公开 |

## 配置说明

### 字典配置 (`lambda.fusion.dict`)

```yaml
lambda:
  fusion:
    dict:
      # 是否允许级联删除子类型
      allowedCascadeDelete: false
      # HTTP 字典 URL 前缀
      httpRemoteHostPrefix: ""
      # 是否启用 Dubbo 服务提供者
      enableDubboProvider: false
```

### 服务配置

```yaml
# 字典服务配置
dict:
  service:
    http: false                    # 是否使用 HTTP 协议（默认 HTTPS）
    host: 127.0.0.1               # 服务主机地址
```

## 使用说明

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-dict</artifactId>
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

## 开发指南

### 创建静态字典

```java
// 1. 创建字典类型
POST /dictType
{
  "dictType": "USER_STATUS",
  "dictName": "用户状态",
  "dataType": 0,  // 静态字典
  "dictUsage": 0  // 系统字典（开发者权限）
}

// 2. 添加字典项
POST /dictInfo
{
  "dictType": "USER_STATUS",
  "fieldType": "1",
  "fieldName": "正常",
  "sort": "1",
  "enableState": 1,
  "selectable": 1
}

POST /dictInfo
{
  "dictType": "USER_STATUS",
  "fieldType": "0",
  "fieldName": "禁用",
  "sort": "2",
  "enableState": 1,
  "selectable": 1
}
```

### 创建 SQL 字典

```java
// 创建 SQL 字典类型
POST /dictType
{
  "dictType": "DEPARTMENT_LIST",
  "dictName": "部门列表",
  "dataType": 2,  // SQL 字典
  "dataTypeValue": "SELECT dept_code as key, dept_name as val FROM sys_department WHERE status = 1 ORDER BY sort_no"
}

// 查询动态字典数据
GET /dictType/dict/composite?dictTypeId=DEPARTMENT_LIST
```

### 创建 URL 字典

```java
// 创建 URL 字典类型
POST /dictType
{
  "dictType": "EXTERNAL_API_DATA",
  "dictName": "外部API数据",
  "dataType": 1,  // URL 字典
  "dataTypeValue": "https://api.example.com/dict/data"
}

// 或使用本地端点
POST /dictType
{
  "dictType": "LOCAL_API_DATA",
  "dictName": "本地API数据",
  "dataType": 1,  // URL 字典
  "dataTypeValue": "/api/local/dict/data"
}
```

### 创建枚举字典

```java
// 1. 定义枚举类
@DictMapper(dictName = "GENDER", dictUsage = 0, dictDesc = "性别")
public enum Gender {
    MALE(1, "男"),
    FEMALE(2, "女");
    
    private final Integer val;
    private final String key;
    
    Gender(Integer val, String key) {
        this.val = val;
        this.key = key;
    }
    
    // getters...
}

// 2. 枚举会自动扫描注册，通过以下接口获取
GET /dictType/dict/enum
```

### 查询字典数据

```java
// 获取所有启用的字典
GET /dictInfo/composite

// 获取特定类型的树形字典
GET /dictInfo/dict/tree/USER_STATUS

// 根据父节点查询子字典
GET /dictInfo/tree/{parentId}

// 条件查询字典项
GET /dictInfo/data/select?dictType=USER_STATUS&enableState=1
```

### 状态管理

```java
// 启用字典项
PUT /dictInfo/{id}/enable

// 禁用字典项
PUT /dictInfo/{id}/disable

// 设置为可选择
PUT /dictInfo/{id}/selectable

// 设置为不可选择
PUT /dictInfo/{id}/unselectable
```

### 自定义字典解析器

实现 `DictSourceResolver` 接口创建自定义解析器：

```java
@Service
public class CustomDictResolve implements DictSourceResolver {
    
    @Override
    public boolean isSupport(Integer valueType) {
        return valueType != null && valueType == 4; // 自定义类型
    }
    
    @Override
    public List<DynamicDictSource> doResolve(DictTypeTree dictTypeTree) {
        // 自定义解析逻辑
        List<DynamicDictSource> result = new ArrayList<>();
        
        // 从自定义数据源获取数据
        // ...
        
        return result;
    }
}
```

### 多租户使用

```java
// 字典项会自动关联当前用户的租户ID
POST /dictInfo
{
  "dictType": "TENANT_SPECIFIC",
  "fieldType": "value1",
  "fieldName": "租户专用数据",
  // tenantId 会自动设置
}

// 查询时会自动过滤租户数据
GET /dictInfo/composite?dictType=TENANT_SPECIFIC
```

### 层级字典管理

```java
// 创建父级字典项
POST /dictInfo
{
  "dictType": "REGION",
  "fieldType": "100000",
  "fieldName": "中国",
  "parentId": "0",
  "level": 1
}

// 创建子级字典项
POST /dictInfo
{
  "dictType": "REGION",
  "fieldType": "110000",
  "fieldName": "北京市",
  "parentId": "parent-id",
  "level": 2
}

// 查询树形结构
GET /dictInfo/dict/tree/REGION
```

## 工作原理

### 字典解析流程

```
字典类型创建
    ↓
根据 dataType 选择解析器
    ↓
DictSourceResolver.isSupport() 匹配
    ↓
调用对应解析器的 doResolve()
    ↓
返回 DynamicDictSource 列表
    ↓
转换为统一的字典格式
    ↓
缓存结果（可选）
```

### SQL 字典解析

1. **SQL 验证**：使用 JSQLParser 验证 SQL 语法
2. **执行查询**：通过 DictSqlMapper 执行 SQL
3. **结果映射**：
   - 标准列名：key, val, sel, pid, id, rank
   - 位置映射：第1列=key, 第2列=val, 第3列=pid, 第4列=id, 第5列=sel
4. **构建结果**：转换为 DynamicDictSource 对象

### URL 字典解析

1. **URL 处理**：
   - 外部 URL：直接访问
   - 本地端点：构建完整 URL（协议+主机+端口+上下文路径）
2. **认证处理**：从请求头或 Cookie 获取 Bearer Token
3. **HTTP 调用**：使用 RestTemplate 发起请求
4. **结果反序列化**：JSON 转换为 DynamicDictSource 列表

### 枚举字典注册

1. **扫描阶段**：启动时扫描 @DictMapper 注解的枚举
2. **提取数据**：从枚举字段提取 key-value 对
3. **注册存储**：存储到 DictRegistry 的 ConcurrentHashMap
4. **运行时访问**：通过静态方法获取注册的枚举字典