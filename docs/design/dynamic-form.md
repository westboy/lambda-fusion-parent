# 动态表单（lambda-fusion-form）设计

> 状态：P0 已实施（2026-09-28）；P1 起待开发，分期见 §15
> 实施记录：`lambda-fusion-form` 模块 + `lambda-form-changelog.xml` 四表 + 管理端 8 个前端文件 + startup 演示表 `la_demo_leave`；与本文的偏差已在 §4.4、§9、§10、§15 就地标注
> 相关阅读：`.rule/engineering-contract.md`（§3 自动配置、§5 多租户、§6 Liquibase、§9 Entity/Service、§10 包结构、§16 安全、§19 特性开关、§20 最小充分设计）、`.rule/package-structure.md`、`docs/design/workflow.md`（流程集成范式 §10.2）
> 技术基线：Java 21 / Spring Boot 4.1.1 / MyBatis-Plus 3.5.17 / warm-flow 1.8.9 / vben 5.6 + tdesign

## 1. 背景与定位

后台系统 80% 的页面是"左树右表 / 列表 / 查询 / 新增 / 编辑 / 删除"的 CRUD 变体，但当前每个页面都要手写后端四件套（Entity/Mapper/Service/Controller）+ 前端三件套（index.vue/table/form-dialog）——前端 38 个页面重复同一范式，无任何参数化复用。

**定位**：`lambda-fusion-form` 是元数据驱动的 CRUD 能力层——表单元数据落库后，通用运行时引擎直接提供数据读写接口，前端通用页面按元数据渲染；针对既有物理表，业务方零 Java 代码获得带权限、带租户隔离、可挂审批流的完整 CRUD。新表诉求与超出表达力的复杂功能由代码生成毕业路径承接（§2.1）。

与现有模块的关系：

- 复用 `lambda-fusion-core` 全部分页/树/字典/身份能力（契约 §2.1）；
- 租户隔离走 MyBatis-Plus 租户插件 SQL 解析层，动态业务表默认即被纳入（豁免才是配置项），零额外成本（契约 §5.1）；
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

- 表单目标表必须是经 Liquibase changelog 治理预建的物理表（携带 `tenant_id` 与审计四列），发布时校验列存在性与类型兼容（§5.5）；
- 设计器只做"字段映射 + UI/查询/校验配置"，不产生 DDL；
- 新表诉求走毕业路径：元数据 → 生成源码 + changelog → 正常评审部署。

### 2.3 边界（本期不做）

- 不做运行时建表/DDL（§2.2）；
- 不做运行期字节码动态 Bean（§2.1）；
- 不做拖拽式画布设计器（表格化编辑起步）；
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
│  缓存：快照读穿缓存 + TTL；跨节点 Dubbo broadcast 失效为 P2（§4.2）             │
└──────────────┬───────────────────────────────┬───────────────────────────────┘
               ▼                               ▼
   元数据表 la_form_*（changelog 治理）   业务表 la_*（既有物理表，changelog 治理）
```

## 4. 关键设计

### 4.1 发布快照模型

草稿与发布物分离，是低代码语义的底线（已发布版本持续可用，草稿随便改）：

- `la_form_def` 是**草稿工作行**：设计器就地编辑 def/field/relation 三表，`@Version` 乐观锁防并发覆盖；`published_version` 指针标识对外生效的快照版本（空 = 从未发布）；
- `la_form_def_version` 是**不可变发布快照**：发布时把 def+fields+relations 序列化为一份全量 JSON 写入，版本号单调递增；快照永不修改；
- **发布**：单事务内完成发布校验（§5.5）→ 快照内容 MD5 去重（与当前生效快照一致则拒绝）→ 写快照 → 拨指针；事务提交后广播失效（跨节点广播 P2，P0 为节点内 `evict`）；
- **回滚**：`published_version` 回指历史快照 + 失效，一行更新，无快照增删；
- **停用**：`status` 置 `DISABLED` + 失效，运行时拒绝该表单全部数据请求；
- 运行时只读快照（§4.2），草稿永不对外生效。

### 4.2 表单模型编译与缓存

`FormModelCompiler` 把已发布快照 JSON 编译为不可变 `FormModel`：

```text
FormModel = {
  formKey, formName, version, formType, tableName,   // 物理映射（服务端私有）
  fields: [ { name, column, type, nullable, defaultValue, validation, dictType,
              uiComponent, uiProps, listShow, queryShow, queryOp, formShow, sort } ],
  relations: [ { subFormKey, foreignKey, cascadeStrategy } ],
  tree: { sourceType, parentColumn, nodeColumn, dictType, filterColumn },  // TREE 类型
  treeConfig                                          // 设计端原始 JSON，透传给前端
}
```

查询/排序白名单不另存副本：`QueryAstValidator` 直接按 `fields` 的 `queryShow`（条件字段）与 `listShow`（排序列）派生，避免白名单与字段定义两份事实（契约 §20.3）。`tree` 的 `dictType` 字段已建模但 P0 编译期只接受 `sourceType=SELF`，其他来源抛 `FORM_TABLE_INVALID`（§4.4）。

- 缓存 key = `{tenantId, formKey}`，值为当前 `published_version` 对应的 `FormModel`；读穿加载，版本变化即 key 内值替换；
- 失效：发布/停用/回滚后本节点立即失效；跨节点经 Dubbo broadcast 通知各节点（P2，AI 模块 `DubboInvalidationConfiguration` 同模式），在此之前由 TTL（`lambda.fusion.form.cache-ttl-seconds`）兜底（契约 §4.1 优雅降级）；
- 草稿工作行不进缓存——TTL 降级节点也只会编译到已发布快照，半成品草稿污染运行时的窗口不存在；
- `FormModel` 不可变，读取全程无锁。

**P0 实施现状**：缓存为节点内 `ConcurrentHashMap` + TTL（`cacheTtlSeconds` 到期即重编译），发布/停用/回滚后本节点立即 `evict`；Dubbo 跨节点广播为 P2（§15），在此之前多节点部署存在 TTL 窗口（§13 R4）。

### 4.3 查询 AST 白名单（安全红线，契约 §16）

客户端只传结构化条件：

```json
{
  "pageNum": 1, "pageSize": 20,
  "sorts": [ { "field": "createdAt", "asc": false } ],
  "conditions": [ { "field": "applicant", "op": "like", "value": "张" } ]
}
```

`field` 一律是**字段标识**（前端 vModel），物理列名只在服务端解析；`between` 的 value 为长度 2 数组、`in` 为数组、`isNull/isNotNull` 忽略 value。

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

**P0 实施现状（偏差登记）**：P0 只落地单表 CRUD 与自关联树的**直接子节点**查询（`GET /v1/form/{formKey}/tree?parentValue=`），因此：

- 写路径的主从同事务写入、读路径的两段式组装、`CASCADE_DELETE` 级联删除均未实现（`la_form_relation` 已建模、删除时的子表存在性检查已生效，但 P0 一律按 `FORBID_DELETE` 处理，见 `DynamicDataServiceImpl#delete` 注释）；
- 列表页的"选树节点过滤全部子孙"未实现，`maxTreeDescendants` 与 `FORM_TREE_DESCENDANT_LIMIT`(18123) 为 P1 生效项；树来源 P0 只支持 `SELF` 自关联（`DICT` 等来源在编译期即拒绝）；
- 上述三项随 P1 一并交付，届时 §4.4 全部条款生效。

### 4.5 审计字段填充

`GlobalMetaObjectHandler` 只覆盖实体路径，通用引擎的 Map 投影不经过它——这是本模块唯一的手工点。写路径在 `DynamicDataServiceImpl#fillAudit` 内联填充 `CREATED_BY/CREATED_AT/UPDATED_BY/UPDATED_AT`（经 `AuthUtils.getSafeUsername()` 取当前身份，无登录上下文时退化为匿名），目标表的审计四列在发布校验时强制存在（§5.5）。

### 4.6 权限模型与权限码约定

- 运行时数据接口：`StpUtil.checkPermission("form:{formKey}:{action}")` 编程式检查（action ∈ `page/add/edit/delete`），替代注解（路径参数运行期才知，注解不适用）；
- 设计接口：固定权限码 `form:design:*`，与既有管理端页面同构；
- 权限项作为 authority 资源注册，与菜单/按钮同构；P0 手工注册（§10.3），`/schema` 端点返回服务端已解析的按钮权限集，前端据此渲染操作列；
- 菜单挂载：每个表单一个薄包装页（路由组件由菜单 `RES_PATH` 推导，见 §10.1），包装页只声明 `formKey`，渲染逻辑全在共享引擎组件内。

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
- 发布/回滚/停用均为"一行指针更新 + 缓存失效"（跨节点广播 P2），无 DDL、无字节码，集群不存在"结构已变、缓存未刷新"的窗口；
- 目标表结构演进走正常部署（§2.2）；目标表字段下架须先停用引用它的表单。

## 5. 元数据表结构（changelog 治理）

四张表走 `lambda-form-changelog.xml`（契约 §6.1），changeSet 带 `preConditions onFail="MARK_RAN"`；列名大写（同 `la_workflow_form_path` 惯例），均含 `TENANT_ID varchar(64)` 与审计四列（`CREATED_BY varchar(32)` / `CREATED_AT datetime` / `UPDATED_BY varchar(32)` / `UPDATED_AT datetime`）。

### 5.1 `la_form_def` 表单定义（草稿工作行）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `ID` | varchar(32) PK | 主键 |
| `FORM_KEY` | varchar(64) NN | 表单标识，URL 与权限码的一部分 |
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
| `VALIDATION` | text | 校验规则 JSON（正则/范围/必填提示） |
| `DICT_TYPE` | varchar(64) | DICT 类型的字典编码（lambda-fusion-dictionary） |
| `UI_COMPONENT` | varchar(32) | 前端组件名（`Input`/`Select`/`DatePicker`…） |
| `UI_PROPS` | text | 组件属性 JSON：placeholder、label span、日期格式、查询默认操作符、列宽 |
| `LIST_SHOW` / `QUERY_SHOW` / `FORM_SHOW` | tinyint NN | 三视图显隐 |
| `SORT` | int NN | 字段顺序 |

唯一键 `uk_form_field_name (FORM_ID, FIELD_NAME)`。`FILE` 类型值 = lambda-fusion-oss 附件 id（单值存 id，多值存 id JSON 数组）。

### 5.3 `la_form_relation` 主从关系（MASTER_SUB）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `ID` | varchar(32) PK | 主键 |
| `FORM_ID` | varchar(32) NN | 主表单 |
| `SUB_FORM_KEY` | varchar(64) NN | 子表单 formKey（同租户内解析） |
| `FOREIGN_KEY` | varchar(64) NN | 子表外键列 |
| `CASCADE_STRATEGY` | varchar(16) NN | `CASCADE_DELETE` / `FORBID_DELETE` |

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

`la_form_def.table_name` 必须指向 Liquibase 治理下已存在的物理表，发布时校验：

1. 表存在，且未命中 `lambda.fusion.form.table-exclude-pattern` 的表（元数据表自身、Liquibase/Quartz 系统表等）不可映射；
2. 每个 `column_name` 在表中存在且类型与 `field_type` 兼容（`INT`↔`DECIMAL` 放宽、`TEXT` 可映射 `STRING` 列、`DICT`/`FILE` 可映射字符/整型列）；
3. 必含 `ID`、`tenant_id` 与审计四列；挂流程的表另需 `flow_status` / `flow_instance_id`（由该表所属模块的 changelog 定义，随 P2 一并纳入发布校验，§11）。

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
| lambda-cloud-starter-dubbo | 可选 | P2 跨节点失效广播；缺失时 TTL 降级（契约 §4.1） |

P0 未引入 oss / workflow / dictionary 依赖：FILE 字段只存附件 id（读写由前端经 oss 接口完成）、warm-flow 桥接与字典翻译（前端 `useDict` 已覆盖）分别在 P2 / 无需后端介入。

无硬编码版本（契约 §1.2），经 BOM 收敛。

### 7.3 Liquibase

`META-INF/db/changelogs/lambda-form-changelog.xml` 建 §5 四张元数据表；目标业务表不在本模块 changelog 内（§2.2）。

## 8. API 定稿

### 8.1 设计 API（管理端，权限码 `form:design:*`）

| 方法与路径 | 权限码 | 说明 |
| --- | --- | --- |
| `GET /v1/form-design/page` | `form:design:page` | 表单分页（草稿行 + 生效版本号） |
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
| `PUT /v1/form/{formKey}/{id}` | `form:{formKey}:edit` | 编辑（全量替换语义：非空字段必须一并提交） |
| `DELETE /v1/form/{formKey}/{id}` | `form:{formKey}:delete` | 删除（按级联策略，P0 一律拒绝有子表数据的记录） |

Controller 只编排（契约 §9.3），每个端点先 `StpUtil.checkPermission`；出参分页统一 `PageView`（契约 §8.2），行形态为按元数据裁剪的 `Map` 投影；表单不存在/未发布/已停用分别抛 `FORM_NOT_FOUND`/`FORM_NOT_PUBLISHED`/`FORM_DISABLED`。

### 8.3 渲染元数据契约（前端消费）

`/schema` 返回 `FormSchemaView`——只含渲染所需信息，**剥离物理映射**（column/table 不出服务端）：

```json
{
  "formKey": "leave_demo", "formName": "请假演示", "formType": "TREE", "version": 3,
  "fields": [
    { "name": "reason", "label": "事由", "type": "STRING", "component": "Input",
      "props": "{\"placeholder\":\"请输入\"}", "validation": "{\"required\":true,\"max\":200}",
      "nullable": false, "listShow": true, "queryShow": true, "queryOp": "like", "formShow": true, "sort": 1 }
  ],
  "treeConfig": "{\"sourceType\":\"SELF\",\"parentColumn\":\"PARENT_ID\",\"nodeColumn\":\"NAME\"}",
  "permissions": { "add": true, "edit": true, "delete": false }
}
```

`validation` / `props` / `treeConfig` 为设计端原样存储的 JSON **字符串**（前端 `JSON.parse` 后使用），服务端不解释其内容。`label` P0 直接取字段标识（`la_form_field` 无显示名列；若要展示列注释或自定义中文名，需补 `LABEL` 列，属后续增强）。

`permissions` 由服务端按当前用户逐 action 解析（`StpUtil.hasPermission`，action ∈ `add/edit/delete`；`page` 是访问本页的前提，已在 Controller 入口校验，故不下发），前端只按布尔渲染按钮，不自行拼权限码。

## 9. 配置项定稿

| key | 默认 | 说明 |
| --- | --- | --- |
| `lambda.fusion.form.enabled` | `true` | 总开关（契约 §19） |
| `lambda.fusion.form.cache-ttl-seconds` | `300` | 元数据缓存 TTL（P0 为唯一失效兜底，Dubbo 广播 P2 生效，§4.2） |
| `lambda.fusion.form.max-condition-count` | `20` | AST 条件数上限（§4.3） |
| `lambda.fusion.form.tree.max-descendants` | `500` | 树子孙 id 集上限（P1 生效，P0 未实现子孙过滤，§4.4） |
| `lambda.fusion.form.table-exclude-pattern` | `la_form_.*\|databasechangelog.*\|qrtz_.*` | 选表导列与发布校验的表名排除正则，大小写不敏感（§5.5） |

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
    ├── runtime/<formKey>/index.vue      每表单薄包装页：<FormRuntime form-key="..." />
    └── designer/{index.vue,form-def-editor.vue}
```

引擎组件按 `formKey` 拉 `/schema` → 生成查询栏 schema（`buildSearchSchema`）与 `VxeTableGridOptions.columns`（`buildColumns`）→ 复用 `useVbenForm` / `useVbenVxeGrid`（`adapter/` 已全局封装，组件名经 `adapter/component.ts` 解析）；字典列经 `useDict` 翻译，日期/布尔经 `displayText` 格式化；操作列按 `permissions` 布尔渲染。

**薄包装页不可省**：路由组件路径由菜单 `RES_PATH` 推导（`normalizeViewPath` + `pageMap` glob），一个路径只能对应一个 `.vue` 文件，因此"新增一个动态表单"= 设计端建表单 + 建菜单行 + 落一个三行的包装页（`formKey` 也可省略——组件缺省取路由末段）。

TREE 左树右表布局与主从子表编辑为 P1（§15）；P0 的引擎组件只渲染列表/查询/CRUD，`formType=TREE` 表单同样按列表渲染。

### 10.2 表单设计器

- 起点是"选表导列"：`/tables` 选目标物理表 → `/tables/{table}/columns` 读列元数据一键生成字段草稿（`fieldName` 由列名转小驼峰、`columnName`/`fieldType` 自动带入），契约列（`ID`/`TENANT_ID`/审计四列）自动跳过，已存在的列不重复导入；
- 字段表格化编辑：标识/列名/类型/必填/三视图显隐/查询算子/字典/组件/排序，行内增删；
- `TREE` 类型展示 `treeConfig` JSON 编辑框，`MASTER_SUB` 类型展示主从关系行编辑；
- 列表页承载发布/停用/回滚/版本历史（`/versions`）与可复制权限码（`form:{formKey}:page/add/edit/delete`）；
- 本期不做拖拽画布，表格化编辑即可（契约 §20.1 最小充分）。

### 10.3 菜单与权限注册（P0 手工）

菜单/按钮权限仍是 authority 资源，走 changelog 预置（与既有静态页同流程）。P0 已登记：

| changeSet | 资源 | 说明 |
| --- | --- | --- |
| `lambda-authority-2026092800010/11` | `FmTop00001` + `FmDesign001` | 顶层"动态表单" + "表单设计"页（`/form/designer/` → `views/form/designer/index.vue`），按钮 `form:design:page/detail/add/edit/delete/table/publish`，授予 `ROLE_ADMIN`/`ROLE_SYSTEM`/`ROLE_DEV` |
| `lambda-fusion-startup-202609280002/03` | `FmDemo00001` | 演示表单"请假动态表单"（`/form/runtime/leave_demo/` → `views/form/runtime/leave_demo/index.vue`），按钮 `form:leave_demo:page/add/edit/delete` |

新增动态表单时按同构补三条：菜单行（`RES_PATH` 指向该表单的运行时路径）+ 四个按钮权限码 + 角色授权。发布/版本切换时按 diff 自动注册/回收按钮权限留后续（m1 `publishResource`/`changeResource` 已有先例，§14）。

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
| R4 | 元数据缓存失效前的旧版本窗口 | 广播失效 + 懒加载；无 Dubbo 时 TTL 上界 300s（§4.2）；字段下架须先停用引用表单 |

## 14. 已知限制与后续

1. **平台级共享表单**：`la_form_*` 本期租户内隔离，平台级共享走豁免白名单 + 差异化下发（可参考 m1 版本-租户绑定），后续单独立项；
2. **按钮权限自动注册**：发布/版本切换 diff 注册（m1 `publishResource` 先例），本期手工（§10.3）；
3. **行级数据权限**：留 DataScope 扩展；m1 的 rule 模型可作参考，但其 SQL 片段通道明确不引入（§4.3）；
4. **导入导出/批量操作/合计列**：待真实诉求再评估（m1 有 sumColumn 与超 2 万行大导出实现可参考）；
5. **版本导入导出**：跨环境迁移（导入换 id），随 P3 毕业路径一并落地（§15）。

## 15. 分期实施

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 ✅ 已实施 | 模块骨架（pom + 三件套 + imports + 前缀登记）；`lambda-form-changelog.xml` 四表；design 子域（草稿 CRUD + 选表导列 + 发布/停用/回滚/版本历史）；runtime 子域（快照编译 + TTL 缓存 + AST 白名单 + 单表 CRUD + 权限/租户/审计 + 自关联树直接子节点）；前端引擎组件 + 设计器；startup 演示表 `la_demo_leave` 与菜单 | 模块 `mvn test` 36 项通过（AST 白名单 10 + 编译 14 + 动态数据 12）；`mvn compile` 过 Spotless/SpotBugs；演示流程见 §17 未勾选项 |
| P1 | TREE 左树右表（含列表页子孙过滤 + `maxTreeDescendants`）；MASTER_SUB 主从写入与两段式级联查询、`CASCADE_DELETE` | 左树右表与主从演示 |
| P2 | warm-flow 集成（§11 五步 + 办理页消费端）+ Dubbo 跨节点失效广播 + `FormDataInterceptor` SPI | 动态表单走通审批闭环 |
| P3 | 代码生成毕业路径（含新表 changelog 生成）+ 版本导入导出 | 生成源码编译进模块 |

P0 的偏差已就地登记：§4.2（广播未做）、§4.4（子孙过滤/级联删除未做）、§8.3（label 取字段标识）、§9（`tree.max-descendants` P1 生效）。

## 16. 关键决策

| # | 决策 | 说明 |
| --- | --- | --- |
| 1 | 路线 C：元数据引擎主线 + 毕业路径 | 字节码动态 Bean 否决，理由见 §2.1 |
| 2 | 契约 §6 不修订 | 不运行时建表，表单只映射既有物理表（§2.2、§5.5） |
| 3 | 命名 | 模块 `lambda-fusion-form` / 前缀 `lambda.fusion.form` / 元数据表 `la_form_*`；`la_df_` 前缀留给毕业路径生成表 |
| 4 | 元数据租户内隔离 | `la_form_*` 默认租户归属；平台级共享表单后续扩展（§14） |
| 5 | 发布快照模型 | 草稿工作行 + 不可变快照 + `published_version` 指针（§4.1），回滚 = 指针回指 |
| 6 | FILE 字段值 = 附件 id | 复用 lambda-fusion-oss（§5.2） |
| 7 | 服务端扩展 SPI | `FormDataInterceptor`（§4.7，P2）；前端事件脚本不做 |

## 17. 落地检查清单（PDCA Check）

- [x] `mvn compile` 通过（Spotless Palantir + SpotBugs，契约 §13）：`lambda-fusion-form` 模块已通过；`mvn -pl lambda-fusion-startup -am compile` 当前被环境阻塞——本地仓库的 `lambda-cloud-parent` pom 早于本次 warm-flow 版本管理变更，而刷新它需要 `io.agentscope:agentscope-bom:2.0.4`（配置的镜像无此版本），与本次改动无关；
- [x] changelog 命名 `lambda-form-changelog.xml`，4 个 changeSet 全部带 `preConditions onFail="MARK_RAN"`，表名 `la_form_*`（契约 §6.1）；
- [x] 模块 POM 无硬编码版本，`mvn dependency:tree` 确认 MP 收敛 3.5.17（契约 §1.2）；
- [x] 引擎 SQL 零原文拼接：`wrapper.apply` 零命中；`${}` 仅出现在 `DynamicTableMapper` 的标识符占位（表名/列名/WHERE/ORDER 片段），其来源经 `FormConstants.isIdentifier` 白名单校验（§4.3）；
- [x] AST 白名单单测覆盖：字段越权/算子越权/排序越权/条件超限（`QueryAstValidatorTest` 10 项）；树子孙超限随 P1 实现补测；
- [ ] 租户隔离验证：两租户同名 formKey 互不可见；动态表 SELECT 自动带 `tenant_id`、INSERT 自动填充——机制上由 `TenantLineInnerInterceptor` 承担（引擎零租户代码），集成验证待演示环境执行；
- [ ] 发布/停用/回滚后缓存失效验证：节点内 `evict` 已实现，跨节点广播为 P2（§4.2）；
- [ ] startup 演示：changelog 预建演示表 → 设计器选表导列 → 发布 → 运行时 CRUD 全流程（待演示环境执行）；
- [x] `lambda.fusion.form` 前缀登记契约 §3.2（规则文件独立提交，契约 §18.2）。

## 18. 附：参考实现对照（m1-parent）

m1-parent（m1-dynamic-curd / m1-dynamic-form，约 250 类）是生产级先例。与本设计**相同的选择**：不运行时建表（只绑定既有表，DDL 走 Liquibase/外部）、快照行 + 页面双指针（启用版/编辑版）的版本模型、客户端只传 property + 操作符枚举的白名单查询（值全参数化）、增强器 SPI。**关键差异**：m1 走运行期字节码（路线 A，代价见 §2.1）；元数据为单份全量 JSON 文档（本设计为工作行 + 发布快照分离）；工作流用独立关系表（§11）；另有版本级租户绑定实现平台表单差异化下发（§14）。**已借鉴**：发布 MD5 去重、版本导入导出（P3）、增强器下拉、树子孙过滤、审批完成后记录只读。其 SQL 片段规则通道为本设计明确排除的攻击面（§4.3）。
