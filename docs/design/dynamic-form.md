# 动态表单（lambda-fusion-form）设计

> 状态：P0 与第一批数据正确性整改已完成；已实现 Vben 画布、单表契约、独立发布路由及统一权限接入。2026-10-02 预览已接入后端数据引擎，查询和增删改查操作真实业务表，详见[审计与整改记录](dynamic-form-audit-20260930.md)。
> 实施记录：`lambda-fusion-form` 模块 + `lambda-form-changelog.xml` 四表 + 管理端 8 个前端文件 + startup 演示表 `la_demo_leave`；与本文的偏差已在 §4.4、§9、§10、§15 就地标注
> 相关阅读：`.rule/engineering-contract.md`（§3 自动配置、§5 多租户、§6 Liquibase、§9 Entity/Service、§10 包结构、§16 安全、§19 特性开关、§20 最小充分设计）、`.rule/package-structure.md`、`docs/design/workflow.md`（流程集成范式 §10.2）
> 技术基线：Java 21 / Spring Boot 4.1.1 / MyBatis-Plus 3.5.17 / warm-flow 1.8.9 / vben 5.6 + tdesign

## 1. 背景与定位

后台系统 80% 的页面是"左树右表 / 列表 / 查询 / 新增 / 编辑 / 删除"的 CRUD 变体，但当前每个页面都要手写后端四件套（Entity/Mapper/Service/Controller）+ 前端三件套（index.vue/table/form-dialog）——前端 38 个页面重复同一范式，无任何参数化复用。

**定位**：`lambda-fusion-form` 是元数据驱动的 CRUD 能力层——表单元数据落库后，通用运行时引擎直接提供数据读写接口，前端通用页面按元数据渲染；针对既有物理表，业务方零 Java 代码获得带权限、带租户隔离、可挂审批流的完整 CRUD。新表诉求与超出表达力的复杂功能由代码生成毕业路径承接（§2.1）。

与现有模块的关系：

- 复用 `lambda-fusion-core` 全部分页/树/字典/身份能力（契约 §2.1）；
- 租户隔离走 MyBatis-Plus 租户插件 SQL 解析层，动态业务表默认即被纳入（豁免才是配置项），零额外成本（契约 §5.1）；豁免清单中不含 `TENANT_ID` 列的全局表（字典、配置类）同样可作为表单目标，无租户上下文的平台管理/开发者模式整体放行（§9）；
- warm-flow 集成复用 workflow 模块五步范式（`workflow.md` §10.2），并补齐 `la_workflow_form_path` 注册表已建成但消费端未实现的办理页渲染缺口；
- 下游复用单位仍是 starter jar（宪章 §1）：依赖本模块即获得"表单设计器 + 运行时引擎 + 通用页面"。

## 2. 选型与边界

### 2.1 技术路线：元数据引擎 + 毕业路径

| 路线 | 机制 | 评价 |
| --- | --- | --- |
| A. 字节码动态 Bean | 发布时 ByteBuddy 生成 Entity/Mapper/Service/Controller，运行期注册进 Spring 容器 | 原生 Bean 语义，但与框架内部机制多点耦合，否决 |
| B. 元数据通用引擎 | 一套通用 Controller/Service 按 formKey 解释元数据执行 CRUD | 用户可见效果相同，零框架内部耦合 |
| C. 混合（**选定**） | B 为运行时主线 + 同一份元数据驱动代码生成器（毕业路径产出真实源码） | 运行期零风险，"真 Bean"价值以编译期形式获得 |

**为什么否决运行期字节码**：原生 Bean 的价值在于有编译期调用方（import、@Autowired、强类型方法调用），而运行期生成的类没有任何编译期代码能依赖它，唯一消费者是按 formKey 走 HTTP 的通用页面——代价真实、收益空心。代价全部是结构性的：Spring MVC 的 handler 注册/注销只能调非公开 API 并手工清 springdoc 缓存；同类名不可重定义，改字段须版本化 ClassLoader，旧版本驱逐 MP `TableInfo` 缓存才可能回收 Metaspace；ByteBuddy attach 自身进程在容器化/JDK 受限环境是地雷；生成代码绕开 Spotless/SpotBugs/评审，契约 §13 失效；字节码须全节点同步生成；栈轨迹穿透合成类排障困难。参考实现 m1-parent（§18）即该路线的生产先例，上述每一项都在其代码中有对应物（生成类永不卸载、ClassReloadingStrategy 全局重定义、1200 行生成器上帝类）。性能差异是虚的：解释路线同样走 SqlSession + 参数化 SQL，差异只剩一层 Map 投影。字节码的好处由毕业路径以编译期形式完整获得。如未来重启该路线，须先做 Bean 注册/注销 PoC 再单独立项评审。

**毕业路径**：当某表单长出复杂业务逻辑，由同一份元数据生成真实源码（Entity/Mapper/ServiceImpl/Controller + Vue 三件套），走正常 Liquibase/评审/静态检查进入业务模块。仓库当前无代码生成器，本路径同时填补该空白。毕业路径的终点是"生成进真实模块"，不做"导出静态工程"式半成品（m1 的 Freemarker 模板导出功能已烂尾为死代码）。

### 2.2 DDL 边界（契约 §6 合规）

本模块不做运行时建表，契约 §6 无需修订：

- 表单目标表必须是经 Liquibase changelog 治理预建、且在 `allowed-tables` 中显式开放的物理表；租户与审计契约按物理列自动适配（§5.5、§9），发布时校验列存在性与类型兼容；
- 设计器只做"字段映射 + UI/查询/校验配置"，不产生 DDL；
- 新表诉求走毕业路径：元数据 → 生成源码 + changelog → 正常评审部署。

### 2.3 边界（本期不做）

- 不做运行时建表/DDL（§2.2）；
- 不做运行期字节码动态 Bean（§2.1）；
- 首版采用表格化配置；2026-10-01 已接入 Vben 拖拽画布、共享栅格布局与当前设计/草稿/版本预览，见 §10.2；
- 不做动态多表 JOIN 查询（两段式替代，§4.4）；
- 不做导入导出/批量操作（待真实诉求再评估）；
- 不做表单事件脚本与自定义前端按钮（非标逻辑走 §4.7 SPI 或毕业路径）；
- 不做 SQL 片段级高级条件（§4.3 攻击面声明）；
- 不做 EAV/JSON 大宽表存储（失去 DB 类型/索引/约束，报表能力差）；
- 不做表单级数据权限矩阵（复用 authority 资源权限；行级数据权限留给 DataScope 后续扩展，§14）；
- 不改 warm-flow 引擎本体（边界同 workflow.md §2.2）。

## 3. 总体架构

```text
┌────────────────────────── 管理端（vben + tdesign） ──────────────────────────┐
│ 表单设计器 views/form/designer/   通用运行时引擎 views/form/components/         │
│   选表导列/字段/校验/布局设计       + 每表单一个薄包装页（列表/查询/弹窗表单）    │
└──────────────┬───────────────────────────────┬───────────────────────────────┘
               │ 设计 API /v1/form-design/**    │ 数据 API /v1/form/{formKey}/**
┌──────────────▼───────────────────────────────▼───────────────────────────────┐
│                        lambda-fusion-form                                     │
│ ┌───────────────────────┐  ┌───────────────────────────────────────────────┐ │
│ │ design/ 设计子域        │  │ runtime/ 运行子域                              │ │
│ │  草稿 CRUD/发布/版本     │  │  FormModelCompiler  快照→内存表单模型           │ │
│ │  TableMetadataReader   │  │  QueryAstValidator  白名单查询 AST              │ │
│ │  发布校验（列存在性）    │  │  DynamicDataService 单表 CRUD（主从/两段式 P1）  │ │
│ └───────────────────────┘  │  （审计四列在 DynamicDataServiceImpl 内联填充）  │ │
│ ┌───────────────────────┐  └───────────────────────────────────────────────┘ │
│ │ integration/ 集成子域   │  FormDataInterceptor SPI / warm-flow 桥接（P2）    │ │
│ └───────────────────────┘                                                    │
│  缓存：逐次核对 DB 状态/版本，按表单 ID 复用匹配版本的编译结果（§4.2）          │
└──────────────┬───────────────────────────────┬───────────────────────────────┘
               ▼                               ▼
   元数据表 la_form_*（changelog 治理）   业务表 la_*（既有物理表，changelog 治理）
```

## 4. 关键设计

### 4.1 发布快照模型

草稿与发布物分离，是低代码语义的底线（已发布版本持续可用，草稿随便改）：

- `la_form_def` 是**草稿工作行**：设计器就地编辑 def/field/relation 三表，更新必须携带 version，`@Version` 乐观锁防并发覆盖；formKey、formType、tableName 创建后不可修改；`published_version` 指针标识对外生效的快照版本（空 = 从未发布）；
- `la_form_def_version` 是**不可变发布快照**：发布时把 def+fields+relations 序列化为一份全量 JSON 写入，版本号单调递增；快照永不修改；
- **发布**：服务入口校验发布权限 → 读取已保存草稿并生成快照 → MD5 去重 → 编译及目标表校验（§5.5）→ 乐观锁竞争生效指针 → 插入不可变快照；指针和快照在同一事务内提交，失败整笔回滚，并发失败者返回草稿冲突；提交后清除本节点编译缓存；
- **回滚**：校验发布权限，重新编译指定快照并检查当前目标表约束，再将 `published_version` 回指历史版本；提交后失效，无快照增删；
- **停用**：`status` 置 `DISABLED` + 失效，运行时拒绝该表单全部数据请求；
- 运行时只读快照（§4.2），草稿永不对外生效。

版本发布保留上述职责，不隐式创建菜单或授予角色权限。独立“发布路由”负责通用页面挂载和资源管理；设计与版本预览独立于发布和路由挂载。当前处于开发阶段，不实现旧配置、旧快照格式或历史数据迁移兼容。

### 4.2 表单模型编译与缓存

`FormModelCompiler` 把已发布快照 JSON 编译为不可变 `FormModel`：

```text
FormModel = {
  formKey, formName, version, formType, tableName,   // 物理映射（服务端私有）
  fields: [ { name, column, type, nullable, defaultValue, dictType, sort,
              query: { show, op, defaultValue },      // 查询区配置（op 为归一后生效值）
              list:  { show, width, align, format },  // 列表列配置
              form:  { show, component, props, rules, readonly } } ],  // 表单与详情配置
  relations: [ { subFormKey, foreignKey, cascadeStrategy } ],
  tree: { sourceType, parentColumn, nodeColumn, dictType, filterColumn },  // TREE 类型
  treeConfig                                          // 设计端原始 JSON，透传给前端
}
```

字段显示配置按「查询区 / 列表列 / 表单详情」三段归组（设计器三步化的数据模型，§4.9），平铺开关列已收敛为三个 JSON 配置列（§5.2）。

查询/排序白名单不另存副本：`QueryAstValidator` 直接按 `fields` 的 `queryShow`（条件字段）与 `listShow`（排序列）派生，避免白名单与字段定义两份事实（契约 §20.3）。`tree` 的 `dictType` 字段已建模但 P0 编译期只接受 `sourceType=SELF`，其他来源抛 `FORM_TABLE_INVALID`（§4.4）。

- 每次 `FormModelProvider.require` 经框架租户 Mapper 查询当前定义，检查停用、生效指针和目标表允许清单；DB 是状态与生效版本的唯一事实来源；
- 缓存 key = 数据库表单 ID，只有缓存模型版本与本次查得的 `published_version` 相同才命中；同名但不同租户的表单不会共用条目；
- 发布/停用/回滚在事务提交后清除本节点条目。旧请求晚到回填旧版本时，下次请求因版本不匹配重新编译，不会将旧模型作为新版本使用；
- TTL（`lambda.fusion.form.cache-ttl-seconds`）只控制编译结果的复用时间，状态和版本正确性不依赖 TTL 或广播；草稿字段不进入运行模型缓存；
- 模型字段和关系列表、组件属性和规则外层 Map 为只读视图；嵌套配置按只读方式消费。

**当前边界**：节点内采用 `ConcurrentHashMap` + TTL，尚无容量上限或主动清理。各节点逐次查 DB，无需广播才能识别已提交的停用和版本切换；已经取得模型的在途操作不会被强制取消。目标表结构变更仍需正常部署和处理在途请求（§13 R4）。

### 4.3 查询 AST 白名单（安全红线，契约 §16）

客户端只传结构化条件：

```json
{
  "pageNum": 1, "pageSize": 20,
  "sorts": [ { "field": "createdAt", "asc": false } ],
  "conditions": [ { "field": "applicant", "op": "like", "value": "张" } ]
}
```

`field` 一律是**字段标识**（前端 vModel），物理列名只在服务端解析；`between` 的 value 为长度 2 且端点非空的数组（0 有效），`in` 为非空、无 null 元素的数组且逐元素绑定参数，`isNull/isNotNull` 忽略 value。前端为空值操作提供显式选择，数字区间采用双输入组件，IN 支持多选或逗号分隔输入。LIKE 的完整模式值直接参数绑定。

列表、详情和树节点统一投影为 `{id, 字段标识: 值}`，保留 null 键，屏蔽 JDBC 驱动列名大小写差异。默认排序按目标表是否含 `CREATED_AT` 自动适配：存在用 `CREATED_AT DESC, ID DESC`，否则 `ID DESC`。

三层白名单（全部对照 `FormModel` 服务端校验）：

1. `field` 必须在 `queryShow=true` 的字段集内，否则抛 `FORM_QUERY_FIELD_FORBIDDEN`；
2. `op` 必须在枚举白名单（`eq/ne/like/in/between/ge/le/isNull/isNotNull`）内；
3. `sorts[].field` 排序列必须在 `listShow=true` 的字段集内（`PageQuery` 基类只管 `pageNum/pageSize` 形态，成员资格由引擎层校验）。

落地约束：列名/表名**永远**来自服务端 `FormModel`，值全部走参数化绑定（`#{params.*}`）；条件数上限 `lambda.fusion.form.max-condition-count`。树过滤、字典翻译走同一 AST。**不提供 SQL 片段级高级条件**：m1 的规则通道用 `wrapper.apply(原文)` 拼接用户 SQL 片段（且其保存期校验实现有缺陷），是此类系统的最大攻击面，本设计明确不引入。

**标识符白名单（MUST）**：动态 SQL 的表名、列名、主从标识、树配置列无法参数化，只能以 `${}` 形式拼入——它们必须来自服务端元数据，且拼入前一律经 `FormConstants.isIdentifier`（`^[A-Za-z][A-Za-z0-9_]*$`）校验，不通过即拒绝：

1. 草稿保存期：`FormDesignService` 校验表名/列名/子表单标识/外键列（请求 DTO 上另有 `@Pattern` 前置拦截），非法即 `INVALID_PARAMETER`；
2. 快照编译期：`FormModelCompiler` 对快照内全部标识符再校验一次（快照是外部可写数据，运行边界必须自证），非法即 `FORM_TABLE_INVALID`。

因此本模块 `grep` 的判定标准是：`wrapper.apply`/`apply(` 命中为零；`${` 只允许出现在 `DynamicTableMapper` 的标识符占位（表名/列名/WHERE/ORDER 片段），且其调用方已过上述白名单。值一律 `#{params.*}` 参数化；排序方向由布尔解析为字面量 `ASC`/`DESC`、分页 `LIMIT/OFFSET` 由数值拼接，均不接受客户端原文。

### 4.4 多表级联与树过滤

- **写路径**（MASTER_SUB）：同事务先写主表取回 id，回填子表外键批量写；删除按 `la_form_relation.cascade_strategy`——`CASCADE_DELETE` 级联删子表、`FORBID_DELETE` 存在子表数据时抛 `FORM_SUB_DELETE_FORBIDDEN`；
- **读路径**：两段式——主表分页出 id 集 → 子表/关联表 `in` 查回内存组装。**不做**动态多表 JOIN：AST 白名单控制 JOIN 的复杂度收益不抵风险，两段式慢查询可控、租户条件天然分别生效；
- **树过滤**（TREE）：左树选中节点时过滤其全部子孙——按 `tree_config` 数据源递归取回子孙 id 集，对树关联列 `in` 过滤；子孙集上限 `lambda.fusion.form.tree.max-descendants`（m1 用独立关系表维护树闭包，本设计自关联/字典场景直接递归查询，规模由该上限兜底）。

**P1 交付（2026-10-03）**：TREE 左树右表与 MASTER_SUB 主从已全部交付，§4.4 全部条款生效——

- **主从写路径**：同事务先写主表取回 id，再按关系回填子表外键逐行写入（复用子模型校验、子表逻辑删除初始化与审计填充，租户由插件承担）。子行数据嵌套在提交体中（key=subFormKey，值为子行对象数组）；客户端子行 id 一律不被信任。更新采用**替换式**：仅当请求显式携带子表键时整组替换（删除既有活跃子行后重插），未携带表示不触碰子表。
- **主从读路径**：两段式——主表分页出 id 集 → 按外键 `in` 查回子行（子模型投影），挂到主行（key=subFormKey）；`getById` 同语义。子表单不可用（未发布/未映射/表未开放）时读取跳过该关系并置空，写入则直接拒绝。
- **级联删除**：`CASCADE_DELETE` 先按子表语义清理子行（有逻辑删除列则置删除标记，否则物理删除），再删主行；`FORBID_DELETE` 存在活跃子行时抛 `FORM_SUB_DELETE_FORBIDDEN`。子表单须为单表类型且外键已映射（运行时校验），关系不能自引用或重复（编译期校验）。
- **树过滤**（TREE）：左树选中节点时过滤「自身 + 全部子孙」——按 `parentColumn` 逐层递归取回子孙 id 集，对主键 `ID` in 过滤；子孙集上限 `lambda.fusion.form.tree.max-descendants`，超限抛 `FORM_TREE_DESCENDANT_LIMIT`(18123)，环状数据由上限兜底终止；`parentColumn` 引用父行 ID，`filterColumn` 预留给 DICT 等外部树来源（仍仅支持 `SELF`）。

### 4.5 系统字段与逻辑删除

`GlobalMetaObjectHandler` 只覆盖实体路径，通用引擎的 Map 投影不经过它——这是本模块唯一的手工点。写路径在 `DynamicDataServiceImpl#fillAudit` 内联填充 `CREATED_BY/CREATED_AT/UPDATED_BY/UPDATED_AT`（经 `AuthUtils.getSafeUsername()` 取当前身份，无登录上下文时退化为匿名），审计列按目标表物理存在逐列适配：存在则填充，不存在则跳过（§5.5）。

ID、TENANT_ID、审计四列及对外字段名 `id` 不允许作为普通业务字段映射；保存、快照编译和写入归一均校验。租户归属继续由框架插件处理。

配置逻辑删除列时，该列必须映射为 INT/BOOL，统一 0 有效、1 已删除。新增由引擎初始化，普通编辑忽略用户对该列的写入，编辑 Schema 隐藏该字段。分页计数、列表、详情、树、更新、删除和子表引用检查统一限定有效记录；写入检查影响行数，已不存在的记录不能返回成功。

### 4.6 权限模型与权限码约定

- 运行时数据接口：通用端点用 `@FormPermission(FormAction)` 声明操作，`FormPermissionHandler` 实现 Sa-Token 的 `SaAnnotationHandlerInterface`，由 `SaBeanInject` 注册至统一注解鉴权链，从 MVC 已解析的 formKey 得到 `form:{formKey}:{action}`；action 为 `page/add/edit/delete`，不再另注册表单 MVC 鉴权拦截器；
- 设计接口：固定权限码 `form:design:*`，用标准 `@SaCheckPermission` 声明，由 `lambda-cloud-processor` 编译生成 `META-INF/permissions/api-permissions.json`，再经 `LocalPermissionLoader` 进入 `ApiPermissionRegistry`；预览独立使用 `form:design:preview`，详情权限不替代预览权限；
- `design` 为管理权限保留标识，草稿保存、模型编译和运行入口均拒绝业务表单占用，避免业务接口授权扩大为设计管理权；
- 请求由 Cloud `SaTokenInterceptor` 统一处理登录及 `ROLE_DEV` 豁免。预览、路由 Service 不再重复执行接口权限注解对应的裸 `StpUtil` 检查。发布/停用/回滚及菜单适配器的跨能力内部调用经 `AuthUtils.checkPermission` 校验，使用框架活跃登录类型并沿用开发者豁免；保存即发布仍要求发布权，拒绝时回滚同一事务中的草稿变更；
- 动态按钮资源码为 `form-ui:{formKey}:{action}`，与接口权限码分开，避免登录权限合并按钮标识时绕过接口绑定；路由发布通过 `PermissionService.bind` 绑定 `la_api_resources`，授权与撤销复用 RoleService 和提交后缓存刷新；
- `FormApiPermissionProvider` 从当前租户已发布定义和实际 MVC 映射生成七个接口的目录，补充进 `ApiPermissionRegistry` 的本地查询；静态扫描目录仍然保留，不依赖编译期枚举 formKey。当前不把动态租户目录推送到全局静态上报；
- `/schema` 经 `AuthUtils.hasPermission` 返回操作权限，开发者不依赖逐项授权记录；前端统一 `useAccess().hasAccessByCodes` 同样豁免精确的 `ROLE_DEV`。该豁免不改变租户隔离、表允许清单、发布状态或数据校验。普通用户通过菜单与接口绑定、角色授权取得对应权限，挂载不自动授权。

### 4.7 扩展点 SPI（P2）

```java
public interface FormDataInterceptor {
    String getName();                                   // 设计器下拉展示
    default void beforeCreate(FormModel model, Map<String, Object> data) {}
    default void afterCreate(FormModel model, Object id, Map<String, Object> data) {}
    default void beforeUpdate(FormModel model, Object id, Map<String, Object> data) {}
    default void afterUpdate(FormModel model, Object id, Map<String, Object> data) {}
    default void beforeDelete(FormModel model, Object id) {}
    default void afterDelete(FormModel model, Object id) {}
}
```

下游以 Spring bean 提供实现，`la_form_def` 配置拦截器 bean 名；`ObjectProvider` 空安全包装，未配置即空操作。设计器经 `GET /v1/form-design/interceptors` 列出可用拦截器（bean 名 + 描述）供下拉。非标逻辑（联动校验、写后通知）由此承接；超出表达力的走毕业路径（§2.1）。

### 4.8 集群部署

- 元数据为 DB 单一事实来源（契约 §20.3），各节点只缓存已发布快照（§4.2）；
- 发布/回滚/停用提交后，各节点下一次运行模型读取均核对 DB 状态与版本；本节点提交后清缓存用于释放编译结果，正确性不依赖跨节点广播；
- 目标表结构演进走正常部署（§2.2）；目标表字段下架须先停用引用它的表单。

### 4.9 设计器三步化与字段配置块（P1，2026-09-29 实施）

设计器从"单页字段大表（平铺开关列）"演进为**三步向导**，每步只面对一类决策：

| 步骤 | 内容 | 写哪段配置 |
| --- | --- | --- |
| ① 基础与数据 | 表单信息、选表导列（标识/列名/类型/可空/默认值/字典）、主从关系、树配置 | 字段数据层列 |
| ② 表单与详情 | 组件选择、组件属性 JSON、校验规则（required/max/min/pattern/message）、字段级只读 | `FORM_CONFIG` |
| ③ 列表设计 | 查询条件（开关/操作符/默认值）、表格列（开关/列宽/对齐/格式化） | `QUERY_CONFIG` / `LIST_CONFIG` |

数据模型：字段显示配置归组为三个 JSON 配置块（§4.2 / §5.2），运行时 `/schema` 展平下发（§8.3）。`readonly=true` 的字段在**表单中禁用**（详情页天然只读，合一设计不单独维护详情视图）。

校验两道：草稿保存期 `FieldConfigs` 解析 JSON 文本做**键白名单 + 值域**校验（拒绝未知键，防拼写静默失效）；快照编译期 `FormModelCompiler` 对照快照对象再校验（防篡改）。`component` 只校验标识符白名单不锁枚举（前端组件集演进不受后端版本钳制）。

## 5. 元数据表结构（changelog 治理）

四张表走 `lambda-form-changelog.xml`（契约 §6.1），changeSet 带 `preConditions onFail="MARK_RAN"`；列名大写（同 `la_workflow_form_path` 惯例），均含 `TENANT_ID varchar(64)` 与审计四列（`CREATED_BY varchar(32)` / `CREATED_AT datetime` / `UPDATED_BY varchar(32)` / `UPDATED_AT datetime`）。

### 5.1 `la_form_def` 表单定义（草稿工作行）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `ID` | varchar(32) PK | 主键 |
| `FORM_KEY` | varchar(64) NN | 表单标识，URL 与权限码的一部分 |
| `ROUTE_RESOURCE_ID` | varchar(32) nullable UK | 受此表单管理的菜单资源 ID；路由配置以 authority 资源为准，不进入发布快照 |
| `FORM_NAME` | varchar(128) NN | 显示名 |
| `FORM_TYPE` | varchar(16) NN | `SINGLE` / `TREE` / `MASTER_SUB` |
| `TABLE_NAME` | varchar(64) NN | 目标物理表名（§5.5 约束） |
| `TREE_CONFIG` | text | TREE 类型：树数据源 JSON（P0 编译期只接受 `sourceType=SELF` 自关联；字典 / 另一表单未开放） |
| `FLOW_CODE` | varchar(64) | 挂接的 warm-flow 流程编码（P0 随草稿保存，P2 消费） |
| `STATUS` | varchar(16) NN | `DRAFT` / `PUBLISHED` / `DISABLED` |
| `PUBLISHED_VERSION` | bigint | 对外生效的快照版本号（空 = 从未发布） |
| `TITLE_TEMPLATE` | varchar(256) | 流程标题模板（如 `{applicant}的{form_name}`，P0 随草稿保存，P2 消费） |
| `INTERCEPTOR` | varchar(128) | `FormDataInterceptor` bean 名（P0 随草稿保存，P2 消费，可空） |
| `VERSION` | bigint NN | `@Version` 乐观锁 |

唯一键 `uk_tenant_form_key (TENANT_ID, FORM_KEY)`。

`FLOW_CODE` / `TITLE_TEMPLATE` / `INTERCEPTOR` 三列属草稿文档的可选属性：P0 由设计 API 原样保存、随发布快照落库（不丢配置），P2 才被 warm-flow 桥接与 SPI 消费；设计器暂未提供这三个输入项，前端亦不展示。

### 5.2 `la_form_field` 字段定义

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `ID` | varchar(32) PK | 主键 |
| `FORM_ID` | varchar(32) NN | 归属表单 |
| `FIELD_NAME` | varchar(64) NN | 字段标识（前端 vModel） |
| `COLUMN_NAME` | varchar(64) NN | 物理列映射 |
| `FIELD_TYPE` | varchar(16) NN | `STRING/TEXT/INT/DECIMAL/DATE/DATETIME/DICT/BOOL/FILE` |
| `NULLABLE` | tinyint NN | 可空 |
| `DEFAULT_VALUE` | varchar(512) | 默认值 |
| `DICT_TYPE` | varchar(64) | DICT 类型的字典编码（lambda-fusion-dictionary） |
| `QUERY_CONFIG` | json | 查询区配置 `{show, op, defaultValue}` |
| `LIST_CONFIG` | json | 列表列配置 `{show, width, align, format}` |
| `FORM_CONFIG` | json | 表单与详情配置 `{show, component, props, rules, readonly}` |
| `SORT` | int NN | 字段顺序 |

唯一键 `uk_form_field_name (FORM_ID, FIELD_NAME)`。`FILE` 类型值 = 附件三元组 JSON 数组文本 `[{"id","name","url"}]`（文本列存储；携带名称与地址避免渲染时逐个回查附件，`parseFileValue` 兼容旧纯链接文本）。

### 5.3 `la_form_relation` 主从关系（MASTER_SUB）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `ID` | varchar(32) PK | 主键 |
| `FORM_ID` | varchar(32) NN | 主表单 |
| `SUB_FORM_KEY` | varchar(64) NN | 子表单 formKey（同租户内解析） |
| `FOREIGN_KEY` | varchar(64) NN | 子表外键列 |
| `CASCADE_STRATEGY` | varchar(16) NN | `CASCADE_DELETE` / `FORBID_DELETE` |
| `SUB_TABLE_CONFIG` | json | 子表列表呈现配置 `{columns:[{field,width}]}`，field 为子表单字段标识（未配置=全量可见字段） |

设计端保存期前置校验（2026-10-03）：草稿保存即拦截运行时 `requireSubModel` 才能发现的错误——子表单须存在（同租户）且为 `SINGLE` 类型、外键列须为子表单已映射且落库的字段，另拒绝自引用与重复 `SUB_FORM_KEY`；设计器关系配置为选择式（选子表单 → 自动加载其字段 → 外键下拉），不再手打 formKey。`SUB_TABLE_CONFIG` 经 `SubTableConfigs` 键白名单+值域校验（保存期对照子表单字段、编译期防篡改再拦一道），设计器子表区块拖拽定列（2026-10-03）。

### 5.4 `la_form_def_version` 发布快照

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `ID` | varchar(32) PK | 主键 |
| `FORM_ID` | varchar(32) NN | 归属表单 |
| `VERSION` | bigint NN | 版本号（表单内单调递增） |
| `SNAPSHOT_JSON` | text NN | def + fields + relations 全量快照，**不可变** |
| `CONTENT_MD5` | varchar(32) NN | 内容指纹，发布去重 |
| `PUBLISHED_BY` | varchar(64) NN | 发布人 |
| `PUBLISHED_AT` | datetime NN | 发布时间 |

唯一键 `uk_form_def_version (FORM_ID, VERSION)`。

### 5.5 目标物理表约束

`la_form_def.table_name` 必须指向 Liquibase 治理下已存在、支持事务的专用业务表。当前运行 SQL 以 MySQL 为支持范围，H2 MySQL 模式用于自动化测试；未声明其它数据库方言可用。发布、回滚、启用及写入统一经 `TargetTableContract` 重新读取并检查物理契约：

1. 表存在、名称满足标识符规则、在 `lambda.fusion.form.allowed-tables` 中精确匹配且未命中 `table-exclude-pattern`；模块默认允许清单为空，startup 仅开放 `la_demo_leave`；
2. 元数据限定当前连接的 catalog/schema，转义 JDBC 模式字符并复核返回的表名；真实主键必须为单列 `ID`，不可自增或生成。ID 至少容纳 19 位雪花值，支持长度至少 19 的字符列、BIGINT、DECIMAL(p≥19,0)；查询结果中的 `id` 始终为字符串。
3. 每个映射都必须有真实物理列。STRING/TEXT/FILE 仅映射文本列，JSON/ENUM/SET 不作为普通文本开放；DICT 支持文本或整数列；INT 支持整数或精确小数列，DECIMAL 只支持 DECIMAL/NUMERIC，不映射整数或近似浮点列；BOOL 支持布尔、单 bit 或 TINYINT；DATE/DATETIME 匹配日期/日期时间列，不将 YEAR 当作 DATE。写入还检查文本容量、整数范围、小数精度与小数秒精度，不截断或四舍五入。
4. 引擎写入列不可自增或生成；未赋值的物理 NOT NULL 列必须有数据库默认值或由数据库生成。可清空字段不可映射 NOT NULL 列。无法输入的必填字段（隐藏或控件只读）必须有表单默认值或数据库默认值。`persisted=false` 表示读取现有列但不写入，支持数据库生成列，尚不提供脱离物理列的计算字段；导列自动将自增/生成列设为不提交、只读。
5. 租户与审计契约按物理元数据与租户配置自动适配，不检查插件装配（装配一致性由框架自动配置保证）。租户：复用 `mybatis-plus.tenant.enabled` 与 `ignore-tables` 配置——请求带租户上下文时，未豁免的表必须有可写的字符串 `TENANT_ID` 列（至少 32 字符）且当前租户值可落库；表被豁免却含 `TENANT_ID` 列属配置矛盾，拒绝动态读写；豁免且无该列的全局表直接放行；请求无租户上下文即平台管理/开发者模式（契约 §5.1 受控流程，登录与权限入口仍生效），整体放行且不代填租户列，租户列的数据库约束自行表态；全局关闭时按单租户模式处理，不要求租户上下文或 `TENANT_ID` 列。审计列按物理存在逐列适配：存在则校验类型（操作人列至少 32 字符、时间列为 DATETIME/TIMESTAMP）并由引擎填充，不存在则跳过要求与填充。物理表 NOT NULL/default 等约束仍执行。

表单默认值保持文本存储协议，但编译时即做类型和规则检查，发布时再检查列容量；新增转换为布尔、整数、BigDecimal 或日期时间后绑定。未提交且无表单默认值的字段可交给数据库默认值，SQL 表达式只由数据库执行；映射字段的实际默认值在新增事务内回读校验，违反规则则回滚。显式 null 不触发任何默认值；更新未提交字段保持原值。

`FormFieldValidator` 在服务端执行 `required/min/max/pattern`，并拒绝未知规则、无效正则、错误规则类型与倒置范围。min/max 对文本表示 UTF-16 长度（与前端一致），对数字表示数值范围；物理列容量另按 JDBC 元数据检查。日期采用严格 ISO 解析，DATETIME 也接受空格分隔的日期时间。正则由 Java Pattern 执行，设计器应使用前后端共通语法。可空文本可显式清空，必填文本拒绝全空白。

物理元数据不复制到快照或长期缓存。数据库默认表达式、唯一键、外键、CHECK、触发器、字符集及数据库时间范围仍以目标数据库为准；本契约不替代专用业务服务、字典成员校验或文件引用协议。`readonly/formShow/listShow` 属于展示配置，不是字段级权限。挂流程表的流程列仍随 P2 一并接入校验（§11）。

毕业路径为新表单建表时，生成表沿用 `la_df_` 前缀约定，经正常 changelog 与评审落地。

## 6. 包结构

遵循「模块根级共享 + 子域内分层」（契约 §10，`.rule/package-structure.md`），基础包 `com.lambda.fusion.form`：

```text
com.lambda.fusion.form
├── FormConstants                       标识符白名单、权限码模板、表单/字段/算子白名单
├── exception/
│   ├── FormBusinessException           模块级业务异常
│   └── FormErrorCode                   错误码枚举（18xxx 段，§12）
├── design/                             设计子域
│   ├── controller/FormDesignController 草稿 CRUD/选表导列/发布/停用/回滚/版本历史
│   ├── service/{FormDesignService, FormPublishService} + impl/
│   ├── support/TableMetadataReader     information_schema 只读 introspection（选表导列与发布校验共用）
│   ├── entity/{FormDefEntity, FormFieldEntity, FormRelationEntity, FormDefVersionEntity}
│   ├── mapper/{FormDefMapper, FormFieldMapper, FormRelationMapper, FormDefVersionMapper}
│   └── model/                          FormDefPage/FormDefView/FormDefSaveCommand/FormFieldItem/FormRelationItem/TableColumnView/FormVersionView
├── runtime/                            运行子域
│   ├── controller/FormDataController   /v1/form/{formKey}/**
│   ├── service/{FormRuntimeService, DynamicDataService} + impl/
│   ├── mapper/DynamicTableMapper       动态表读写（表名/列名占位 + 值参数化）
│   ├── support/                        FormDefSnapshot/FormModel/FormModelCompiler/FormModelCache/FormModelProvider/QueryAstValidator/SnapshotJson
│   └── model/                          AstCondition/FormDataPageQuery/FormSchemaView
└── integration/                        集成子域（SPI 与外部桥接，P2）
    ├── FormDataInterceptor             数据钩子 SPI（§4.7）
    ├── FormBusinessProvider            WorkflowBusinessProvider 实现（§11）
    ├── FormFlowStatusUpdater           @EventListener 消费 WorkflowFlowEvent 回写 flow_status（§11）
    └── FormMetadataInvalidator         Dubbo broadcast 失效监听（§4.2）

com.lambda.fusion.autoconfig
└── FormAutoConfiguration               @AutoConfiguration 入口，注册 AutoConfiguration.imports（末尾无空行）
com.lambda.fusion.form
├── FormConfigure                       @Configuration：@EnableConfigurationProperties + @ComponentScan + @MapperScan
└── FormProperties                      @ConfigurationProperties(prefix = "lambda.fusion.form")
```

要点：子域内 `model/` 只放 VO/Query/Command（契约 §9.3）；VO 一律 `extends BaseVO<E>` + `@AutoConverter`（契约 §8.1），`FormDesignServiceImpl extends AbstractCrudService<FormDefEntity, FormDefView, FormDefMapper>` 走 def 表的标准 CRUD（契约 §9.2）；`FormDataPageQuery extends PageQuery<Map<String,Object>>` 复用分页入参形态与 `PageView` 出参（契约 §8.2），其 `getLambdaQueryWrapper()` 对动态查询不适用（抛 `UnsupportedOperationException`），分页经 `COUNT` + `LIMIT/OFFSET` 由引擎直接下推；运行时数据读写不经实体栈。表名保留数据库原始大小写（Linux MySQL 表名区分大小写，与 `${tableName}` 原样 SQL 对齐）；字段列名统一大写（MySQL 列名大小写不敏感）。

## 7. 自动配置与依赖

### 7.1 三件套（契约 §3.1）

- `FormAutoConfiguration`（`com.lambda.fusion.autoconfig`）注册进 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，并以 `@ConditionalOnProperty(prefix = "lambda.fusion.form", name = "enabled", matchIfMissing = true)` 承载模块总开关（契约 §19.1，同 logger/message 模块）；
- `FormConfigure`：`@EnableConfigurationProperties(FormProperties.class)` + `@ComponentScan` + `@MapperScan("com.lambda.fusion.form.**.mapper")`；P0 无跨模块可选 Bean（Dubbo 依赖为 `optional` 且尚未使用），P2 的广播/SPI 装配在此加 `@ConditionalOnClass` 保护（契约 §4.1）；
- `FormProperties`：前缀 `lambda.fusion.form`，契约 §3.2 前缀表登记（规则文件独立提交，契约 §18.2）。

### 7.2 pom 依赖（P0 实施）

| 依赖 | 必要性 | 用途 |
| --- | --- | --- |
| lambda-fusion-core | 必需 | 分页/树/字典/身份/`AuthUtils`；web 底座（Controller/统一返回/异常体系）经其传递 |
| lambda-cloud-starter-mybatis | 必需 | MP 3.5.17 + 租户插件（§5.1 隔离机制） |
| tools.jackson.core:jackson-databind | 必需 | 快照 JSON 序列化（Jackson 3，版本经 BOM） |
| lambda-cloud-starter-liquibase | 必需 | `lambda-form-changelog.xml` 聚合执行 |
| lambda-cloud-starter-logger | 必需 | 设计子域写操作留痕（`@OperationLog`） |
| lambda-cloud-starter-dubbo | 可选（现有声明，未使用） | 不承担当前状态/版本正确性；缓存核对见 §4.2 |
| lambda-fusion-oss | 可选（P1 引入） | FILE 字段上传：`FormUploadController` 复用 `AttachmentService` 附件体系（§9 上传协议）；缺失时端点不装配，字段退化为文本输入 |
| com.h2database:h2 | 仅 test | 真实 Mapper、租户插件和事务集成测试；版本沿用 BOM，不改变运行数据库 |

P1 引入 oss 可选依赖：上传端点权限为设计预览或目标表单新增/编辑（any-of），附件按 `form-{formKey}` 分组归置；workflow / dictionary 仍不引入（warm-flow 桥接在 P2、字典翻译由前端 `useDict` 覆盖）。

无硬编码版本（契约 §1.2），经 BOM 收敛。

### 7.3 Liquibase

`META-INF/db/changelogs/lambda-form-changelog.xml` 建 §5 四张元数据表；目标业务表不在本模块 changelog 内（§2.2）。

## 8. API 定稿

### 8.1 设计 API（管理端，权限码 `form:design:*`）

| 方法与路径 | 权限码 | 说明 |
| --- | --- | --- |
| `GET /v1/form-design/page` | `form:design:page` | 表单分页（草稿行 + 生效版本号） |
| `POST /v1/form-design/routes` | `form:design:page` | 当前页表单的实际挂载状态，按租户验证 ID 归属 |
| `GET /v1/form-design/{id}/route` | `form:design:route` | 挂载能力与现有配置 |
| `PUT /v1/form-design/{id}/route` | `form:design:route` + 既有资源管理权限 | 创建或更新菜单和四项接口权限绑定，不改发布版本、不自动授权角色 |
| `DELETE /v1/form-design/{id}/route` | `form:design:route` + 既有资源删除/解绑权限 | 撤销本表单拥有的入口；保留草稿、历史和当前生效版本 |
| `GET /v1/form-design/{id}` | `form:design:detail` | 草稿详情（def + fields + relations） |
| `POST /v1/form-design` | `form:design:add` | 新建草稿 |
| `PUT /v1/form-design/{id}` | `form:design:edit` | 保存草稿（`@Version` 乐观锁） |
| `DELETE /v1/form-design/{id}` | `form:design:delete` | 删除（已发布须先停用） |
| `GET /v1/form-design/tables` | `form:design:table` | 可映射物理表列表（排除模式过滤后） |
| `GET /v1/form-design/tables/{table}/columns` | `form:design:table` | 列元数据 → 一键生成字段草稿 |
| `POST /v1/form-design/{id}/publish` | `form:design:publish` | 发布（校验 → 快照 → 拨指针，§4.1） |
| `POST /v1/form-design/{id}/disable` | `form:design:publish` | 停用 |
| `POST /v1/form-design/{id}/rollback/{version}` | `form:design:publish` | 回滚到历史快照 |
| `GET /v1/form-design/{id}/versions` | `form:design:detail` | 版本历史列表 |
| `GET /v1/form-design/interceptors` | `form:design:detail` | 可用 `FormDataInterceptor` 列表（P2） |

### 8.2 运行时数据 API（登录 + 权限码 `form:{formKey}:*`）

| 方法与路径 | 权限码 | 说明 |
| --- | --- | --- |
| `GET /v1/form/{formKey}/schema` | `form:{formKey}:page` | 渲染元数据（§8.3），含已解析按钮权限集 |
| `POST /v1/form/{formKey}/page` | `form:{formKey}:page` | 分页查询（AST 条件，§4.3） |
| `GET /v1/form/{formKey}/tree` | `form:{formKey}:page` | TREE 树数据：按 `parentValue` 取直接子节点（P0 仅自关联树，子孙过滤见 §4.4） |
| `GET /v1/form/{formKey}/{id}` | `form:{formKey}:page` | 详情（主从含子表为 P1） |
| `POST /v1/form/{formKey}` | `form:{formKey}:add` | 新增（主从事务写入为 P1） |
| `PUT /v1/form/{formKey}/{id}` | `form:{formKey}:edit` | 部分更新：未提交字段保持原值，显式 null 清空可空字段；无可写项拒绝 |
| `DELETE /v1/form/{formKey}/{id}` | `form:{formKey}:delete` | 删除（按级联策略，P0 一律拒绝有子表数据的记录） |

Controller 只编排（契约 §9.3），权限在 Cloud 统一注解鉴权链中检查；出参分页统一 `PageView`（契约 §8.2），行形态为按元数据裁剪的 `Map` 投影；表单不存在/未发布/已停用分别抛 `FORM_NOT_FOUND`/`FORM_NOT_PUBLISHED`/`FORM_DISABLED`。

### 8.3 渲染元数据契约（前端消费）

`/schema` 返回 `FormSchemaView`——只含渲染所需信息，**剥离物理映射**（column/table 不出服务端）：

```json
{
  "formKey": "leave_demo", "formName": "请假演示", "formType": "TREE", "version": 3,
  "fields": [
    { "name": "reason", "label": "事由", "type": "STRING", "nullable": false, "sort": 1,
      "queryShow": true, "queryOp": "like", "queryDefaultValue": null,
      "listShow": true, "width": 200, "align": "left", "format": null,
      "formShow": true, "component": "Input", "props": { "placeholder": "请输入" },
      "rules": { "required": true, "max": 200 }, "readonly": false }
  ],
  "treeConfig": "{\"sourceType\":\"SELF\",\"parentColumn\":\"PARENT_ID\",\"nodeColumn\":\"NAME\"}",
  "permissions": { "add": true, "edit": true, "delete": false }
}
```

三段配置在快照内是**结构化对象**（服务端已做键白名单与值域校验：op ∈ 操作符白名单、width 1-500、align ∈ left/center/right、format ∈ date/datetime/dict、component 走标识符白名单），前端直接使用不再 `JSON.parse`；`treeConfig` 仍为设计端原样存储的 JSON 字符串。`label` 保存于 `la_form_field.FIELD_LABEL`，导列默认使用列注释，缺省时取字段标识。`formLayout` 在草稿中以 JSON 文本保存，在快照与 Schema 中是 `{ columns, items: [{ field, span }] }`，列数为 1～4，字段引用唯一且必须存在，占列不超过全局列数。

`permissions` 由服务端按当前用户逐 action 解析（`AuthUtils.hasPermission`，action ∈ `page/add/edit/delete`），与框架开发者豁免保持一致；`page` 同时是访问 Schema 的前提，在统一注解鉴权链中检查。前端按布尔值渲染运行操作，不用路由管理权限替代数据接口权限。

设计预览使用独立的 `form:design:preview` 权限点，元数据与数据端点均由编译期处理器收集并进入现有 permission-api 目录。数据操作在服务端解析真实 formKey 后，继续经 `AuthUtils` 校验 `form:{formKey}:page/add/edit/delete`；提交未保存设计还需 `form:design:add/edit`。开发者沿用统一 `ROLE_DEV` 豁免，租户过滤、状态及数据校验仍执行。仅有预览权限可查看配置，不能读写业务数据。

预览打开完整运行页面，查询、分页、排序、新增、编辑、删除和详情通过后端读写目标业务表，关闭后数据仍保留。预览不要求已发布版本或已挂载路由，也不自动保存设计或发布版本。历史预览使用指定版本的字段模型访问当前业务数据，不代表历史数据快照。

每次数据请求明确携带 `FormPreviewSource`，不依赖服务端会话或前端模拟数据：

| 来源 | source 内容 | 后端约束 |
| --- | --- | --- |
| 已保存草稿 | `{formId, draftVersion}` | 由当前租户 Mapper 读取草稿；修订号必须匹配元数据预览返回的 `draftVersion` |
| 历史版本 | `{formId, version}` | 先校验当前租户表单归属，再读取指定不可变快照 |
| 当前设计 | `{formId?, design}` | 新表单需新增设计权且 formKey 未被占用；已有表单需编辑设计权、匹配 design.version，且不可改变表单标识、目标表和类型 |

数据端点统一位于 `/v1/form-design/preview/data`：

| 方法与路径 | 请求体 | 返回 |
| --- | --- | --- |
| `POST /page` | `{source, query}`，query 复用 `FormDataPageQuery` | `PageView`，records 的键为字段标识 |
| `POST /{recordId}/detail` | `source` | 单条记录 |
| `POST` | `{source, data}` | 新增记录 ID |
| `PUT /{recordId}` | `{source, data}` | 保存结果 |
| `DELETE /{recordId}` | `source` | 删除结果 |

`POST /v1/form-design/preview` 的元数据请求也使用 source 包装。预览复用 `DynamicDataService`，每次操作检查目标表允许清单、物理契约及实际租户插件，写入复用字段规则、事务、默认值与删除语义。配置已变更时返回草稿冲突，须重新打开预览；已停用表单不允许预览数据读写。

## 9. 配置项定稿

| key | 默认 | 说明 |
| --- | --- | --- |
| `lambda.fusion.form.enabled` | `true` | 总开关（契约 §19） |
| `lambda.fusion.form.cache-ttl-seconds` | `300` | 快照编译结果 TTL；状态和生效版本每次查 DB（§4.2） |
| `lambda.fusion.form.allowed-tables` | `[]` | 允许动态读写的物理表名，精确匹配、默认拒绝；startup 仅配置 `la_demo_leave` |
| `lambda.fusion.form.max-condition-count` | `20` | AST 条件数上限（§4.3） |
| `lambda.fusion.form.tree.max-descendants` | `500` | 树子孙 id 集上限（已生效：TREE 列表子孙过滤 §4.4） |
| `lambda.fusion.form.table-exclude-pattern` | `la_form_.*\|databasechangelog.*\|qrtz_.*` | 对允许清单追加的排除规则，大小写不敏感；设计、发布和运行共同检查（§5.5） |

租户开关沿用基础框架配置，不新增表单私有开关：

```yaml
mybatis-plus:
  tenant:
    enabled: false # 单租户运行；开启则必须有当前租户上下文
```

startup 默认开启，可设置环境变量 `MYBATIS_PLUS_TENANT_ENABLED=false` 后重启，关闭整个应用的租户隔离。多租户开启时，请求通过现有 `X-Tenant-Id` 或域名解析获取租户。单租户模式下，动态接口权限目录也允许无租户上下文加载。MyBatis-Plus 的 `InterceptorIgnoreHelper` 适合受控代码段临时忽略插件，本实现不使用它绕过已开启模式下缺失的租户上下文。

目标表的租户契约区分两种模式。请求无租户上下文即平台管理/开发者模式（契约 §5.1 的受控平台流程，登录与权限入口仍生效）：框架策略本就全表跳过租户注入，引擎不代填租户列、不设防，租户列的数据库约束自行表态，开发者据此在设计器与预览中联调任意允许清单内的表。请求带租户上下文时按业务契约校验：注入生效的表必须有可写的 `TENANT_ID` 列（≥32 字符文本，当前租户值须能落库）；表在 `mybatis-plus.tenant.ignore-tables` 豁免清单中时，含 `TENANT_ID` 列等于对全租户关掉隔离，一律拒绝动态读写，不含该列的全局表直接放行。契约只读取租户配置与物理元数据，插件装配由框架自动配置负责；设计、发布、预览与运行数据路径共用该契约，发布/回滚/启用校验因此不依赖请求租户上下文。

**文件上传协议（P1，2026-10-03）**：`POST /v1/form/upload`（multipart，复用 `lambda-fusion-oss` 附件体系）——权限为设计预览或目标表单新增/编辑（any-of，编程式校验，沿用统一开发者豁免）；附件按 `form-{formKey}`（设计端 `form-design`）分组归置，返回 `{id, name, url}`。FILE 字段值由前端组装为三元组 JSON 数组存入文本列（`parseFileValue` 兼容旧纯链接文本），引擎按文本容量校验、不感知文件协议细节；oss 模块缺失时端点不装配，FILE 退化为文本输入。子表（MASTER_SUB）内的 FILE 字段暂以文本输入呈现，上传组件仅覆盖主表单（登记 §15）。

## 10. 前端设计

### 10.1 通用运行时页（P0 实施结构）

```text
packages/system-ui/src/
├── api/form/index.ts                    设计 API + 运行时 API + 类型（唯一后端出入口）
└── views/form/
    ├── form-field-meta.ts               字段类型/状态/算子标签、查询与表单 schema 构建、展示格式化
    ├── components/form-runtime.vue      通用运行时引擎：查询栏 + 列表 + 操作列 + 弹窗/抽屉装配
    ├── components/form-data-dialog.vue  新增/编辑弹窗（按 formShow 字段生成表单）
    ├── components/form-data-detail.vue  详情抽屉（只读，按 formShow 字段渲染）
    ├── components/form-layout-renderer.vue 新增/编辑/详情与预览共用的布局渲染器
    ├── form-layout.ts                   画布、表单、详情共用的字段顺序与占列
    ├── runtime/index.vue                通用页面：显式 meta.formKey；切换表单重建运行组件
    ├── runtime/leave_demo/index.vue     原有演示包装页
    └── designer/                       三步设计器、form-layout-designer.vue 画布、form-preview-dialog.vue 预览
```

引擎组件按 `formKey` 拉 `/schema` → 生成查询栏 schema（`buildSearchSchema`）与 `VxeTableGridOptions.columns`（`buildColumns`）→ 复用 `useVbenForm` / `useVbenVxeGrid`（`adapter/` 已全局封装，组件名经 `adapter/component.ts` 解析）；字典列经 `useDict` 翻译，日期/布尔经 `displayText` 格式化；操作列按 `permissions` 布尔渲染。

**通用挂载**：菜单 `RES_PATH` 只承担访问路径；`EXPAND` 中明确声明 `{ "component": "/form/runtime/index", "meta": { "formKey": "leave", "formId": "..." } }`。`MenuRouteAssembler` 读取组件与参数，以资源 ID 生成稳定路由名称；显示名仍在 meta.title。运行页按当前租户的 formKey 读取生效快照，切换 formKey 会重建 Schema、查询和编辑状态；“刷新表单”重新读取版本与布局。`BUSINESS_EXPAND` 保存归属标记，供挂载管理验证，不靠路径或名称猜测归属。

TREE 类型运行页为左树右表布局（2026-10-03 交付）：左侧数据树按 `tree` 接口懒加载（节点展示列取 `tree.labelField` 字段），选中节点过滤「自身 + 全部子孙」，可一键清除回到全量。MASTER_SUB 主从同步交付：编辑弹窗按关系渲染可编辑子表（子表单 schema 由前端按 `subFormKey` 拉取），详情抽屉只读展示子行；列表页主行与详情接口均携带嵌套子行数组。

### 10.2 表单设计器

- 起点是"选表导列"：`/tables` 选目标物理表 → `/tables/{table}/columns` 读列元数据一键生成字段草稿（`fieldName` 由列名转小驼峰、`columnName`/`fieldType` 自动带入），契约列（`ID`/`TENANT_ID`/审计四列）自动跳过，已存在的列不重复导入；
- 基础页编辑字段标识、显示名、列映射、类型、可空、默认值、字典、落库与排序；列表页编辑查询与实际支持的列配置、按钮文案，预览中的列表共用运行页；
- 表单与详情页是较大的三栏工作区：字段区拖入/点击添加，中央显示真实 Vben 控件并支持拖动排序/上下移动/移除，右侧配置标签、组件、提示、默认值、只读、必填及类型对应规则，JSON 仅作为高级属性入口；
- 表单与详情共享 1～4 列栅格与逐字段占列，默认两列且每字段占一列。`FORM_LAYOUT` 从草稿保存、不可变快照到运行 Schema 全程使用同一结构；改名同步引用、删除映射清理引用、缩小列数收窄占列；
- 当前设计、草稿列表、历史版本分别调用 `POST /v1/form-design/preview`、`GET /{id}/preview`、`GET /{id}/versions/{version}/preview`。配置经正式编译和统一 Schema 组装；全屏运行页携带该来源调用预览数据端点，按后端权限与按钮配置提供新增、编辑、删除和详情；
- 预览与正式页面共用 `FormRuntime`、编辑弹窗、详情抽屉及 Vben 字段校验；原前端 `FormPreviewData` 模拟引擎已移除。查询、排序和分页由数据库执行；保存成功才关闭弹窗并重新查询，末页删除后回到有效页。刷新和关闭重开都读取已持久化数据，页面明确提示保存和删除真实生效；
- 当前设计使用深拷贝固定本次预览模型，不自动保存草稿；异步校验后核对提交上下文，关闭或切换来源时丢弃尚未发出的提交和过期回填。已经发出的成功数据写入不会因关闭窗口撤销。文件字段上传协议已交付（§9）：FILE 字段经受控端点上传至 OSS 附件体系，值为三元组 JSON 数组；
- 设计器提供保存草稿与保存并发布；自动发布仍由服务端在保存事务内校验发布权限。显式保存并发布不改变长期的自动发布配置，发布失败时准确提示草稿已保存；
- 列表页分别展示版本与实际挂载状态，提供发布/停用/启用/回滚/版本历史、发布或管理路由、撤销入口、进入页面及独立角色授权；启用原生效快照不新增版本；
- 新建开放 `SINGLE` / `TREE` / `MASTER_SUB` 类型与 `NORMAL` 列表布局（树与主从已于 2026-10-03 交付）；文件/高级查询/导入导出等未兑现选择仍屏蔽；按钮动作去重。完整能力边界与验证见 [审计报告](dynamic-form-audit-20260930.md) 的第二阶段批次。

### 10.3 菜单、接口权限与角色授权

菜单/按钮权限仍是 authority 资源，走 changelog 预置（与既有静态页同流程）。P0 已登记：

| changeSet | 资源 | 说明 |
| --- | --- | --- |
| `lambda-authority-2026092800010/11` | `FmTop00001` + `FmDesign001` | 顶层"动态表单" + "表单设计"页（`/form/designer/` → `views/form/designer/index.vue`），按钮 `form:design:page/detail/add/edit/delete/table/publish`，授予 `ROLE_ADMIN`/`ROLE_SYSTEM`/`ROLE_DEV` |
| `lambda-fusion-startup-202609280002/03` | `FmDemo00001` | 演示表单"请假动态表单"（`/form/runtime/leave_demo/` → `views/form/runtime/leave_demo/index.vue`），按钮 `form:leave_demo:page/add/edit/delete` |
| `lambda-authority-2026093000012` | `FmDsgV001` | 新增 `form:design:preview` 设计预览权限资源；通过现有角色管理明确授权，不自动扩大角色权限 |
| `lambda-authority-202610010001` | `FmDsgR001` | 新增 `form:design:route` 路由管理资源，仍须同时具备既有资源 CRUD、绑定/解绑及必要的移动权限；不自动授权角色 |

独立“发布路由”通过 `FormRouteGateway` 的本地 authority 实现创建或更新一个菜单与四个操作资源，并绑定对应接口权限。首次挂载要求已有可用发布版本；表单行锁保证重复操作复用同一绑定，资源树固定锁行保证路径检查、归属检查与写入处于同一事务边界，冲突返回明确错误。资源上挂入其它功能的子资源后，本功能拒绝覆盖或级联撤销。

角色授权是另一个操作：明确选择角色后逐项调用既有 grant/revoke API，status=0 仅授予使用权。版本发布和回滚只改变生效快照，原入口与授权继续存在。撤销入口保留版本，删除草稿前必须先撤销其资源绑定。资源、接口绑定及角色授权在事务提交后清理已有权限缓存，回滚保留旧缓存。

部署需执行新增菜单绑定 changeSet `lambda-fusion-form-202610010001` 与资源树锁 changeSet `lambda-authority-202610010002`。表单模块对 authority 和 permission-api 均为可选依赖；缺少本地菜单服务时挂载能力明确不可用。动态接口目录目前限同应用、当前租户请求；跨服务权限服务尚需后续实现携带租户边界的动态目录同步，不能将它当作现有静态编译元数据直接全局上报。

## 11. warm-flow 集成（P2）

复用五步范式（`workflow.md` §10.2），由本模块一次性通用实现：

1. 动态表自持业务事实，`flow_status`/`flow_instance_id` 两列为挂流程目标表的必备列（§5.5 发布校验）；
2. 挂流程的表单新增后"提交"动作调 `instanceService.start(businessId=记录id, bizType="form:{formKey}", FlowParams.ext 同约定)`；
3. `FormBusinessProvider implements WorkflowBusinessProvider`：按 `title_template` + 记录字段渲染标题/链接；
4. `FormFlowStatusUpdater` 以 `@EventListener` 消费 `WorkflowFlowEvent` 回写 `flow_status`（谓词分组同 `LeaveFlowStatusUpdater`）；
5. 补齐 `la_workflow_form_path` 消费端：办理端按 `form_path` 跳转该表单的运行时页（`/form/runtime/{formKey}/`，带 `id` 与 `mode=approval`），动态表单即审批办理页——该消费逻辑在 workflow 模块当前未实现，本特性落地后闭环。

幂等靠业务状态机（同 workflow.md §10.2 第⑤步）：`flow_status` 草稿/审批中/通过的迁移校验拒绝重复提交；审批中/已通过的记录由状态机拦截编辑与删除。m1 的另一范式是业务表不加流程列、用独立关系表关联流程实例——本设计不采用，保持与 workflow 模块既有业务表范式一致。

## 12. 错误码（18xxx 段）

`FormErrorCode implements ErrorCode`（契约 §11.1，禁止裸 code），占用 **18xxx**（authority 13xxx / message 14xxx / logger 15xxx / workflow 16xxx / startup 17xxx / ai 30xxx 之后的首个空闲段，落地前按全仓盘点再对齐一次）：

| 码段 | 用途 |
| --- | --- |
| 18100-18109 | 通用：参数非法（18100）、表单不存在（18101）、未发布（18102）、formKey 已存在（18103）、已停用（18104）、已发布表单不可删除（18105） |
| 18110-18119 | 发布：目标表校验失败（18110，消息携带缺失列/类型不兼容明细，亦用于快照标识符非法）、版本不存在（18111）、内容未变更（18112，MD5 相同）、草稿并发冲突（18113，`@Version` 乐观锁） |
| 18120-18129 | 查询：字段不在白名单（18120）、操作符不支持（18121）、条件数超限（18122）、树子孙超限（18123，P1 生效） |
| 18130-18139 | 数据：数据不存在（18130）、存在子表数据禁止删除（18131）、字段校验失败（18132，非空缺失等） |
| 18140-18149 | 流程（P2）：流程状态不允许该操作（18140） |

## 13. 风险登记

| # | 风险 | 缓解 |
| --- | --- | --- |
| R1 | 新表诉求仍需一次部署（无运行时建表） | 毕业路径把摩擦降到一次评审（§2.1）；设计器引导"先建表再映射" |
| R2 | 查询 AST 逃逸为注入面 | 三层白名单 + 值全参数化，表名/列名永不出自请求（§4.3） |
| R3 | 通用接口性能（大宽表分页） | 分页为 `COUNT` + `LIMIT/OFFSET` 直下推（不走实体栈）；`query_show` 字段建议建索引由设计器提示；两段式级联控住 JOIN 复杂度（P1） |
| R4 | 停用前已取得模型的在途操作，以及缓存容量增长 | 新请求逐次核对状态与版本，缓存不能绕过停用；结构变更处理在途请求；缓存容量/主动清理待完善（§4.2） |

## 14. 已知限制与后续

1. **平台级共享表单**：`la_form_*` 本期租户内隔离，平台级共享走豁免白名单 + 差异化下发（可参考 m1 版本-租户绑定），后续单独立项；
2. **分离部署的动态权限目录**：本地路由挂载、角色授权和运行时目录已实现；跨服务动态目录同步尚未实现。版本发布始终不承担资源注册（§10.3）；
3. **行级数据权限**：留 DataScope 扩展；m1 的 rule 模型可作参考，但其 SQL 片段通道明确不引入（§4.3）；
4. **导入导出/批量操作/合计列**：待真实诉求再评估（m1 有 sumColumn 与超 2 万行大导出实现可参考）；
5. **版本导入导出**：跨环境迁移（导入换 id），随 P3 毕业路径一并落地（§15）。

## 15. 分期实施

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 ✅ 已实施 | 模块骨架（pom + 三件套 + imports + 前缀登记）；`lambda-form-changelog.xml` 四表；design 子域（草稿 CRUD + 选表导列 + 发布/停用/回滚/版本历史）；runtime 子域（快照编译 + TTL 缓存 + AST 白名单 + 单表 CRUD + 权限/租户/审计 + 自关联树直接子节点）；前端引擎组件 + 设计器；startup 演示表 `la_demo_leave` 与菜单 | 模块 `mvn test` 36 项通过（AST 白名单 10 + 编译 14 + 动态数据 12）；`mvn compile` 过 Spotless/SpotBugs；演示流程见 §17 未勾选项 |
| P1 ◐ 部分实施（2026-09-29） | 设计器三步化（§4.9）：字段显示配置归组为 `QUERY_CONFIG`/`LIST_CONFIG`/`FORM_CONFIG` 三个 JSON 配置列（键白名单 + 值域两道校验），设计器改三步向导（基础与数据 / 列表设计 / 表单与详情），列表列宽/对齐/格式化、查询默认值、字段级只读、校验规则表单化 | 模块 `mvn test` 51 项通过（新增配置解析校验 9 + 编译配置块 5）；前端 `vue-tsc` form 目录零错误 |
| 第一批整改 ✅（2026-09-30） | 发布权限、表允许清单、系统列保护、结果投影、逻辑删除、部分更新/清空、IN/区间/空值输入、发布前编译、草稿版本与身份约束、并发发布及缓存版本核对 | `mvn -pl lambda-fusion-form -am test` 通过（form 83 项、core 3 项，含 Spotless/SpotBugs）；前端相关 Vitest 9 项及 web-tdesign vue-tsc 通过 |
| 第二阶段设计器批次 ✅（2026-10-01） | Vben 拖拽画布、属性面板、统一布局与三种来源预览、业务显示名、启用入口、支持范围收敛 | form 93 项与 core 3 项测试通过；实际 Vben、拖拽及只读预览组件测试与专项类型检查通过；H2 验证 Liquibase 新增字段和重复执行 |
| 第二阶段路由与接口权限批次 | 通用参数页面、独立挂载/更新/撤销、明确角色授权、动态接口目录与真实请求鉴权、提交后缓存清理 | 真实 MVC / Mapper / 事务和浏览器组件夹具验证；具体数量与边界见审计记录 |
| 第二阶段目标表与字段契约批次（2026-10-01） | 真实主键、ID 容量、精确元数据范围、实际租户策略、审计/生成/默认列、服务端类型和规则校验 | JDBC 物理表反例、真实事务中的默认值回滚、发布/回滚/启用结构变更验证；数量及边界见审计记录 |
| P1 | ~~TREE 左树右表~~、~~MASTER_SUB 主从写入与两段式级联查询、`CASCADE_DELETE`~~（均于 2026-10-03 交付，§4.4） | 演示表 `la_demo_area`（左树右表）；主从演示可在设计器配置 |
| P2 | warm-flow 集成（§11 五步 + 办理页消费端）+ `FormDataInterceptor` SPI；缓存优化按实际性能需求评估 | 动态表单走通审批闭环 |
| P3 | 代码生成毕业路径（含新表 changelog 生成）+ 版本导入导出 | 生成源码编译进模块 |

尚未完成项已就地登记：§4.2（缓存容量）、§5.5（字典成员、文件协议和目标数据库部署验收）、§10（分离部署下的动态目录同步）。

## 16. 关键决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 1 | 路线 C：元数据引擎主线 + 毕业路径 | 字节码动态 Bean 否决，理由见 §2.1 |
| 2 | 契约 §6 不修订 | 不运行时建表，表单只映射既有物理表（§2.2、§5.5） |
| 3 | 命名 | 模块 `lambda-fusion-form` / 前缀 `lambda.fusion.form` / 元数据表 `la_form_*`；`la_df_` 前缀留给毕业路径生成表 |
| 4 | 元数据租户内隔离 | `la_form_*` 默认租户归属；平台级共享表单后续扩展（§14） |
| 5 | 发布快照模型 | 草稿工作行 + 不可变快照 + `published_version` 指针（§4.1），回滚 = 指针回指 |
| 6 | FILE 字段值 = 附件三元组 JSON 数组文本 | 复用 lambda-fusion-oss（§5.2、§7.2）；受控上传端点按表单权限授权，名称/地址随值存储避免渲染回查 |
| 7 | 服务端扩展 SPI | `FormDataInterceptor`（§4.7，P2）；前端事件脚本不做 |

## 17. 落地检查清单（PDCA Check）

- [x] `mvn compile` 通过（Spotless Palantir + SpotBugs，契约 §13）：`lambda-fusion-form` 模块已通过；`mvn -pl lambda-fusion-startup -am compile` 当前被环境阻塞——本地仓库的 `lambda-cloud-parent` pom 早于本次 warm-flow 版本管理变更，而刷新它需要 `io.agentscope:agentscope-bom:2.0.4`（配置的镜像无此版本），与本次改动无关；
- [x] changelog 命名 `lambda-form-changelog.xml`，4 个 changeSet 全部带 `preConditions onFail="MARK_RAN"`，表名 `la_form_*`（契约 §6.1）；
- [x] 模块 POM 无硬编码版本，`mvn dependency:tree` 确认 MP 收敛 3.5.17（契约 §1.2）；
- [x] 引擎 SQL 零原文拼接：`wrapper.apply` 零命中；`${}` 仅出现在 `DynamicTableMapper` 的标识符占位（表名/列名/WHERE/ORDER 片段），其来源经 `FormConstants.isIdentifier` 白名单校验（§4.3）；
- [x] AST 白名单单测覆盖：字段越权/算子越权/排序越权/条件超限（`QueryAstValidatorTest` 10 项）；树子孙超限已随 TREE 交付补测（`DynamicDataServiceJdbcTest`）；
- [x] 自动化租户隔离验证：H2 真实租户插件验证动态表读写隔离及 INSERT 补租户列；Provider 测试验证同 formKey、不同定义 ID 的缓存不串用；真实登录/切租户的演示环境验收仍待执行；
- [x] 发布/停用/回滚与缓存自动化验证：Spring 事务提交后失效、回滚不失效、旧加载晚到、版本切换和停用拒绝均有测试；未执行多节点部署验收；
- [ ] startup 演示：changelog 预建演示表 → 设计器选表导列 → 发布 → 运行时 CRUD 全流程（待演示环境执行）；
- [x] `lambda.fusion.form` 前缀登记契约 §3.2（规则文件独立提交，契约 §18.2）。

## 18. 附：参考实现对照（m1-parent）

m1-parent（m1-dynamic-curd / m1-dynamic-form，约 250 类）是生产级先例。与本设计**相同的选择**：不运行时建表（只绑定既有表，DDL 走 Liquibase/外部）、快照行 + 页面双指针（启用版/编辑版）的版本模型、客户端只传 property + 操作符枚举的白名单查询（值全参数化）、增强器 SPI。**关键差异**：m1 走运行期字节码（路线 A，代价见 §2.1）；元数据为单份全量 JSON 文档（本设计为工作行 + 发布快照分离）；工作流用独立关系表（§11）；另有版本级租户绑定实现平台表单差异化下发（§14）。**已借鉴**：发布 MD5 去重、版本导入导出（P3）、增强器下拉、树子孙过滤、审批完成后记录只读。其 SQL 片段规则通道为本设计明确排除的攻击面（§4.3）。
