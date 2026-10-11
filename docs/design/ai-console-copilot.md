# 管理控制台 AI 助手（Console Copilot）设计

> 状态：run-as 通道与元数据只读工具（能力包①后端）已实施并通过 PoC 与模块全量测试（2026-10-12，见 §4.3）；前端悬浮容器与其余能力包未实施。
> 实施记录：`lambda-fusion-ai` 模块——`runtime/operator/ToolOperatorContext`（run-as 通道，含租户三模式判据）、`console/MetadataQueryTools`（list_forms / get_form_fields / list_datasources）、`AiConfigure.ConsoleConfiguration` 条件装配、`ChatRunCoordinator`/`ChatRunFinalizer` 阶段边界钩子、`ai → lambda-fusion-form` optional 依赖；`runtime/operator/ToolOperatorContextPocTest`（8）+ `console/MetadataQueryToolsTest`（6）+ `DataScopeMarkerPocTest`（5）。
> 相关阅读：[ai-form-data-query.md](ai-form-data-query.md)（智能问数，能力包之一）、[chat-runtime.md](chat-runtime.md)（Run 生命周期与 HITL 确认流）、[dynamic-form.md](dynamic-form.md)（表单草稿与发布边界）、`.rule/engineering-contract.md`（§3 自动配置、§5 多租户、§9 Service 边界、§12 配置、§15 测试、§16 安全红线、§19 特性开关、§20 最小充分设计）
> 技术基线：Java 21 / Spring Boot 4.1.1 / AgentScope Java 2.0 / Sa-Token（spring-boot4 starter）/ vben 5.6 + tdesign

## 1. 背景与定位

管理后台的日常操作（查配置、加菜单、建用户、配字典）分散在多个功能页，非技术管理员的学习成本高；AI 助手把这些操作收敛为一次对话。形态：**后台右下角全局悬浮对话框，可展开最大化**，全页面可达，随时唤起。

**定位**：统一入口的「管理控制台 Copilot」，按能力包渐进开放：

```text
能力包                     交互              风险
─────────────────────────────────────────────────
① 平台问答（文档/元数据）   只读              零
② 业务问数（见 ai-form-data-query.md）  只读     低（引擎白名单内）
③ 表单草稿（文本转表单）    写入但仅草稿      低（发布永远人工）
④ 运维写操作（菜单/字典/用户…） 即时生效写    分级（本文重点）
```

**核心范式（一句话）**：AI 提议 → 人确认（HITL 确认卡）→ 以当前登录者身份、经既有 Service 层执行。AI 不自主写，确认卡展示真实完整载荷。

行业参照：ServiceNow Now Assist、钉钉 AI 助理（管理后台）、飞书管理后台助手均为「全局对话框 + 动作确认」形态；「能执行动作的助手」是元数据平台 AI 化的第三步（问数 → 建应用 → 运维操作），非创新赌注。

## 2. 选型与边界

### 2.1 交互形态

| 形态 | 评价 |
| --- | --- |
| A. 后台全局悬浮组件（**选定**） | 右下角对话框 + 可展开最大化；全功能页可达，符合"随手问、随手办"的使用模式 |
| B. 独立 AI 应用页 | 已有 `published-ai-app` 页面承载 C 端发布应用；管理端操作要跨页跳转，打断工作流，仅作为降级入口保留 |
| C. 各功能页内嵌侧栏 | 上下文最贴合，但每页接入成本高；后续按需在个别页面加深（如表单设计器侧栏），不作为通用形态 |

前端落点：管理端应用（`web-tdesign`）新增全局悬浮容器，对话能力**直接复用既有共享包 `packages/business/ai-ui`（`@vben/ai-ui`）**——`ChatPanel`（会话/流式/AG-UI SSE 渲染）、`ChatSessionList` 已抽包，且管理端 `web-tdesign` 已经消费（`app.vue` 经 `provideAiContext` 注入请求客户端/API 基地址/token，与内置发布页共用）；HITL 协议前后端已打通：`@RequireConfirm` → `RequireUserConfirmEvent` → `RunFinished(interrupt)` → 前端确认卡（`pendingInterrupts`）→ `/confirm` 回传 `ConfirmResult` 恢复 Run，挂起输入卡（单选/多选/文本）与断线重连恢复（`run.pendingConfirm`/`pendingInputs`）均已实现。本设计新增的只有：悬浮容器（右下角 + 最大化）、确认卡的结构化载荷渲染（§4.4）、建议单与数据卡两类卡片。

### 2.2 写操作范式：HITL 三段式

| 范式 | 评价 |
| --- | --- |
| A. AI 自主执行写操作 | **否决**。权限升级通道 + 间接提示注入面（数据内容诱导 AI 授权），违反契约 §16 精神 |
| B. AI 提议 → 确认卡人工确认 → 以操作人身份执行（**选定**） | 确认卡展示真实完整载荷；权限校验按真实管理员语义走（§4.3）；审计可追溯（§4.6） |
| C. AI 只出建议单，人工去界面执行 | 用于最高风险域（角色/权限变更）的降级形态，不作为通用范式 |

### 2.3 边界（本期不做 / 永不）

- **永不**：AI 自主（无确认）执行任何即时生效写操作；AI 触发表单发布/启用/删除（工具层面不提供该能力，`FormDesignService` 的发布入口不经 AI）；绕过 Service 直调 Mapper（契约 §9.3）；角色/权限变更的 AI 执行（只出建议单）；
- **本期不做**：添加用户（初始密码生成/交付、强制首登改密等凭据设计单独 cycle，§7 C2）；跨域批量操作（一次确认一批跨类型变更）；对话内执行 SQL（问数走 AST，见 ai-form-data-query.md）；定时/无人值守的管理操作；
- 知识库语料不含 `.rule` 工程治理文档对外发布场景——控制台助手限内部管理员/开发者角色可见，语料范围在知识库侧另行收口。

## 3. 总体架构

```text
┌─────────────── 管理端（web-tdesign） ───────────────────────┐
│ 任意功能页                                                  │
│   ┌──────────────────────────────────────┐                 │
│   │ Copilot 悬浮组件（右下角，可最大化）  │                  │
│   │  会话流 / 流式回答 / 确认卡 / 建议单  │                  │
│   └──────────────┬───────────────────────┘                 │
└──────────────────┼──────────────────────────────────────────┘
                   │ AG-UI SSE（既有协议）
┌──────────────────▼──────────────────────────────────────────┐
│ 对话运行时（chat-runtime.md，零改动）                        │
│ ChatController → ChatRunCoordinator → HarnessAgent          │
│   Toolkit：能力包工具族（按角色与配置装配）                  │
└───┬──────────┬──────────┬──────────┬────────────────────────┘
    │          │          │          │
 ①问答包     ②问数包     ③草稿包    ④运维写包
 知识库RAG   FormData    FormDef    PlatformAdminTools
 元数据工具  QueryTool   Transfer   (user/menu/dict…)
    │          │          │        │
    │          │          │        │ run-as 通道（§4.3）+ 显式鉴权
    ▼          ▼          ▼        ▼
 RAG设施    form引擎    form设计器  authority / dict 等 Service 层
 （既有）   （既有）    （既有）   （权限注解语义的 Service 同源调用）
```

## 4. 详细设计

### 4.1 前端壳

- 复用 `@vben/ai-ui` 的 `ChatPanel`/`ChatSessionList` 作为对话内核，外包一层**悬浮容器**：悬浮球 → 点开对话框（默认 400px 级）→ 最大化展开为半屏/全屏面板；会话随登录用户持久（复用 `ChatSession`），跨页面不丢上下文；运行时上下文沿用 `web-tdesign` 已注入的 `provideAiContext`（同一请求客户端与 token）；
- 卡片增量（确认卡/输入卡已有，无需新建）：**确认卡结构化载荷渲染**——现有 `tool-call-card` 展示工具名与提示语，本设计按 `action` 渲染完整提交载荷（菜单树选择、字典类型选择等；最小集先支持通用键值对）、**建议单**（角色变更等只出建议的场景，渲染为只读清单 + 「去处理」跳转链接，复用 `tool-input-card` 的规格解析手法）、**数据卡**（问数结果，含条件回显，Phase 2 接图表意图）；
- 入口可见性按角色控制（B 端 audience + DEV/ADMIN，`AuthUtils` 既有 `ROLE_DEV` 口径），无角色用户不渲染悬浮球。

### 4.2 能力包矩阵与角色放权

| 能力包 | 工具族（示例） | 读/写 | 角色门槛 | 开放批次 |
| --- | --- | --- | --- | --- |
| 平台问答 | 知识库检索（RAG 既有） | 只读 | 登录即可 | C0 |
| 元数据问答 | `list_forms` / `get_form_fields` / `list_datasources`（**已实现**）· `list_dict_items` / `list_menus` / `list_roles`（待 authority-api 扩展） | 只读 | DEV/ADMIN（工具内门槛 + run-as fail-closed 双闸） | C0 |
| 业务问数 | `query_form_data` 等（ai-form-data-query.md） | 只读 | 随问数设计 | 问数 Phase 1 |
| 表单草稿 | `draft_form_from_text`（生成 `FormDefTransfer` → 导入路径建草稿） | 写（仅草稿） | DEV | C1 |
| 运维写·菜单/字典 | `create_menu` / `create_dict_type` / `create_dict_item` | 写 | ADMIN/DEV | C1（首个写域） |
| 运维写·用户 | `create_user` 等 | 写 | ADMIN | C2（凭据设计后） |
| 角色权限变更 | `suggest_role_change`（**只出建议单**） | 无写 | ADMIN | C1（仅建议形态，长期） |

放权实现：元数据工具为全局注册（`@AiTools` + `AiConfigure.ConsoleConfiguration` 条件装配），匿名对话因 run-as fail-closed 结构性免疫；写工具按应用绑定（`ToolkitAssembler` 既有机制）+ 工具内显式鉴权双闸（§4.5）；能力包配置开关（§6）。

### 4.3 run-as 通道（平台级共享组件，机制已静态审计确认）

**问题**：工具运行在 Agent 异步线程，无 Sa-Token 上下文。经源码核实：`AuthUtils.getUser()` → `OperatorUtils.getLoginUser` → Sa-Token 会话（`StpLogicUtils.getActiveStpLogic`），无上下文抛 `SaTokenContextException`（`getSafeOperator` 降级为匿名）；authority 管理端的权限校验（`@SaCheckPermission`，如 `authority:user:add`）与操作日志（`@OperationLog`）都在 **Controller 层**，Service 层无注解——工具若直调 Service，权限校验与操作日志会**双双旁路**。

**机制依据（静态审计已证，2026-10）**：项目实际使用 sa-token-core 1.46.0，本地仓库 jar 实测自带 `SaTokenContextMockUtil.setMockContext(SaRetGenericFunction<T>)/clearContext()`（线程级 mock 上下文，泛型版本即 run-as 包装器形态）、`SaTokenContextForThreadLocalStaff.modelBoxThreadLocal`（ThreadLocal 存储）、`SaRequestForMock`（`headerMap` 为 public 字段——token 注入点）。`OperatorUtils.getLoginUser` 解析链路为「上下文请求取 token → Redis 会话 → `loginUser` 属性」，绑定含 token 的 mock 请求后端到端原生工作。`DataScopeEvaluator.getOperator` 的 ParamMap 通道与 `parseReplaceDataScope` 的 SQL 标记解析（`'permissions|{type}'` 正则与 form 引擎手工标记精确匹配）亦经审计确认。

**方案**：ai 模块共享组件 `ToolOperatorContext`（`com.lambda.fusion.ai.runtime.operator`，**已实现**，2026-10-12），问数、草稿、运维写三个工具域共用，职责收敛为三件事：

1. **捕获（阶段边界）**：token 在**每个驱动 Run 的请求边界**捕获并刷新——已接入 `ChatRunCoordinator` 的 `start/confirm/submitInput/bootstrap` 四个入口（`captureFromCurrentRequest`，无 Web 上下文时静默跳过，由执行期 fail-closed 兜底），存独立内存登记表（runId 键控），Run 终结时在 `ChatRunFinalizer.applyResultAndPublish` 统一 `release`；**不进 `RuntimeContext`、不随 `ChatRunSnapshot` 序列化、不落库/日志/出参——契约 §16**。userId/tenantId 由既有 `RuntimeContext` 承载；**角色与组织不快照**——执行时刻从绑定的会话读取（与人工操作员同源同语义，「快照 vs 执行时」伪问题随「角色不存快照」一并消失）；
2. **绑定（工具方法体内）**：窄作用域执行「Sa-Token 线程上下文绑定（带 token 的 mock 请求，头名/前缀读全局配置）→ 会话内读操作人 → 租户三模式判据 → `TenantContextHolder` 恢复」→ 调 Service/引擎 → finally 双清理。绑定必须在工具体内——被调 Service/引擎为纯同步调用，体内绑定与 AgentScope 线程选择无关，规避 Reactor 线程跳跃；
3. **租户三模式判据**（权威依据 = 绑定会话中操作人自身的租户归属，不采信客户端输入）：显式目标租户 > 操作人自身归属 > 平台模式（无租户绑定，跨租户元数据读合法，契约 §5.1 受控平台流程）；**租户归属操作者指定其它租户 → 拒绝（30222）**——区分「合法无租户」与「意外丢租户」，防止静默降级为跨租户读；
4. **效果**：会话绑定后 `AuthUtils.checkPermission(code)`、审计字段填充（`MetaObjectHandler` → 真实操作人）、租户插件、`DataScopeInterceptor` 全部按真实操作员语义工作；登出/会话过期 → 绑定会话解析失败 → **fail-closed**（`NotLoginException`）；无上下文（清理后线程复用）→ `SaTokenContextException`（实测语义：上下文缺失即直接拒绝）；
5. **适用边界**：本通道依赖操作人登录会话——控制台助手天然满足（B 端登录态）；问数/草稿工具同此约束（见 ai-form-data-query.md §2.2/§4.5），C 端匿名发布应用不在覆盖范围（匿名 Run 无 token 捕获，工具执行 fail-closed，结构性免疫）；
6. **机制二降级预案（备而不用）**：若未来框架行为变化导致会话绑定失效，降级为「工具内经 `RemoteUserService`（Dubbo）执行时回查角色集显式鉴权 + 审计标注 AI 来源」；form 引擎此时需补 `FormDataSecurityContext` holder 备件（见 ai-form-data-query.md §4.5）。

**PoC 动态验证：已通过（2026-10-12，9/9 测试 + 模块全量 505 无回归）**。实测语义补充：① 数据权限授权子查询真实形态为 `SELECT DISTINCT id FROM la_datascopes WHERE (target_type='USER' AND tid=操作人) OR (target_type='ORG' AND tid=操作人组织) AND domain_type={type}`——操作人身份直接决定授权范围，会话绑定后 AI 与人工同语义；② form 手工标记 `'permissions|{type}'` 与 `DataScopeEvaluator` 正则精确匹配（原生支持路径）；③ 无上下文路径抛 `SaTokenContextException`（非 `NotLoginException`），同为 fail-closed。

### 4.4 HITL 确认卡协议

> 前后端协议链路已存在并打通（`chat-panel.vue` 的 `pendingInterrupts`/`/confirm` 回传/重连恢复；见 §2.1）——本节只新增「载荷结构约定」与「结构化渲染」。

- **载荷结构**（工具执行被 `@RequireConfirm` 拦截后生成，经既有 `RequireUserConfirmEvent → RunFinished(interrupt) → run.pendingConfirm` 下发）：`{action: "create_menu", title, payload: {…完整字段…}, permissionCode, riskLevel}`——载荷即最终提交体，确认后原样执行，不允许确认后再改写；
- **交互**：复用既有确认卡流——确认卡内联于对话流；确认 → 既有 `/confirm` 端点恢复 Run → 工具真正执行；取消 → 拒绝挂起工具调用（`denyPendingToolCalls` 既有机制）；
- **建议单**：`suggest_role_change` 返回结构化建议 + 界面跳转路径，不进入确认-执行流（新卡片，渲染手法复用 `tool-input-card` 的规格解析）；
- **结构化渲染增量**：现有 `tool-call-card` 展示工具名与提示语，本设计按 `action` 渲染完整载荷字段（菜单树选择、字典类型选择等），最小集先支持通用键值对渲染，域特定渲染随各写域补充。

### 4.5 写工具实现规范

1. **只经 Service 层**：工具调用与 Controller 同源的 Service 方法（如 user/menu 的 `AuthorityService` 族、dict 的 `DictTypeService`），服务端行为（校验、审计、租户）与人工操作完全一致——单一事实来源（契约 §20.3）；
2. **显式鉴权前置**：工具方法内先 `AuthUtils.checkPermission("authority:user:add")`（该 API 即为「非 MVC 调用的权限校验」设计，含 DEV 豁免口径），失败转译为结构化拒绝；权限码与对应 Controller 注解**逐字一致**，写工具的测试断言这一点（防止两边漂移）；
3. **操作日志**：工具方法标 `@OperationLog`（logger 模块既有 AOP），module/value 与人工操作同口径 + AI 标记；
4. **目标校验**：写前校验目标存在性/唯一性（如菜单父节点、字典类型编码存在），禁止工具内 for 循环无界批量——单次确认单动作；
5. **返回**：执行结果结构化返回（新对象 id + 关键字段），错误经 `DataQueryExceptionTranslator` 同款手法转译（组件泛化复用，更名为工具域共享）。

### 4.6 审计与追溯

- 人工操作与 AI 操作共用 `@OperationLog` 通道，AI 发起的操作 value 带 `[AI]` 前缀 + Run 标识，管理后台操作日志页可按标记筛选；
- 对话审计复用 chat 域既有消息/Run 存储；AI 写操作在操作日志里可反查会话（Run id 关联）。

## 5. 安全红线映射（契约 §16 对照）

| 风险 | 防线 |
| --- | --- |
| 越权写（低权用户借 AI 写管理面） | 双闸：入口角色门槛（§4.2）+ 工具内 `checkPermission`（§4.5），与 Controller 注解同码 |
| 提示注入诱导授权 | 确认卡人工确认 + 权限变更只出建议单 + 载荷即提交体不可篡改 |
| 绕过权限同步/审计 | 只经 Service 层 + `@OperationLog` + run-as 真实操作人语义；不新增旁路 |
| 会话/凭据泄露 | 快照 token 仅内存、禁止落库/日志；登出即失效（fail-closed） |
| 跨租户操作 | `TenantContextHolder` 恢复 + 租户插件；authority 管理流程按契约 §5.1 受控例外口径显式收窄目标租户 |
| 无界批量变更 | 单次确认单动作；无批量工具 |
| 审计不可追溯 | 操作日志 `[AI]` 标记 + Run id 关联会话 |

## 6. 配置项

`AiProperties` 新增嵌套 `ConsoleCopilot`（前缀 `lambda.fusion.ai.console-copilot`，契约 §12）：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关（条件装配 + 悬浮球渲染双检查） |
| `packs.metadata` / `packs.draft` / `packs.admin-write` | 逐包开关 | 能力包逐个开放，默认全关 |
| `admin-write.allowed-tools` | 空 | 写工具显式白名单（在逐包开关之上再收口） |
| `confirm-timeout-minutes` | `30` | 确认卡挂起超时，超时自动拒绝（Run 生命周期内） |

## 7. 分期与开放计划

- **C0（壳 + 问答）**：悬浮容器（包 `@vben/ai-ui` 的 `ChatPanel`）+ 会话复用 + 文档/元数据问答包。验收：管理员在任意页面唤起、问答可用。
- **C1（首个写域全链路）**：run-as 通道 PoC 动态验证与落地（§4.3 末「PoC 动态验证项」，静态依据已证）→ `create_menu` / `create_dict_type` / `create_dict_item` 打通「提议→确认→执行→审计」→ 表单草稿包（`FormDefTransfer` 导入路径）。角色变更建议单同批交付。此 cycle 跑通后，后续写域为边际成本。
- **C2（添加用户）**：初始密码生成策略（服务端生成 + 一次性展示 + 强制首登改密）、密码不出日志的验证、角色分配边界。独立小 cycle。
- **问数/图表**：独立推进（ai-form-data-query.md），就绪后作为能力包挂入。
- 每批开放 = 配置开关 + 白名单 + 角色门槛三重收口，先内部灰度再放开。

## 8. 测试与验收场景

单元/切片测试（契约 §15：`mvn -pl lambda-fusion-ai test`）：

1. **run-as 通道**：mock context 绑定后 `AuthUtils.getUser()` 返回会话真实身份（与人工登录同源）、登出后 fail-closed、执行后上下文清理（无泄漏到线程池复用线程）；token 不出现在任何持久化面（对 `ChatRunSnapshot` 序列化结果断言）；
2. **权限双闸一致性**：写工具的权限码与对应 Controller `@SaCheckPermission` 逐字一致（测试扫描断言）；无权限 → 结构化拒绝且未触达 Service；
3. **确认流**：确认前不执行；确认后载荷原样执行；取消走 `denyPendingToolCalls`；超时自动拒绝；
4. **审计**：`@OperationLog` 记录真实操作人 + `[AI]` 标记 + Run id；
5. **租户隔离**：A 租户管理员经 AI 创建的资源归属 A 租户（或按 authority 受控例外的目标租户口径）；
6. **降级**：authority/dict 不在 classpath → 对应工具族不注册、启动成功；
7. **敏感信息**：token 与密码不出现在任何日志/出参（断言日志捕获）。

生产验收：确认卡载荷与实际执行一致（抽查比对操作日志 payload）；建议单跳转路径可达；灰度期内 AI 写操作误操作率与人工操作基线对比记录。

## 9. 明确删除的设计

- **不做 AI 自主执行模式**（无确认直写）：永久排除，不做开关；
- **不做角色/权限变更执行**：长期只有建议单形态；
- **不做独立「AI 助手」新模块**：能力包都挂在 ai 模块子域（`runtime/operator`、`console`），复用对话运行时；
- **不做新对话协议**：复用 AG-UI 与 chat 域全部既有端点，确认卡只是新增一种前端渲染形态；
- **不做跨域批量操作工具**、不做对话内 SQL、不做 AI 触发发布；
- **不为每个功能页预埋侧栏接入点**：悬浮组件统一入口，页面级深化按需单议。

## 10. 实施清单

| 项 | 内容 |
| --- | --- |
| `com.lambda.fusion.ai.runtime.operator` | `ToolOperatorContext`（阶段边界 token 捕获/刷新 → 工具体内会话与租户绑定 → 清理 → fail-closed）——问数、草稿、写操作共用 |
| `com.lambda.fusion.ai.console` | 能力包工具族：`MetadataQueryTools`（只读）、`PlatformAdminTools`（写，逐域）、`RoleChangeSuggestionTool`（建议单） |
| `AiProperties` + `AiConfigure` | `ConsoleCopilot` 配置嵌套与条件装配（§6） |
| `ToolkitAssembler` | 能力包注册分支（按配置与角色门槛） |
| `web-tdesign` | Copilot 悬浮容器（外包 `@vben/ai-ui` ChatPanel；新增建议单/数据卡与确认卡结构化载荷渲染） |
| `packages/business/ai-ui` | **既有共享包直接复用**（ChatPanel/ChatSessionList/tool-call-card/tool-input-card，管理端已消费）；如需导出 tool 卡片或新增卡片，在包内扩展 |
| 测试 | §8 清单 |
| 文档 | 本文档实施记录回填 + README 能力表；问数文档桥接节已改为引用 `ToolOperatorContext` |

工作量估：run-as 通道（含 PoC 动态验证）2 天、问答包 1.5 天、C1 写域全链路 3.5 天、前端悬浮容器与卡片增量 2 天（对话内核整体复用 `@vben/ai-ui`，较初估下调）、测试与灰度 2 天；C0+C1 合计约 11 人日，C2 另计 2~3 人日。

## 附录 A. 术语

| 术语 | 含义 |
| --- | --- |
| 能力包 | 按功能域组织的一组工具 + 放权配置（问答/问数/草稿/运维写） |
| run-as 通道 | `ToolOperatorContext`：阶段边界捕获 token、工具体内绑定管理员会话与租户上下文、用后清理；角色/组织执行时从会话读，不存快照；fail-closed |
| 确认卡 | HITL 确认的前端形态：展示最终提交载荷，确认前不执行 |
| 建议单 | 只出结构化建议 + 界面跳转、不进入执行流的降级形态（角色/权限变更） |
| 双闸 | 入口角色门槛 + 工具内显式 `checkPermission`（权限码与 Controller 注解逐字一致） |
| `[AI]` 标记 | `@OperationLog` value 前缀 + Run 标识，标识 AI 发起的操作 |
