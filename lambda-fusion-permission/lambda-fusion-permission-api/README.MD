# Lambda Fusion Permission API

`lambda-fusion-permission-api` 管理接口权限元数据的加载、注册、查询、匹配及跨服务上报。请求鉴权由 Lambda Cloud 的 Sa-Token 安全链承担，角色和资源授权由 authority 管理。

## 模块职责

固定接口通过标准 `@SaCheckPermission` 声明权限，`lambda-cloud-processor` 在编译期生成 `META-INF/permissions/api-permissions.json`。本模块复用该产物，不要求业务模块另维护同一份接口目录。

1.  **客户端（Client）**：
    *   **本地加载**：`LocalPermissionLoader` 聚合 classpath 中处理器生成的 JSON；`PermissionClientInitializer` 在启动时写入 `ApiPermissionRegistry`。
    *   **权限上报**：`client.push-enabled=true` 时通过 `PermissionPushClient` 上报，并按 `push-interval-seconds` 定期重报。
    *   **匹配能力**：`ApiPermissionMatcher` 根据 HTTP Method 和 Path 匹配目录项；它返回元数据，不自行执行用户权限校验。

2.  **服务端（Server）**：
    *   提供 `PermissionSyncApi` 的 Dubbo RPC 接口实现，接收各个业务子系统上报的 API 权限点。
    *   `ApiPermissionRegistry` 提供本地及按应用归集的上报目录，供 authority 查询接口并绑定到资源；角色授权仍使用既有资源授权关系。

3. **动态目录**：`ApiPermissionProvider` 为无法在编译期枚举的权限点提供运行期目录。提供方从业务唯一事实来源派生 `ApiPermissionMetadata`，保留应用与租户边界，不另建权限登记表。动态表单采用此扩展，静态目录重新加载不会覆盖动态目录；当前动态租户目录不混入无租户上下文的全局静态上报。

## 请求鉴权与开发者豁免

- Cloud `SaTokenInterceptor` 使用活跃登录类型，先执行 `ROLE_DEV` 豁免，再进入 Sa-Token 注解检查和 `SecureInterceptor`。
- 固定接口使用 `@SaCheckPermission`；请求期才能确定权限码的接口，可实现 `SaAnnotationHandlerInterface`，由 Sa-Token 的 `SaBeanInject` 注册进同一注解鉴权链，避免另建独立 MVC 权限拦截器。
- 业务内部跨能力调用（例如保存草稿时自动发布）仍需检查目标能力权限，使用 core 的 `AuthUtils.checkPermission`；权限展示使用 `AuthUtils.hasPermission`，二者沿用活跃登录类型与开发者豁免。
- 豁免只针对操作权限，不改变租户过滤、数据范围与业务状态校验。普通用户仍通过 resource/API 绑定和角色授权取得具体权限。

当前源码没有 `PermissionSecureInterceptor` 实现，旧文档对此的描述不准确。`client.check-enabled` 和 `client.deny-unmatched` 目前仅为配置字段，没有请求拦截器消费，不能把它们视为已生效的鉴权开关。

## 核心配置 (`lambda.fusion.permission`)

```yaml
lambda:
  fusion:
    permission:
      enabled: true
      mode: client # client / server，默认 client
      client:
        resource-path: "META-INF/permissions/api-permissions.json"
        push-enabled: false
        push-interval-seconds: 300
        fail-fast: false
        auth-token: "${PERMISSION_AUTH_TOKEN:}"
      server:
        auth-token: "${PERMISSION_AUTH_TOKEN:}"
```

## 工作原理

1. 编译：processor 提取固定接口的路径、方法及权限标识，生成 JSON。
2. 装配：loader/registry 加载本地目录，按配置上报；动态提供方在有合法上下文时补充目录。
3. 授权：authority 将接口权限绑定到资源，并通过既有角色授权授予用户。
4. 请求：Cloud 统一安全链执行开发者豁免与注解鉴权；业务操作继续遵守租户及数据契约。
