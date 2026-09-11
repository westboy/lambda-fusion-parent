# Lambda Fusion Logger

Lambda Fusion Logger 是 Lambda Fusion 框架中的操作日志模块，基于 Lambda Cloud 构建。它为 `lambda-cloud-starter-logger` 的 `@OperationLog` 切面提供统一的落库实现（顶替 starter 默认实现），支持异步落库、多存储后端路由（内置 MySQL，预留 KAFKA / ES）、多租户、分布式部署下经 Dubbo 远程落库，并提供操作日志查询 REST API。

## 项目概述

starter 的 `@OperationLog` 切面在 AOP finally 中通过 `OperationService` SPI 同步回调日志记录，默认实现并不落库。本模块实现 `OperationLogApi` 契约（继承 `OperationService` SPI）注册为本地 bean 顶掉默认实现，将日志经 `LogStorageResolver` 路由到当前存储后端统一落库，并提供分页查询与模块检索接口。

### 核心特性

- **落库实现**：实现 starter 的 `OperationService` SPI，自动顶替默认实现，业务方只需标注 `@OperationLog`
- **异步落库**：独立线程池异步写库，不阻塞业务线程；队列满时回退调用方线程（CallerRunsPolicy），保证日志不丢且有反压
- **多存储后端**：`LogStorage` SPI 插件式架构，内置 MySQL 实现（`la_operation_logs` 表），预留 KAFKA / ES
- **读写一致**：读与写都经 `LogStorageResolver` 按 `storageType` 路由到同一后端，切换后端时读写一致
- **多租户支持**：在请求线程内捕获租户上下文后再投递异步执行（异步线程拿不到 ThreadLocal），租户字段随日志落库
- **分布式部署**：落库服务经 Dubbo 以 `OperationLogApi` 暴露，未部署本模块的服务通过 `lambda-fusion-logger-api` 装配远程引用，日志统一落到部署本模块的服务
- **失败不外抛**：任何落库异常仅记录日志，不影响业务主流程
- **数据库版本管理**：Liquibase 自动创建表结构与索引

## 技术栈

- **Java 21+**
- **Spring Boot 3.x**
- **MyBatis Plus** - ORM 框架
- **Liquibase** - 数据库版本管理
- **MapStruct** - record ↔ entity 转换（经 `OperationLogConverter`）
- **Sa-Token** - 接口权限控制
- **Apache Dubbo** - 分布式远程落库（可选依赖）
- **Lambda Cloud** - 基础框架依赖（`lambda-cloud-starter-logger` / `security` / `mybatis` / `liquibase` / `datasource`）

## 项目结构

```
lambda-fusion-logger/
├── src/main/java/com/lambda/fusion/
│   ├── autoconfig/
│   │   └── LoggerAutoConfiguration.java      # Spring Boot 自动配置入口
│   └── logger/
│       ├── LoggerConfigure.java              # 模块装配类（线程池、Dubbo 暴露、MapperScan）
│       ├── LoggerProperties.java             # 配置属性绑定（lambda.fusion.logger.*）
│       ├── controller/
│       │   └── OperationLogController.java   # 操作日志查询 REST API
│       ├── service/
│       │   └── OperationLogService.java      # 落库服务（实现 OperationLogApi）
│       ├── storage/                          # 存储层
│       │   ├── LogStorage.java               # 日志存储 SPI
│       │   ├── LogStorageResolver.java       # 按存储类型路由
│       │   ├── OperationLogConverter.java    # MapStruct 转换器
│       │   └── mysql/                        # MySQL 实现
│       │       ├── MysqlLogStorage.java      # MySQL 存储实现（读写一体）
│       │       ├── entity/OperationLogEntity.java
│       │       └── mapper/OperationLogMapper.java
│       └── exception/                        # 业务异常与错误码（15000-15999 段）
├── src/test/java/                            # 单元测试
└── src/main/resources/META-INF/
    ├── spring/...AutoConfiguration.imports   # 自动配置注册
    └── db/changelogs/lambda-logger-changelog.xml  # Liquibase 迁移
```

配套的契约模块 **lambda-fusion-logger-api**（独立部署的引用方依赖）：

```
lambda-fusion-logger-api/
└── src/main/java/com/lambda/fusion/
    ├── autoconfig/LoggerClientAutoConfiguration.java  # Dubbo 远程引用装配
    └── logger/api/
        ├── OperationLogApi.java    # 远程契约（OperationService SPI + 分页查询）
        └── OperationLogQuery.java  # 通用查询条件
```

## 核心机制

### 日志采集与落库流程

```
业务方法标注 @OperationLog
    ↓
starter 的 OperationLoggerAdvice 切面（AOP finally 中回调）
    ↓
OperationLogService.save()（顶替 starter 默认实现）
    ├─ 请求线程内补租户（TenantContextHolder，异步线程拿不到 ThreadLocal）
    ├─ operator 为空时回填 operatorId
    └─ async=true → 投递 loggerExecutor 线程池；async=false → 同步执行
        ↓
LogStorageResolver.resolve(storageType) 路由到存储后端
    ↓
MysqlLogStorage.saveOperation() 写入 la_operation_logs（失败仅记日志，不外抛）
```

### 单体 vs 分布式部署

- **单体部署**：本模块与业务同进程，`OperationLogService` 作为本地 bean 直接被切面调用，不经远程。
- **分布式部署**：`LoggerConfigure` 在 classpath 存在 Dubbo 时将 `OperationLogService` 以 `@DubboService` 暴露为 `OperationLogApi`；其他服务引入 `lambda-fusion-logger-api` 后由 `LoggerClientAutoConfiguration` 装配 Dubbo 引用（`@ConditionalOnMissingBean(OperationService.class)`），顶替 starter 默认实现，其采集的日志经 Dubbo 落到部署本模块的服务统一落库。

### 扩展存储后端

实现 `LogStorage` SPI 即可接入新后端（如 KAFKA / ES）：

```java
@Component
public class KafkaLogStorage implements LogStorage {

    @Override
    public String type() {
        return "KAFKA";
    }

    @Override
    public void saveOperation(OperationLogRecord record) {
        // 写失败不抛出影响业务的异常，记录失败原因即可
    }

    @Override
    public PageView<OperationLogRecord> pageOperationLogs(OperationLogQuery query) {
        // 读写都经 LogStorageResolver 路由，需实现查询以保证切换后端时读写一致
        ...
    }

    @Override
    public List<String> listModules(String keyword) {
        ...
    }
}
```

注册为 Spring bean 后，将 `lambda.fusion.logger.storage-type` 配置为 `KAFKA` 即可生效。

## API 接口

### 操作日志查询 API（`/logger/operation`）

| 方法 | 路径 | 描述 | 权限 |
|------|------|------|------|
| GET | `/page` | 分页查询操作日志 | `logger:operation:list` |
| GET | `/page/{number}` | 分页查询（指定页码） | `logger:operation:list` |
| GET | `/page/{number}/size/{size}` | 分页查询（指定页码与页大小） | `logger:operation:list` |
| GET | `/modules` | 按关键字模糊检索已有业务模块（去重，限前 50），供前端下拉远程检索 | `logger:operation:list` |

分页查询支持的过滤条件（`OperationLogQuery`）：

| 参数 | 类型 | 说明 |
|------|------|------|
| `module` | String | 业务模块（精确匹配） |
| `operator` | String | 操作人用户名（模糊匹配） |
| `startTime` / `endTime` | 时间 | 操作时间范围 |
| `pageNum` / `pageSize` | Integer | 分页参数 |

示例：

```
GET /logger/operation/page/1/size/20?module=数据源管理&operator=admin
GET /logger/operation/modules?keyword=数据
```

## 数据库表

`la_operation_logs`（Liquibase 自动创建，幂等：表已存在则跳过）：

| 字段 | 类型 | 说明 |
|------|------|------|
| ID | varchar(32) | 主键 |
| TENANT_ID | varchar(32) | 租户 |
| MODULE | varchar(100) | 业务模块 |
| METHOD | varchar(255) | 执行方法（类名.方法名） |
| DESCRIPTION | varchar(500) | 操作描述 |
| HTTP_METHOD | varchar(16) | HTTP 请求方法 |
| DETAIL | text | 操作详情 |
| COST | bigint | 执行耗时（毫秒） |
| IP | varchar(64) | 客户端 IP |
| OPERATOR | varchar(64) | 操作人用户名 |
| OPERATOR_ID | varchar(64) | 操作人用户 ID |
| TIME | datetime | 操作时间 |
| CREATED_AT / UPDATED_AT 等 | — | 审计字段 |

索引：`idx_oplog_tenant_time (TENANT_ID, TIME)`、`idx_oplog_operator (OPERATOR)`、`idx_oplog_module (MODULE)`。

## 配置说明

### 模块配置（`lambda.fusion.logger`）

```yaml
lambda:
  fusion:
    logger:
      # 是否启用模块（默认 true）
      enabled: true
      # 存储后端类型：MYSQL（默认），预留 KAFKA / ES
      storageType: MYSQL
      # 是否异步落库（默认 true，异步线程池执行，避免阻塞业务线程）
      async: true
      # 异步落库线程池
      executor:
        corePoolSize: 5     # 核心线程数
        maxPoolSize: 10     # 最大线程数
        queueCapacity: 1000 # 队列容量（满时回退调用方线程）
```

线程池拒绝策略为 `CallerRunsPolicy`：队列满时由调用方线程执行落库，保证日志不丢且形成反压；线程名前缀 `LoggerExecutor-`，空闲 30 秒回收。

## 使用说明

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-logger</artifactId>
    <version>2026.1.1-SNAPSHOT</version>
</dependency>
```

分布式部署下，仅需统一落库的服务引入本模块；其余服务引入 `lambda-fusion-logger-api` 走 Dubbo 远程落库（需依赖 `lambda-cloud-starter-dubbo`）。

### 2. 标注操作日志

模块经 Spring Boot 自动配置（`AutoConfiguration.imports`）自动启用，业务方法标注 `@OperationLog` 即可：

```java
import com.lambda.cloud.logger.annotation.OperationLog;

@RestController
@RequestMapping("/dataSource")
public class DataSourceController {

    @OperationLog(module = "数据源管理", value = "新增数据源")
    @PostMapping
    public void create(@RequestBody DataSourceRequest request) {
        // 业务逻辑
    }
}
```

注解配置项：

| 属性 | 说明 | 默认值 |
|------|------|--------|
| `value` | 操作描述，如 "新增数据源"；不指定则使用方法全限定名 | `""` |
| `module` | 所属业务模块，用于分类管理；建议使用有意义的模块名 | `"模块"` |
| `type` | 操作类型（如 CREATE / UPDATE / DELETE / QUERY）；不指定则根据 HTTP 方法自动推断 | `""` |

### 3. 配置数据库

确保数据库连接配置正确，Liquibase 会自动创建 `la_operation_logs` 表结构与索引。

## 错误码

错误码占用 **15000-15999** 段（`LoggerErrorCode`）：

| 错误码 | 说明 |
|--------|------|
| 15900 | 系统内部错误 |
| 15901 | 参数无效 |
| 15902 | 不支持的日志存储类型 |
