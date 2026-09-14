# Lambda Fusion Permission DataScope

`lambda-fusion-permission-datascope` 是 Lambda Fusion 框架中的**数据权限控制面**模块。它与底层的 `lambda-cloud-starter-mybatis`（数据面）协同工作，共同实现了细粒度的行级数据安全隔离。

- **控制面（本模块）**：负责数据权限的**定义、分配和生命周期自动维护**——权限树渲染、授权落库、组织架构变更后的自动同步。
- **数据面（底座 starter）**：负责**查询期 SQL 改写**——MyBatis 拦截器根据当前登录用户把 `SELECT` 语句追加数据权限过滤条件。

## 模块职责

1.  **统一的权限分配 API**：提供标准的 REST 接口（`/datascope/tree` 和 `/datascope/grant`），供前端统一渲染并提交任何业务类型（如部门、项目、客户等）的权限树。
2.  **策略模式（View Provider）**：引入 `DataViewProvider` 接口，解耦业务模型与权限树的构建。业务模块只需实现该接口，即可将自身的数据结构转换为标准的 `DataScopeNode`，无需创建复杂的数据库物理视图。
3.  **Smart 自动同步机制**：监听业务对象（如组织架构）的生命周期事件（创建、更新、删除、移动），自动修复并同步底层权限表 `la_datascopes`，防止因业务数据变更导致权限孤儿或越权。

## 核心架构设计

### 泛化授权主体 (Target Type)
打破传统的“只能给角色授权”的限制，本模块支持向**任何类型的主体**进行数据授权：
*   `USER`: 直接授权给特定用户
*   `ROLE`: 授权给系统角色
*   `ORG`: 授权给整个组织机构
*   `GROUP`: 授权给临时项目组或用户组
*   `CLIENT`: 授权给第三方 API 客户端

> ⚠️ **运行期匹配范围**：数据面拦截器（`DataScopeEvaluator#getDataScopeIds`）目前只按 `USER:<用户名>` 与 `ORG:<用户所属组织ID>` 生成匹配条件。即 `USER` / `ORG` 两类授权在查询期直接生效；`ROLE` / `GROUP` / `CLIENT` 授权记录可落库、可在权限树上回显，但查询期生效需要业务侧自行扩展（例如把角色授权展开为成员的 `USER` 记录，或扩展 scope id 生成逻辑）。

### 资源维度隔离 (Domain Type)
通过 `domain_type` 字段隔离不同的业务域，一个整数代表一种业务数据视图：
*   `0`: 组织架构 / 部门数据
*   `1`: 项目管理数据
*   `2`: CRM 客户数据
*   ...支持无限扩展

## 数据表结构 (`la_datascopes`)

由本模块的 Liquibase changelog（`META-INF/db/changelogs/lambda-datascope-changelog.xml`）自动建表：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `ID` | varchar(32) | 业务数据 ID（部门 ID、项目 ID……），联合主键 |
| `TID` | varchar(32) | 授权主体 ID（用户 ID、角色 ID……），联合主键 |
| `TARGET_TYPE` | varchar(32) | 主体类型：`USER`/`ROLE`/`ORG`/`GROUP`/`CLIENT`，联合主键 |
| `DOMAIN_TYPE` | int | 业务数据类型（0-部门、1-项目……），联合主键 |
| `RANK_LEVEL` | int | 层级，配合注解 `level`/`levelExp` 做层级比较（预留） |
| `CHECKED` | int | 勾选状态：1 全选、2 半选（树形级联语义） |
| `TENANT_ID` | varchar(32) | 多租户隔离字段（Mapper 已标记跳过租户插件，隔离由查询逻辑显式携带） |

一行数据的含义：**主体 `TARGET_TYPE:TID` 拥有 `DOMAIN_TYPE` 域下节点 `ID` 的数据权限**。

## 核心配置

### 控制面 (`lambda.fusion.datascope`)

```yaml
lambda:
  fusion:
    datascope:
      smart:
        # 是否开启 Smart 自动同步机制（默认 false）
        enabled: true
        # 配置哪些业务实体（businessKey）的生命周期事件触发哪些 domain_type 的同步
        types:
          "com.lambda.fusion.authority.model.organization.Organization": [0]
```

### 数据面 (`lambda.datascope`，底座 starter 提供)

通常无需配置，以下为可覆盖项及默认值：

```yaml
lambda:
  datascope:
    # 超级管理员身份标识（按 LoginUser.name 匹配），命中则跳过数据权限过滤
    super-admin-identifiers: [admin]
    # 代码中的 type 值 → 数据库 domain_type 值的映射（多环境编号不一致时使用）
    type-mapper:
      0: 0
    # 权限表与列名（一般保持默认）
    data-scope-table-name: la_datascopes
    data-scope-table-alias: datascope
    data-scope-id-column: id
    data-scope-tid-column: tid
    data-scope-target-type-column: target_type
    data-scope-type-column: domain_type
    data-scope-rank-column: rank_level
    data-scope-checked-column: checked
    # ORGANIZATION 方案使用的组织表
    organization-table-name: la_organization
    organization-table-alias: org
    organization-id-column: id
    organization-parent-keys-column: parent_keys
```

## 使用指南

### 第一步：实现 DataViewProvider（定义某个 domain_type 的权限树）

以部门树（type = 0）为例，真实实现见 `lambda-fusion-authority` 的 `OrganizationDataViewProvider`：

```java
@Component
public class OrgDataViewProvider implements DataViewProvider {

    @Override
    public boolean supports(int type) {
        return type == 0; // 本 Provider 负责处理 domain_type = 0
    }

    @Override
    public List<DataScopeNode> loadDataView(String tenantId) {
        // 1. 查询业务数据（平铺列表即可，模块会统一用 TreeBuilder 组树）
        List<Org> orgs = orgService.selectByTenant(tenantId);
        Map<String, Boolean> hasChild = orgs.stream()
                .filter(o -> o.getParentId() != null)
                .collect(Collectors.toMap(Org::getParentId, o -> true, (l, r) -> l));
        // 2. 转换为 DataScopeNode
        return orgs.stream().map(o -> {
            DataScopeNode node = new DataScopeNode();
            node.setId(o.getId());
            node.setName(o.getName());
            node.setPid(o.getParentId());
            node.setLevel(o.getLevel());
            node.setLastStage(!hasChild.containsKey(o.getId())); // 末级节点标记
            node.getProps().put("category", o.getCategory());    // 前端扩展属性
            return node;
        }).collect(Collectors.toList());
    }
}
```

要点：
- `loadDataView` 返回**全量扁平节点**，不要自己去组树、也不要回填 `checked`（回填由 `DataScopeGrantServiceImpl` 完成）。
- 多个 Provider 通过 `supports(type)` 区分，`type` 冲突时取先注册者。

### 第二步：在业务 Mapper 上标注 @DataScope（查询期过滤）

在需要数据权限过滤的 **Mapper 接口方法**上添加 `@DataScope`（只对 `SELECT` 生效）：

```java
public interface ProjectMapper extends BaseMapper<ProjectEntity> {

    /**
     * key:     业务表与权限表关联的字段（业务行对应的权限树节点 ID 所在列）
     * type:    domain_type，对应 la_datascopes.DOMAIN_TYPE
     * level:   层级匹配值，配合 levelExp 与 RANK_LEVEL 比较；0 视为 Integer.MAX_VALUE
     * levelExp: 比较运算符 EQ/GT/LT/GE/LE，默认 EQ
     * mode:    SUB_QUERY(默认) / INNER / STATISTICS
     * scheme:  CASCADE(默认，按授权表级联) / ORGANIZATION(按组织表树)
     * checked: 0 忽略 / 1 只查全选 / 2 只查半选（级联模式下生效）
     * condition: 追加到权限子查询上的额外条件，如 "target_type != 'CLIENT'"
     */
    @DataScope(key = "T.id", type = {1})
    List<ProjectEntity> selectProjects(@Param("query") ProjectQuery query);
}
```

默认（`mode = SUB_QUERY`, `scheme = CASCADE`）时，原 SQL：

```sql
SELECT * FROM la_project T WHERE T.status = 'ACTIVE'
```

会被改写为：

```sql
SELECT * FROM la_project T WHERE T.status = 'ACTIVE'
  AND T.id IN (
      SELECT DISTINCT datascope.id FROM la_datascopes datascope
      WHERE ((datascope.target_type = 'USER' AND datascope.tid = 'zhangsan')
          OR (datascope.target_type = 'ORG'  AND datascope.tid = '1001'))
        AND datascope.domain_type IN (1)
        AND datascope.rank_level <= 2147483647
  )
```

各模式差异：

| 模式 | 改写方式 | 适用场景 |
| :--- | :--- | :--- |
| `SUB_QUERY`（默认） | `key IN (子查询)` | 通用场景，SQL 可读性好 |
| `INNER` | `JOIN LATERAL (SELECT DISTINCT ...) datascope ON datascope.id = key` | 大数据量下性能更好（需 MySQL 8+） |
| `STATISTICS` | 同子查询，但会下钻到聚合/派生表的最内层 `SELECT` 再注入条件 | `COUNT`/`SUM` 等统计查询 |
| `scheme = ORGANIZATION` | 不查授权表，改为 `JOIN la_organization org ON org.id = key WHERE org.id = '当前组织' OR org.parent_keys LIKE '%当前组织%'` | “看本部门及下级全部数据”这类纯组织维度过滤 |

> 注意：`INNER` 模式使用 `LATERAL` 语法，`replace` 占位符场景在 `INNER` 模式下不支持（会抛 `UnsupportedOperationException`）。

#### 预处理模式（大数据量）

权限节点数很大时，子查询每次执行代价高，可开启 `pretreatment = true`：拦截器先一次性查出当前用户的权限 ID 集合，再以内联 `IN ('id1','id2',...)` 字面量方式注入。用户无任何权限时直接短路返回空结果（`List` 返回空集、`int`/`Integer` 返回 0、`long`/`Long` 返回 0L）。权限数超过 1000 会在日志中告警（可能触发 SQL 长度/参数上限问题）。

#### Replace 占位符模式（注解不便于标注时）

在 SQL 中写入占位符 `'permissions|<type>[,<type>...]|<levelExp><level>'`，拦截器通过正则识别并强制走子查询替换，无需 `@DataScope` 注解：

```xml
<select id="selectOrgStats" resultType="map">
    SELECT org_id, COUNT(*) FROM la_employee
    WHERE org_id IN ('permissions|0|>=1')
    GROUP BY org_id
</select>
```

- `|0`：domain_type 列表（可多个，逗号分隔）
- `|>=1`：层级表达式，运算符支持 `= > < >= <=`，可省略运算符（默认 `=`）或整段省略
- 超级管理员执行时，整个 `IN ('permissions...')` 被替换为 `1 = 1`
- 普通用户执行时，占位符被替换为按其身份构建的授权子查询 SQL

#### 编程式动态指定（DataScopeContextHolder）

同一个 Mapper 方法需要在运行时按调用方切换过滤维度时，可用 ThreadLocal 上下文，**优先级高于方法上的 `@DataScope` 注解**：

```java
try (DataScopeContextHolder holder = DataScopeContextHolder.getInstance()) {
    DataScopeContextHolder.setDataScopeContext(
            new int[]{1},        // type: domain_type
            "T.dept_id",         // key: 关联字段
            2,                   // level
            DataScope.Mode.SUB_QUERY,
            DataScope.Scheme.CASCADE);
    projectMapper.selectProjects(query);
} // close() 自动清理 ThreadLocal，务必使用 try-with-resources
```

### 第三步：前端分配权限（REST API）

| 接口 | 说明 |
| :--- | :--- |
| `GET /datascope/tree/{type}?targetId=&targetType=` | 加载 domain_type 对应的完整权限树，并回填该主体已授权节点的 `checked` 状态 |
| `POST /datascope/grant` | 保存授权结果（先删后插，事务内全量覆盖） |

保存授权请求示例：

```json
POST /datascope/grant
{
  "targetId": "10086",          // 主体 ID
  "targetType": "USER",         // USER / ROLE / ORG / GROUP / CLIENT
  "type": 0,                    // domain_type
  "nodes": [
    { "id": "dept-001", "checked": 1, "level": 1 },  // 1 全选
    { "id": "dept-002", "checked": 2, "level": 2 }   // 2 半选
  ]
}
```

注意：
- `nodes` 中的 `checked`/`level` 会原样写入 `la_datascopes.CHECKED` / `RANK_LEVEL`，`checked` 直接决定查询期 `checked` 过滤与 Smart 继承逻辑。
- 内置超级管理员角色（`ROLE` + `ROLE_ADMIN`）拒绝直接授权。
- `targetId`/`targetType`/`type` 必填，后端按「主体 + 域」整体覆盖式保存。

### 第四步：业务侧发布变更事件（Smart 自动同步）

业务对象的生命周期变化会破坏授权关系（节点被删、被移动），通过发布 `DataScopeObjectChangedEvent` 让模块自动修复 `la_datascopes`。真实实现见 `OrganizationServiceImpl`：

```java
// 1. 创建节点：新节点自动继承父节点上 checked=1 的授权主体，
//    操作人本人（非管理员时）也自动获得该节点权限
applicationEventPublisher.publishEvent(
    DataScopeObjectChangedEvent.created(this, Organization.class.getName(),
        orgId, parentId, operator));

// 2. 更新节点（父级发生变化时，按新旧父级授权主体做差量增删）
applicationEventPublisher.publishEvent(
    DataScopeObjectChangedEvent.updated(this, Organization.class.getName(),
        orgId, parentId, previousParentId, operator));

// 3. 删除节点：清理该节点在指定 domain_type 下的全部授权记录
applicationEventPublisher.publishEvent(
    DataScopeObjectChangedEvent.deleted(this, Organization.class.getName(),
        orgId, operator));

// 4. 移动子树：cascadeObjectIds 为随移动而变更的全部节点 ID，
//    逐个按新旧父级授权主体做差量增删
applicationEventPublisher.publishEvent(
    DataScopeObjectChangedEvent.moved(this, Organization.class.getName(),
        orgId, parentId, previousParentId, cascadeChildIds, operator));
```

要点：
- `businessKey`（第 2 个参数）必须与配置 `lambda.fusion.datascope.smart.types` 的 key 完全一致（约定用实体类全限定名），否则事件被忽略。
- `operator` 传 `UserDetails`；`smartForCreated` 中操作人（非管理员）会自动获得新节点权限，`smartForUpdated`/`smartForMoved` 的差量删除会保护操作人本人的授权不被移除。
- Smart 只维护 `checked = 1`（全选）记录的继承关系；半选（2）记录不参与级联。
- 需要监听的事件类型与 domain_type 必须在配置中登记（见「核心配置」），未登记的 businessKey 不做任何同步。

## 完整接入示例：新增“项目数据”（domain_type = 1）

1. **pom 引入**（通常由聚合模块传递，无需手动添加）：

```xml
<dependency>
    <groupId>com.lambda.fusion</groupId>
    <artifactId>lambda-fusion-permission-datascope</artifactId>
</dependency>
```

2. **实现 Provider**：`ProjectDataViewProvider implements DataViewProvider`，`supports(type)` 返回 `type == 1`。
3. **Mapper 标注**：项目查询方法加 `@DataScope(key = "T.id", type = {1})`（业务行的 `id` 即权限树节点 ID）。
4. **配置登记**：`lambda.fusion.datascope.smart.types` 中增加项目实体的映射 `[1]`，项目增删改后发布对应事件。
5. **前端授权**：调用 `GET /datascope/tree/1?targetId=...&targetType=USER` 渲染项目树，提交 `POST /datascope/grant`。

## 运行期行为细节（排查问题时参考）

- **只拦截 `SELECT`**：增删改不受影响。
- **操作者来源**：优先取 Mapper 参数中的 `LoginUser` 类型参数；取不到时回退 `OperatorUtils.getOperator()`（Sa-Token 当前会话）。**两者都取不到时该查询直接跳过过滤**并打 WARN 日志——定时任务、异步线程等无登录上下文的场景要注意这是“放行”而非“拦截”。
- **超级管理员**：`operator.getName()` 命中 `lambda.datascope.super-admin-identifiers`（默认 `[admin]`）时跳过全部过滤（replace 模式下替换为 `1 = 1`）。
- **无权限短路**：`pretreatment = true` 且用户权限集为空时，直接返回空结果，不再执行原 SQL。
- **type 映射**：`lambda.datascope.type-mapper` 在注解 type 进入 SQL 前做「代码值 → 库值」转换，代码与数据库编号不一致时使用。
- **SQL 解析失败不静默降级**：非 replace 模式下 JSqlParser 解析失败会直接抛异常，暴露问题而不是放过过滤。

## 注意事项
*   超级管理员（内置 `ROLE_ADMIN` 等标识）。