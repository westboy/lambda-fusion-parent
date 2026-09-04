# lambda-fusion-authority

`lambda-fusion-authority` 是 Lambda Fusion 的权限与身份域模块，提供用户、角色、资源、组织、租户、客户端与令牌管理，并通过 Sa-Token 实现登录态与权限校验。

## 模块定位

- 提供 RBAC 领域模型与管理 API。
- 提供登录上下文接口（菜单、权限、用户信息）。
- 提供租户上下文拦截与多租户访问隔离入口。
- 提供 Dubbo 形式的远程认证服务暴露能力。

## 目录结构（核心）

```text
src/main/java/com/lambda/fusion/
├─ autoconfig/AuthorityAutoConfiguration.java
└─ authority/
   ├─ AuthorityConfigure.java
   ├─ AuthorityProperties.java
   ├─ AuthorityConstants.java
   ├─ controller/ (Authentication/User/Role/Resource/Organization/Tenant/Client/ApiToken/Area)
   ├─ service/ + service/impl/
   ├─ mapper/ + resources/mapper/*.xml
   ├─ model/ (user/role/resource/tenant/organization/...)
   ├─ manager/
   ├─ helper/
   ├─ inteceptor/TenantContextInterceptor.java
   └─ listener/(UserOnlineLogListener, UserSseEventListener)

src/main/resources/META-INF/
├─ spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└─ db/changelogs/lambda-authority-*.xml
```

自动装配注册项：

```text
com.lambda.fusion.autoconfig.AuthorityAutoConfiguration
```

## 自动装配机制

### AuthorityAutoConfiguration

- `@AutoConfiguration(before = SecurityAutoConfiguration.class)`
- `@Import(AuthorityConfigure.class)`

### AuthorityConfigure

核心装配点：

- `@MapperScan("com.lambda.fusion.authority.**.mapper")`
- `@EnableConfigurationProperties(AuthorityProperties.class)`
- 注册 `TenantContextInterceptor` 到 `/**`（高优先级）
- 注册 `EntityMetaFiller`（createdAt/createdBy/updatedAt/updatedBy）
- 注册 `operationLogExecutor` 线程池
- 条件注册：
  - `SaTokenListener` -> `UserOnlineLogListener`
  - `SseEventListener` -> `UserSseEventListener`
  - `ServiceBean<RemoteAuthenticationService>`（Dubbo 存在时）

## 配置模型

配置前缀：`lambda.fusion.authorize`

- `use-org-name-as-id` 默认 `false`
- `password-strategy.mode` 默认 `RANDOM`（`FIXED/RANDOM/CIPHERTEXT`）
- `password-strategy.customize` 默认 `123456`
- `password-strategy.enable-period-change` 默认 `false`
- `password-strategy.period-change-days` 默认 `90`
- `third-part.enabled` 默认 `false`，为 `true` 时才注册支付宝/微信/钉钉第三方登录 Bean
- `third-part.auto-register` 默认 `false`

示例：

```yaml
lambda:
  fusion:
    authorize:
      use-org-name-as-id: false
      password-strategy:
        mode: RANDOM
        customize: "123456"
        enable-period-change: false
        period-change-days: 90
```

## 核心领域与 API 边界

主要控制器分组：

- `/`：认证上下文（`/menus`、`/userinfo`、`/permissions`）
- `/authority/users`：用户管理
- `/authority/roles`：角色管理
- `/authority/tenant`：租户管理
- `/authority/areas`：区域管理
- `/authority/api-token`：令牌管理

领域常量与枚举：

- 默认角色集合：`ROLE_SYSTEM/ROLE_ADMIN/ROLE_DEV/ROLE_USER/ROLE_MANAGER/ROLE_ORG/ROLE_TENANT_MANAGER`
- `RoleType`：功能角色 / 数据角色
- `MenuType`：菜单 / 内嵌页 / 按钮 / 接口 / 外链

## 认证与授权链路

`AuthenticationServiceImpl` 关键行为：

1. 按用户名/手机号查询用户认证信息。
2. 聚合角色权限与接口权限。
3. 根据角色生成导航菜单树并填充路由元数据。
4. 输出当前登录用户信息（含 token、角色、头像等）。

租户角色处理特点：

- 存在 `ROLE_TENANT@orgId` 的角色重写逻辑。
- 会尝试从 `TenantHolder` 注入当前租户上下文。

## 租户上下文拦截

`TenantContextInterceptor` 顺序：

1. 优先读取请求头 `TENANT_ID_HEADER`。
2. 若缺失，按 `serverName` 从 Redis 映射表回查租户。
3. 请求结束后清理 `TenantContextHolder`。

## 事件监听与在线状态

- `UserOnlineLogListener`：监听 Sa-Token 登录/登出事件，记录在线状态。
- `UserSseEventListener`：监听 SSE 连接/断开事件并回调在线服务（连接→上线，断开→下线）。

## 依赖说明

关键依赖（见 `pom.xml`）：

- `com.lambda.cloud:lambda-fusion-core`
- `com.lambda.cloud:lambda-fusion-permission`
- `com.lambda.cloud:lambda-cloud-starter-security`
- `com.lambda.cloud:lambda-cloud-starter-mybatis`
- `com.lambda.cloud:lambda-cloud-starter-redis`
- `com.lambda.cloud:lambda-cloud-starter-liquibase`
- `com.lambda.cloud:lambda-cloud-starter-cache`
- `com.lambda.cloud:lambda-cloud-starter-sse`
- `com.lambda.cloud:lambda-cloud-starter-dubbo`（optional）

## 当前实现约束

- `TenantContextInterceptor` 强依赖 `RedisHelper` 进行域名到租户映射回查。
- `EntityMetaFiller` 依赖 `SecurityUtils.getUser()`，匿名上下文下需关注填充行为。
- `UserSseEventListener`：SSE 断开即记录下线，无重连宽限期，网络抖动重连期间在线状态可能短暂闪烁。
