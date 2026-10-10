# Lambda Fusion Form

`lambda-fusion-form` 是 Lambda Fusion 的动态表单模块，提供：

- **可视化表单设计**（三步向导：基础与数据 → 表单与详情 → 列表设计）
- **零代码运行时**（对任意映射物理表的动态 CRUD、查询、导出、子表、文件上传）
- **发布快照与版本回滚**（草稿/发布双态，发布物不可变）
- **流程挂载**（表单可被工作流定义挂载，支持流程内提交与状态回写）

---

## 1. 模块定位与能力边界

| 维度 | 说明 |
|------|------|
| **负责** | 表单元数据管理、表元数据内省、动态 SQL 编译与执行、目标表契约校验、发布快照 |
| **不负责** | 物理表 DDL（目标表由使用方在库中自行建好，模块按元数据内省对接）、流程定义编排（workflow 模块）、菜单资源管理（authority 模块） |

- 设计器前端为「动态表单 → 表单设计」菜单下的三步向导。
- 动态读写面向**任意已存在的物理表**（平台主库或任一已注册数据源中的表），模块自身不做任何 DDL，也不要求目标表经 Liquibase 治理。

---

## 2. 功能总览

### 2.1 表单类型

| 类型 | 说明 |
|------|------|
| `SINGLE` | 单表表单 |
| `TREE` | 树表；树数据源支持 `SELF` 自关联 / `FORM` 关联另一树表单 / `DICT` 字典树；呈现方式默认左树右表，SELF 自关联还支持整页缩进树表格 |
| `MASTER_SUB` | 关联表；子表直连物理表，呈现方式 `INLINE` 内联编辑 / `LIST` 台账式 / `FLAT` 一对一扁平 |

### 2.2 设计器三步

1. **基础与数据**：名称/类型/目标数据源/目标表、删除方式（物理/逻辑列）、数据写入拦截器、流程表单开关、主从关系配置、按表导列生成字段草稿；表单标识不采集，保存时由后端自动生成（`f_` + 随机段，租户内唯一）。
2. **表单与详情**：拖拽画布布局（组件面板/字段大纲/字段属性三栏）、详情呈现配置（弹窗/抽屉/全屏、列数、系统信息折叠）。
3. **列表设计**：分页条数、序号列、操作按钮（显隐与文案）、查询条件（操作符/默认值）、表格列（显示/列宽/对齐/格式化/冻结/默认排序/合计/列序）。

### 2.3 运行时能力

- 分页查询（AST 条件白名单编译）、树节点查询、详情、增删改
- 编辑带 `__etag` 行指纹乐观锁（详情与台账子行随响应下发，更新端回传比对，覆盖主行与内联子行；列表行不携带子行与指纹，编辑弹窗按 id 现取详情）
- 台账式子表独立分页/增删改（外键由服务端绑定主行 id）
- CSV 导出（UTF-8 BOM，行数上限 `max-export-rows`）
- FILE 字段上传（复用 OSS 附件体系，oss 模块在场时）
- 流程表单提交（同一事务内新增业务行并发起流程）

---

## 3. 数据模型与版本快照

| 表 | 说明 |
|------|------|
| `la_form_def` | 草稿工作行（表单标识/类型/目标表/目标数据源/逻辑删除列/列表与详情配置/布局 JSON/乐观锁版本） |
| `la_form_field` | 字段清单（列映射/类型/落库开关/查询与列表与表单配置 JSON/从表归属标记） |
| `la_form_relation` | 主从关系（子表物理表/外键/级联策略/呈现方式/子表配置 JSON） |
| `la_form_def_version` | 发布快照（`SNAPSHOT_JSON` 全量 + `CONTENT_MD5` + `version` 版本号）；`la_form_def.PUBLISHED_VERSION` 为指向生效版本的指针 |

**草稿/发布双态**：设计期就地编辑三表；发布时校验 → 组装 `FormDefSnapshot`（MapStruct）→ 存版本 → 拨动 `la_form_def.PUBLISHED_VERSION`。运行时只读快照编译产物，发布物不可变；支持回滚到任意历史版本。

---

## 4. 设计期链路

### 4.1 表元数据读取（`TableMetadataReader`）

- 经 JDBC `DatabaseMetaData` 内省（表/列/主键/唯一索引），不执行任何 DDL；只读当前连接 catalog/schema，不跨库合并同名表。
- 列元数据走 `formTableMeta` 短 TTL 缓存（`lambda.cache.caches.form-table-meta`，框架默认 1 小时、演示环境配 60s；缓存 starter 缺席时直通），缓存键为 `数据源id:表名`。

### 4.2 目标数据源绑定

- `la_form_def.DATASOURCE_ID`（可空 = 平台主库），与目标表同口径**创建后不可修改**。
- 枚举端口 `DataSourceCatalog`：动态数据源组件在场时经 `RemoteDataSourceApi` 列出已启用数据源，缺席时为空清单（设计器仅显示平台主库）。
- 解析端口 `RuntimeDataSourceResolver`：按注册 id 经基础框架 `DynamicDataSourceService` 取连接池；空 id 直用主 DataSource。
- 发布校验（`TargetTableContract`）与运行时读写按同一数据源执行；绑定关系为**表单级显式配置**，与租户上下文解耦（契约 §5.1/§5.2 禁止按租户切换数据源）。

### 4.3 表边界（`FormProperties#isMappableTable`）

- 默认排除正则：`la_(?!demo_).*|ai_.*|flow_.*|databasechangelog.*|qrtz_.*`——`la_` 前缀（除 `la_demo_*` 演示表）与 `ai_`/`flow_` 前缀均为平台内部表受保护，Liquibase/Quartz 系统表不可映射。
- 平台新增内部模块的数据表须沿用约定前缀或登记进默认排除集；业务表使用自有前缀（如 `crm_*`）。可经 `table-exclude-pattern` 覆盖。

### 4.4 保存期校验（把运行期错误前置）

字段标识/列名唯一（按 `(subFormKey, column)` 复合键）、字段类型与列类型兼容性口径、系统列禁映射（`TENANT_ID` 结构性禁止；`ID`/审计列仅可映射为不落库展示字段）、落库字段映射列必须真实存在于目标表、逻辑删除列必须已映射且主从一致、子表外键必须落在该关系从字段的落库列上。

---

## 5. 运行期链路

```text
la_form_def_version.SNAPSHOT_JSON
     ↓ FormModelCompiler（结构校验 + 归一）
FormModel（不可变编译产物，运行时唯一事实来源）
     ↓ FormModelProvider（缓存；草稿保存/发布后失效）
DynamicDataService → DynamicTableMapper（MyBatis 脚本，表名/列名白名单，值参数化）
```

- **目标表契约**（`TargetTableContract`）：`ID` 必须唯一主键且能容纳 19 位雪花 ID；审计列按物理存在自动适配；租户规则二选一——表带 `TENANT_ID` 列则纳入插件过滤并校验租户值，或表加入租户豁免清单作全局表（含租户列却被豁免直接拒绝）。
- **标记唯一字段**：发布期校验目标表存在覆盖该列（或与 `TENANT_ID` 联合）的唯一索引。
- **过滤列索引**：发布期校验主从外键列与 SELF 树父列被某个索引的**最左列**直接覆盖（主子查询、级联与树解析的高频过滤列；平台管理模式查询不带租户条件，`(TENANT_ID, FK)` 组合索引不构成兜底）。
- **数据写入拦截器**：实现 `FormDataInterceptor` 并注册为 bean，表单经 `INTERCEPTOR` 字段引用，写入前后生效。

---

## 6. HTTP API

### 6.1 设计端 `/v1/form-design`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/page`、`/{id}` | 表单分页 / 草稿详情（def + fields + relations） |
| POST / PUT / DELETE | `/`、`/{id}` | 新建 / 保存（乐观锁）/ 删除草稿 |
| GET | `/datasources` | 设计器数据源下拉（组件缺席时空清单） |
| GET | `/tables`、`/tables/{tableName}/columns` | 可映射表清单 / 列元数据（按 `datasourceId` 参数读源） |
| GET | `/interceptors` | 已注册数据写入拦截器 |
| POST | `/preview`、GET `/{id}/preview`、GET `/{id}/versions/{version}/preview` | 未保存设计 / 已存草稿 / 指定版本预览 |
| POST | `/preview/data/**` | 预览态真实数据读写（page/tree/detail/增删改） |
| POST | `/{id}/publish`、`/{id}/enable`、`/{id}/disable` | 发布 / 启用 / 停用 |
| POST | `/{id}/rollback/{version}` | 回滚到历史快照 |
| PUT | `/{id}/route` 等 | 菜单挂载（经 authority 资源，独立于版本发布） |

设计端权限码：`form:design:page/detail/add/edit/delete/table/preview/preview:write/publish/route`。

### 6.2 运行时 `/v1/form`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/{formKey}/schema` | 渲染元数据（含当前用户按钮权限） |
| POST | `/{formKey}/page`、GET `/{formKey}/tree` | 分页查询 / 树节点 |
| GET / POST / PUT / DELETE | `/{formKey}/...` | 详情 / 新增 / 编辑 / 删除（按级联策略） |
| POST | `/{formKey}/submit` | 流程表单提交 |
| GET/POST/PUT/DELETE | `/{formKey}/{id}/subs/{subFormKey}/**` | 台账式子表分页与增删改 |
| POST | `/{formKey}/export` | CSV 导出 |
| POST | `/upload` | FILE 字段上传（oss 在场时装配） |

运行时按钮权限由路由资源（authority）统一授予，`FormApiPermissionProvider` 负责注册。

---

## 7. 扩展点与可选依赖（契约 §4.1，缺席即降级）

| 依赖 | 扩展点 | 缺席时行为 |
|------|------|------|
| `lambda-fusion-authority-api` | `BizDataProvider`（ORG/USER/ROLE 选择器数据）、`FormRouteGateway`（菜单挂载） | 选择器数据为空；挂载端点降级不可用 |
| `lambda-fusion-workflow` | `ProcessFormSource`、`WorkflowSubmitService` | 流程表单开关与提交端点降级为不可用 |
| `lambda-fusion-datasource` | `DataSourceCatalog`、`RuntimeDataSourceResolver` | 数据源下拉为空，仅平台主库可映射 |
| `lambda-fusion-oss` | `AttachmentService`（FILE 字段） | FILE 字段退化为文本输入 |
| `lambda-cloud-starter-cache` | `formTableMeta` 缓存 | 直通实时读取 |
| 业务方实现 | `FormDataInterceptor`（bean 名被表单引用） | 拦截器下拉不含未注册项，保存期拒绝悬空引用 |

---

## 8. 配置全览

```yaml
lambda:
  fusion:
    form:
      enabled: true                   # 模块总开关（自动装配条件）
      table-exclude-pattern: "la_(?!demo_).*|ai_.*|flow_.*|databasechangelog.*|qrtz_.*"
      max-condition-count: 20         # 查询条件数上限
      max-in-values: 500              # 单个 IN 条件值数上限
      max-page-num: 10000             # 深分页页码上限
      max-inline-sub-rows: 200        # INLINE 子表随主行加载的子行上限
      max-export-rows: 10000          # 导出行数上限（超出拒绝）
      max-tree-descendants: 500       # 树子孙节点 id 集上限
      upload-max-size-mb: 10          # FILE 字段上传大小上限（MB）
      upload-allowed-extensions: [jpg, jpeg, png, gif, webp, bmp, pdf, doc, docx, xls, xlsx, csv, txt, zip]
```

---

## 9. 数据库变更治理

- Liquibase **仅治理模块自身的元数据四表**（`la_form_*`）：新增列/表一律追加 changeSet，带 `preConditions onFail="MARK_RAN"` 幂等保护。
- 目标业务表不属于本模块 changelog 管辖：任意库、任意命名（未命中 §4.3 排除正则即可映射），建表与结构演进由使用方自行负责。
- 发布快照 JSON 不做破坏性迁移，结构演进经 `snapshotVersion` 兼容。

## 10. 接入说明

```xml
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-form</artifactId>
    <version>${revision}</version>
</dependency>
```

- 引入即用：自动装配 `FormAutoConfiguration`（`lambda.fusion.form.enabled=true` 默认开启），元数据四表由 Liquibase 自动建表。
- 需要目标数据源绑定时，同时引入 `lambda-fusion-datasource` 并启用动态数据源路由（`spring.datasource.dynamic.primary` 有值时生效）。
- 目标业务表任意（平台主库或已注册数据源中的现有表均可），须带 `ID` 主键（19 位雪花可容纳）；租户列策略见 §5 目标表契约。
