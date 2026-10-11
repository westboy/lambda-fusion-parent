# 智能问数（AI × 动态表单 NL2Query）设计

> 状态：设计评审稿；问数工具未实施。前置依赖 run-as 通道已实施并通过 PoC（2026-10-12，见 [ai-console-copilot.md](ai-console-copilot.md) §4.3 与本文 §4.5），租户判据已含平台/代维/租户三模式。
> 相关阅读：[dynamic-form.md](dynamic-form.md)（动态表单引擎与三层白名单）、[chat-runtime.md](chat-runtime.md)（对话运行时与 HITL）、[ai-console-copilot.md](ai-console-copilot.md)（管理控制台助手，共享 run-as 通道与错误转译组件）、`.rule/engineering-contract.md`（§2 复用、§3 自动配置、§4 可选依赖、§5 多租户、§12 配置、§15 测试、§16 安全红线、§19 特性开关、§20 最小充分设计）
> 技术基线：Java 21 / Spring Boot 4.1.1 / AgentScope Java 2.0 / MyBatis-Plus 3.5.17

## 1. 背景与定位

业务用户查询台账数据的现有路径是「建列表视图 → 配筛选 → 导出 → 自己加工」，非技术用户在 AI 助手语境下的自然期待是直接提问：「上个月已审批的差旅单总金额多少？」。智能问数（NL2Query）让 Agent 以工具形式查询动态表单数据，是 AI 模块接入平台核心资产（form 业务数据）的第一个能力。

**定位**：`lambda-fusion-ai` 新增「智能问数」子域，LLM 生成**查询 AST**（`FormDataPageQuery`）而非 SQL，执行 100% 复用 `lambda-fusion-form` 动态数据引擎的既有安全栈（三层白名单 + 租户插件 + 行级数据权限 + 字段级角色权限）。

**为什么在这个项目里做 NL2Query 成本远低于通用 text-to-SQL**：通用方案的两个死结——语义（LLM 看不懂裸表结构）与安全（生成 SQL 绕过权限）——在本项目已由 form 引擎解决大半：`FormModel` 自带 label/类型/字典绑定即语义层，`QueryAstValidator` 三层白名单 + 物理列名服务端私有即安全层。本设计只是把 LLM 插进一条已受控的管道。行业参照：Power Platform Copilot、Salesforce Agentforce、钉钉宜搭 + AI 助理、简道云 × FineChatBI 均为「元数据平台 + AI 问数」同构组合，属补齐行业标配而非创新赌注。

## 2. 选型与边界

### 2.1 技术路线

| 路线 | 机制 | 评价 |
| --- | --- | --- |
| A. 裸 Text-to-SQL | LLM 生成 SQL 字符串，经 SQL 解析器校验后直连执行 | **否决**。绕开租户插件与 `dataScopeFilter`，直接违反契约 §16「不得新增导致数据权限/租户隔离失效的查询路径」；与 `DynamicDataServiceImpl` 构成平行查询事实来源，违反 §20.1 |
| B. NL2AST（**选定**） | LLM 按表单语义层生成 `FormDataPageQuery`（条件/排序/分页），经既有引擎执行 | 生成物落在三层白名单内，安全架构零新增攻防面；语义层现成，准确率上限高 |
| C. 独立 nl2query 模块 | 新建中介模块依赖 ai + form | **否决（本期）**。Phase 1 约 4 个类，撑不起三件套/changelog/BOM 的模块行政成本（§20.1）；触发条件见 §7.3 |

**模块落点**：`lambda-fusion-ai` 内新增子域包 `com.lambda.fusion.ai.dataquery`，`ai → form` 以 `optional=true` 依赖引入（契约 §4.1），form 缺失时优雅降级（工具不注册、启动不报错）。无循环依赖（form 不依赖 ai）。工具属 AI 能力放 ai 模块，查询引擎留在 form 模块——单一事实来源不动。

### 2.2 边界（本期不做）

- **不生成 SQL、不直连物理表执行**：`FormModel.tableName` 与物理列名维持服务端私有（LLM 上下文与工具出参均不出现），同 dynamic-form.md §4.3 攻击面声明；
- **不做跨表 JOIN 问数**：引擎本身主表查询 + 主从两段式（dynamic-form.md §4.4），AST 能力边界与引擎对齐，从表数据经 `pageSubRows` 工具后续按需补；
- **不做写路径**（create/update/delete 随对话执行）：见 §7.2，Phase 1.5 另立 cycle；
- **不做语义层二次建模**（指标/维度/同义词管理）：`FormModel` 即语义层，出现真实缺口再评估；
- **不做 schema 向量检索**：表单数量大时用两阶段对话（先列表单再查详情）承接，RAG 设施已备但不预埋（§20.1）；
- **不做 Phase 2 通用 SQL 分析**（`la_datasources` 非表单库表）：触发条件与红线见 §7.3，默认缓行；
- **仅支持 B 端登录态应用**：数据权限按操作人展开，C 端匿名发布应用（`/v1/ai/public/**`）无操作人语义，其可见性模型另立设计（§4.5 适用边界）。

## 3. 总体架构

```text
┌────────────── 对话运行时（chat-runtime.md，已有） ──────────────┐
│ ChatController(SSE) → ChatRunCoordinator → ChatRunInstance      │
│       → AgentFactory → HarnessAgent（AgentScope，ReAct 循环）    │
└──────────────────────────────┬──────────────────────────────────┘
                               │ 工具调用（ToolkitAssembler 装配）
                    ┌──────────▼───────────────────────────┐
                    │  com.lambda.fusion.ai.dataquery       │
                    │  FormDataQueryTool                    │
                    │   ├ list_queryable_forms              │
                    │   └ query_form_data(formKey, …)       │
                    │  FormSchemaDescriber（语义层文档+缓存）│
                    │  安全上下文桥接（§4.5，fail-closed）   │
                    └──────────┬───────────────────────────┘
                               │ FormDataPageQuery（AST）
┌──────────────────────────────▼──────────────────────────────────┐
│  lambda-fusion-form 动态数据引擎（已有，零改动语义）              │
│  FormModelProvider.require(formKey)      发布版模型/停用拒绝      │
│  QueryAstValidator.validate()            字段/操作符/排序白名单   │
│  TargetTableContract.validateAccess()    租户契约/表开放清单      │
│  DynamicDataServiceImpl.page()           拼参化 SQL + 分页钳 500  │
│    ├ 租户插件（TenantContextHolder）                             │
│    ├ 行级数据权限（permissions|{type} 标记 → 授权子查询）         │
│    └ 字段级角色权限（stripRoleHidden）                           │
└─────────────────────────────────────────────────────────────────┘
```

一次问答的执行序列：

1. 用户自然语言提问 → Agent 规划：先 `list_queryable_forms` 拿表单摘要（名称/formKey/字段清单/字典提示），选定目标表单；
2. Agent 取目标表单的 schema 文档（`FormSchemaDescriber`，缓存命中则零成本），把「上个月」「已审批」翻译为 AST 条件（字段标识 + 白名单操作符 + 值），调用 `query_form_data`；
3. 工具桥接安全上下文（§4.5）→ 引擎校验执行 → 校验失败时工具把业务异常转译为结构化提示（含可用字段/操作符清单）返回给 Agent，Agent 修正后重试（自纠错循环）；
4. 工具返回「总条数 + 本页行（label 表头）+ 合计行 + **已解析条件回显**」，Agent 组织回答；条件回显让用户核对「翻译是否正确」，把黑盒猜测变成可确认的翻译。

## 4. 详细设计

### 4.1 包结构与装配

```text
com.lambda.fusion.ai.dataquery
├── FormDataQueryTool        // @Component，两个 @Tool 方法（§4.2）
├── FormSchemaDescriber      // @Component，FormModel → LLM schema 文档（§4.3）
└── DataQueryExceptionTranslator  // FormBusinessException → 模型可读文本（§4.6）
```

- 装配收敛进 `AiConfigure` 新增嵌套 `DataQueryConfiguration`：`@ConditionalOnClass(FormModelProvider.class)` + `@ConditionalOnProperty(prefix = "lambda.fusion.ai.data-query", name = "enabled", matchIfMissing = false)`（契约 §3.3/§19）；不新建 AutoConfiguration 入口。
- 依赖：`ai → lambda-fusion-form`（optional）、`ai → lambda-fusion-dictionary`（optional，仅用于 schema 文档中的字典项「代码↔名称」清单；缺失时 schema 不含字典选项，Agent 遇到字典字段按值原样查询并在错误转译中自纠）。

### 4.2 工具定义

两个 `@Tool` 方法，**不标 `@AiTools`**（全局扫描），由 `ToolkitAssembler` 按应用绑定注册（§4.4），同 `KnowledgeRetrievalTool` 模式。

入参形式决策：现有工具的 `@ToolParam` 全部为基本类型（`KnowledgeRetrievalTool` 的 `String query, Integer limit` 等），AgentScope 对复杂嵌套类型的 schema 生成存在不确定性。据此**查询条件以 JSON 字符串入参**（工具内 Jackson 解析为 `List<AstCondition>`，解析失败按错误转译返回可修正提示），排序/分页为扁平基本参数：

```java
@Tool(name = "list_queryable_forms",
      description = "列出当前用户可查询的业务表单摘要…")
public String listQueryableForms(RuntimeContext context)

@Tool(name = "query_form_data",
      description = "按结构化条件查询表单数据。conditions 为 JSON 数组…")
public String queryFormData(
        RuntimeContext context,
        @ToolParam(name = "formKey", description = "表单标识，来自 list_queryable_forms") String formKey,
        @ToolParam(name = "conditions", required = false,
                   description = "条件 JSON 数组：[{\"field\":\"字段标识\",\"op\":\"eq|ne|like|in|between|ge|le|isNull|isNotNull\",\"value\":…}]") String conditionsJson,
        @ToolParam(name = "sortField", required = false) String sortField,
        @ToolParam(name = "sortAsc", required = false) Boolean sortAsc,
        @ToolParam(name = "pageNum", required = false) Integer pageNum)
```

- `RuntimeContext` 由 AgentScope 按类型注入（模型不可见、无法伪造，同 `CurrentUserQueryTool`），提供 userId/tenantId/appId。
- 工具内部组装 `FormDataPageQuery` 调 `DynamicDataService.page(model, query)`，返回裁剪后的 `FormDataPageView`（records/total/summaries + 条件回显）；行数由引擎 500 上限与本模块 `max-rows` 双重钳制。
- `list_queryable_forms` 仅返回绑定白名单内的已发布表单；单应用绑定表单过多时按 `schema-digest-max-forms` 截断并提示分批列出。

### 4.3 FormSchemaDescriber（语义层文档）

把 `FormModel` + 字典项编译为 LLM 友好的 schema 文档，是本方案准确率的核心杠杆：

- **只含业务语义，不含物理事实**：字段标识、中文名（label）、类型、可空性、字典项清单（代码↔名称，经 dictionary 模块）、该字段允许的操作符（取 `query.conf.op`，全集 `eq/ne/like/in/between/ge/le/isNull/isNotNull`）、日期字段格式说明；**不含** 表名、物理列名、审计列、`rolePerms` 配置细节。
- **白名单前过滤**：仅列 `queryable()` 字段进条件说明、`sortable()` 字段进排序说明；当前用户不可见字段（`rolePerms.visibleBy` 对桥接角色集为 false）不进文档——LLM 不知道的字段问不出来，从源头减少权限报错轮次。
- **缓存**：按 `(tenantId, formId, publishedVersion)` 缓存文档，随 `FormModelProvider.evictAfterCommit` 同步失效（同 `FormModelCache` 生命周期）；缓存的是字符串文档，无物理元数据驻留顾虑（同 TargetTableContract「物理元数据不入长期缓存」口径——此处缓存的是语义投影而非物理元数据）。

### 4.4 应用绑定与工具注册

- `AppEntity` 新增 `dataQueryFormKeys`（JSON 数组列，`mcp_server_ids`/`knowledge_base_ids` 同款），空 = 该应用不启用问数；应用保存校验 formKey 存在且已发布。
- `ToolkitAssembler.build()` 增注册分支：绑定非空且 `data-query.enabled` 时，构造 `FormDataQueryTool(app.getDataQueryFormKeys(), …)` 注册进 Toolkit，位于 `applyToolPolicy` 统一白黑名单**之前**（同知识工具），使应用级 allow/deny 对问数工具同样生效。
- 不提供「绕过应用绑定的全局开关」：数据访问面必须收敛到显式绑定，这是 §5 安全模型的组成。

### 4.5 安全上下文桥接（红线，实施第一优先验证项）

**问题**：工具运行在 Agent 异步线程，无 HTTP/Sa-Token 上下文。引擎三层防线对上下文的依赖经逐项核实为：

| 防线 | 上下文来源 | 后台线程现状（已核实源码） |
| --- | --- | --- |
| 租户插件 | `TenantContextHolder`（ThreadLocal） | 无上下文 → 走框架缺省策略；已有后台恢复先例 `AgentTaskScheduler.TenantAwareTask` |
| 行级数据权限 | `DataScopeInterceptor` 经 `DataScopeEvaluator.getOperator` 取 `LoginUser`：**Mapper 参数中显式携带的 `LoginUser` 优先**，否则 Sa-Token 回退；operator 为 null 时**跳过改写** → `permissions\|{type}` 标记保留为字面量，匹配不到任何行（意外 fail-closed，返回空集） | 直接调用结果为空集，不泄露但不可用 |
| 字段级角色 | `currentRoles()` → `AuthUtils.getUser()` → Sa-Token 会话；roles 为 null 时 `stripRoleHidden` **跳过过滤** | **fail-open，隐藏字段会泄露** —— 直接调用不可接受 |

**桥接方案**（原则：从已完成所有权校验的领域对象恢复上下文，契约 §5.1；fail-closed）。租户与身份恢复收敛到共享 run-as 通道 `ToolOperatorContext`（`com.lambda.fusion.ai.runtime.operator`，与控制台写工具、表单草稿工具共用，完整机制见 [ai-console-copilot.md](ai-console-copilot.md) §4.3）。**机制一的关键事实已静态审计确认**：项目实际使用 sa-token-core 1.46.0，其自带 `SaTokenContextMockUtil.setMockContext(SaRetGenericFunction<T>)/clearContext()` 线程级 mock 上下文与 `SaRequestForMock`（`headerMap` 为 public 字段，token 注入点）；`OperatorUtils.getLoginUser` 的解析链路为「上下文请求取 token → Redis 会话 → `loginUser` 属性」，绑定含 token 的 mock 请求后 `AuthUtils.getUser()` 端到端原生工作，token 失效即抛 `NotLoginException`（fail-closed 自动成立）。通道职责收敛为三件事：

1. **捕获（阶段边界）**：token 在**每个驱动 Run 的请求边界**（发消息/确认/挂起输入——均为刚通过鉴权的登录态请求）捕获并刷新，存独立内存登记表（runId 键控）；**不进 `RuntimeContext`、不随 `ChatRunSnapshot` 序列化、不落库/日志**（快照持久化为显式字段白名单 record，token 不在其列）。userId/tenantId 由既有 `RuntimeContext` 承载，无需另存；**角色与组织不快照**——执行时刻从绑定的会话读取（与人工操作员同源同语义）。
2. **绑定（工具方法体内）**：在工具方法体内窄作用域执行「Sa-Token 线程上下文绑定（带 token 的 mock 请求）+ `TenantContextHolder` 恢复」→ 调引擎 → finally 双清理。绑定必须在工具体内——`DynamicDataService.page()` 为纯同步调用，体内绑定与 AgentScope 的线程选择无关，天然规避 Reactor 线程跳跃。
3. **fail-closed 兜底**：绑定失败（会话失效/登出）→ 不调用引擎，直接返回结构化错误（「登录状态已失效，请重新操作」）。禁止以「系统路径跳过过滤」的语义执行用户代理查询——那是引擎为可信系统路径（`currentRoles()==null` 不限制）预留的语义，AI 路径不得借用。

**机制一下 form 引擎零改动**：会话绑定后 `currentRoles()`（→`stripRoleHidden`）、`DataScopeInterceptor` 的 `OperatorUtils.getOperator()` 回退、`fillAudit`/`backfillDataScope` 全部原生工作，`FormDataSecurityContext` holder **不再是本设计必需项**，降级为机制二（Sa-Token 线程绑定不可行时）的备件：届时字段级角色经 form 私有 holder 注入、角色经 `RemoteUserService`（Dubbo，参照 `CurrentUserQueryTool.loadRemoteUser` 模式）执行时回查。取舍由 PoC 结果决定。

**适用边界**：本能力依赖操作人登录会话（数据权限按操作人展开），**Phase 1 仅开放 B 端登录态应用**；C 端发布应用（`/v1/ai/public/**` 匿名访问）无操作人语义，其数据可见性模型需独立设计，不在本期范围。

**实施期验证项（PDCA Check）——已通过（2026-10-12）**：run-as 通道 `ToolOperatorContext` 已实现并接入对话运行时阶段边界，PoC 动态验证通过（身份回环、fail-closed 三态、线程池清理、租户三模式判据、数据权限标记改写与授权子查询），模块全量 505 测试无回归。实测语义与租户三模式判据（平台/代维/租户，跨租户越权拒绝）详见 [ai-console-copilot.md](ai-console-copilot.md) §4.3；本能力域的 `FormDataSecurityContext` holder 维持为机制二备件（机制一成立，不需启用）。

### 4.6 错误转译与自纠错

`FormBusinessException`（`FORM_QUERY_FIELD_FORBIDDEN` / `FORM_QUERY_OPERATOR_UNSUPPORTED` / `FORM_QUERY_CONDITION_LIMIT` 等）在工具内捕获，经 `DataQueryExceptionTranslator` 转为模型可读结构化文本：错在哪、可用字段/操作符是什么、修正示例。**不向 ReAct 循环抛异常**——异常会中断多轮工具循环，文本返回才能触发 Agent 自纠错（同 `KnowledgeRetrievalTool`「检索故障转为工具结果」先例）。自纠错轮次上限由运行时既有 `runtime.default-max-iters` 承担，不新增配置。该转译手法为工具域共享能力（控制台写工具等复用，见 [ai-console-copilot.md](ai-console-copilot.md) §4.5），实施时组件放共享位置并按域注解差异化文案。

### 4.7 结果呈现与条件透明化

- 工具返回 JSON：`{total, pageNum, pageSize, rows:[{label: value,…}], summaries, resolvedConditions:[{label, op, 展示值}]}`；`resolvedConditions` 是解析后的条件回显，Agent 必须在回答中呈现（提示词约束），供用户核对与口头修正。
- 行值裁剪：长文本截断（可配置上限）、二进制/文件字段只出文件名；数值保留原值不截断（同 `TargetTableContract.validateValue` 口径）。
- 超出 `max-rows` 的结果只返回首页 + 总数 + 建议语（「数据共 X 条，请加条件缩小范围」），不做全量倾倒。

## 5. 安全红线映射（契约 §16 对照）

| 风险 | 防线 | 本设计增量 |
| --- | --- | --- |
| 跨租户数据 | 租户插件 + `TenantContextHolder` 恢复 | 桥接经共享 `ToolOperatorContext` 通道（§4.5）；验收必测租户隔离（§8） |
| 绕过行级数据权限 | `permissions\|{type}` 标记 → 拦截器按 operator 展开 | 显式 `LoginUser` 参数通道；桥接不齐 fail-closed |
| 隐藏字段泄露 | `stripRoleHidden`（现状后台线程 fail-open） | 机制一：会话绑定后引擎原生过滤，零改动；机制二备件 holder + schema 文档预过滤（§4.3、§4.5） |
| 越权访问未开放表 | `FormModelProvider.require`（停用/未发布/`isMappableTable` 拒绝） | 零改动继承；应用绑定白名单再收一层 |
| 物理结构泄露 | 物理列名服务端私有 | schema 文档与工具出参均不含表名/列名 |
| 提示注入改查询语义 | AST 白名单（字段/操作符/值参数化） | 生成物再越界也只能撞白名单拒绝；无 SQL 面可供注入 |
| 拒绝服务（大结果/深分页） | 引擎 500 钳制 + 条件数上限 | `max-rows` 二次钳制；不做全量导出工具 |

## 6. 配置项

`AiProperties` 新增嵌套 `DataQuery`（前缀 `lambda.fusion.ai.data-query`，契约 §12）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 能力总开关（模块级条件装配 + ToolkitAssembler 注册双检查） |
| `max-rows` | `50` | 单次返回给 Agent 的行数上限（引擎 500 之内再钳） |
| `long-text-limit` | `200` | 行值长文本截断长度 |
| `schema-digest-max-forms` | `20` | `list_queryable_forms` 单次列出的表单上限 |

不新增：自纠错轮次（复用 `runtime.default-max-iters`）、表单白名单（应用绑定为事实来源）、审计（写路径引入时随 Phase 1.5 评估，读路径复用 logger 模块访问日志，不建新表）。

## 7. 分期

### 7.1 Phase 1：读路径（本设计主体）

§4 全部内容。交付判据：§8 验收场景全绿 + `mvn -pl lambda-fusion-ai test` 通过。

### 7.2 Phase 1.5：写路径（HITL）

`create_form_record` / `update_form_record` 工具，`@RequireConfirm` 强制人工确认（chat-runtime.md §4.3 既有确认流），引擎写入路径既有校验全量继承。前置条件：Phase 1 的桥接经真实租户验证；审计填充（`fillAudit` 走 `getSafeUsername` → 匿名）需并入 `FormDataSecurityContext` 通道评估。**写路径默认不随 Phase 1 发布**，需求出现再立项。

### 7.3 Phase 2：通用 SQL 分析（默认缓行）

面向 `la_datasources` 注册数据源（form 表单覆盖不到的分析库表）的真 SQL 生成。启用触发条件（同时满足才立项）：存在明确的跨库分析需求、且目标库确为全局分析库（不含 `tenant_id` 业务表）。红线（届时写入契约修订建议）：管理员显式表级白名单（默认全禁）、仅单条 SELECT、强制 LIMIT、只读连接 + 超时、`@RequireConfirm` + 应用级绑定双收口。若能力长出自建语义层/数据集/指标体系，按 §2.1 路线 C 拆独立模块并收编 ai→form 依赖。

## 8. 测试与验收场景

单元/切片测试（mock 引擎与远程服务，不依赖真实库；契约 §15：改动后 `mvn -pl lambda-fusion-ai test` 必须通过）：

1. **租户隔离**：租户 A 的 Run 桥接上下文查询，断言 SQL 租户条件与结果归属（引擎租户插件生效路径）；
2. **fail-closed**：快照缺角色 / 缺租户 / 缺 operator 任一场景，断言拒绝执行且不触碰引擎；
3. **字段级权限**：桥接角色不可见字段不出现在 schema 文档与结果行（holder 通道生效；无 holder 且无 Sa-Token 时引擎行为回归验证）；
4. **白名单**：越界字段/操作符/条件数超限 → 转译文本含可用清单；
5. **行级数据权限**：ParamMap 显式 `LoginUser` 时标记被展开为授权子查询（框架拦截器行为切片验证，§4.5 验证项）；
6. **降级**：form / dictionary 不在 classpath → 装配跳过、启动成功；
7. **自纠错**：非法字段条件 → 转译文本 → Agent 修正重试的脚本化用例（StubModel，同 `FusionRuntimeTest` 手法）。

生产验收（上线判据）：

- **评测集**：真实问法 30~50 条（含口语、错别字、相对时间、指代）作回归基线，schema 文档每次调整必跑；目标首轮解析率与两轮自纠错后解析率双指标记录；
- **条件透明化**：回答必含已解析条件回显，用户可据此口头修正；
- **审计**：AI 发起的查询可在日志平台按 Run 标识追溯（复用 logger，不建新表）。

## 9. 明确删除的设计

- **不做 SQL 生成器与 SQL 校验器**（JSqlParser 白名单那套）：路线 B 下无 SQL 面，校验器是多余因果（§4.5 第一性原理）；
- **不做独立 nl2query 模块**：触发条件见 §7.3，Phase 1 规模下纯属行政成本；
- **不做语义层建模 UI**（同义词/指标/维度管理）：FormModel 即语义层，出现真实缺口先做规则修订提案；
- **不做 schema 向量索引**：两阶段对话已覆盖当前量级，RAG 设施已备不预埋；
- **不做查询审计新表**：复用 logger 模块；
- **不向 LLM 暴露表名/物理列名**：无论 schema 文档、工具出参还是错误信息；
- **不提供绕过应用绑定的全局问数开关**。

## 10. 实施清单

| 项 | 内容 |
| --- | --- |
| `lambda-fusion-ai/pom.xml` | `lambda-fusion-form`、`lambda-fusion-dictionary` optional 依赖 |
| `com.lambda.fusion.ai.dataquery` | `FormDataQueryTool` / `FormSchemaDescriber` / `DataQueryExceptionTranslator`（转译组件为工具域共享位置，见 §4.6） |
| `com.lambda.fusion.ai.runtime.operator` | `ToolOperatorContext` 共享 run-as 通道（阶段边界 token 捕获 → 工具体内绑定 → 清理 → fail-closed；与控制台助手、表单草稿工具共用，见 [ai-console-copilot.md](ai-console-copilot.md) §4.3；本表仅登记依赖，组件归口在控制台文档实施清单） |
| `AiProperties` | `DataQuery` 嵌套配置（§6） |
| `AiConfigure` | 嵌套 `DataQueryConfiguration`（条件装配 + 降级） |
| `ToolkitAssembler` | 应用绑定注册分支（`applyToolPolicy` 之前） |
| `lambda-fusion-form` | **机制一（预期）：零改动**；备件（仅机制二）：`FormDataSecurityContext` holder + `currentRoles()` 通道（取舍由 PoC 决定，§4.5） |
| `AppEntity` + `lambda-ai-changelog.xml` | `data_query_form_keys` 列追加 changeSet（`mcp_server_ids` 同款；同步 `docs/sql/` 参考脚本，契约 §6.2） |
| 测试 | §8 清单 |
| 文档 | 本文档实施记录回填 + `lambda-fusion-ai/README.md` 能力表补行 |

工作量估：桥接与验证 1.5 天、工具与 schema 描述器 2 天、注册与配置 1 天、测试与评测集 1.5 天，合计约 6 人日；form 引擎侧改动控制在 `FormDataSecurityContext` 一个类 + `currentRoles()` 一处。

## 附录 A. 术语

| 术语 | 含义 |
| --- | --- |
| NL2Query / 智能问数 | 自然语言 → 结构化查询 AST → 引擎执行，区别于生成 SQL 的 text-to-SQL |
| AST | `FormDataPageQuery`：条件（字段标识 + 白名单操作符 + 值）/ 排序 / 分页 / 树过滤 |
| 三层白名单 | `QueryAstValidator` 的字段/操作符/排序校验，物理列名仅服务端解析 |
| 语义层文档 | `FormSchemaDescriber` 产出的 LLM 上下文：字段语义 + 字典 + 操作符，无物理元数据 |
| 桥接快照 | Run 阶段边界捕获的活跃 token（仅内存、runId 键控、不落库）；身份由 RuntimeContext 承载，角色/组织执行时从会话读取，不进快照 |
| run-as 通道 | `ToolOperatorContext`：token 捕获/刷新 + 工具体内 Sa-Token 会话绑定与租户恢复 + 清理，fail-closed，跨能力包共用 |
| fail-closed | 桥接上下文不完整时拒绝执行，绝不退化为无权限查询 |
| HITL | Human-in-the-loop，工具调用前人工确认（`@RequireConfirm`） |
