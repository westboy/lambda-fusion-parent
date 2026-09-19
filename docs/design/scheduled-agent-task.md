# 定时 Agent 任务设计（复用 agentscope-extensions-scheduler-quartz）

> 本文描述 `lambda-fusion-ai` 的「定时执行独立 AI Agent」能力设计：用户配置一个 Agent（提示词 / 模型 / 工具白名单）
> 与调度策略（CRON / 固定频率），到点自动触发该 Agent 执行一次（巡检、日报、数据汇总等），支持暂停 / 恢复 / 取消、
> 持久化与重启恢复。
>
> 调度引擎复用 AgentScope 官方扩展 `agentscope-extensions-scheduler-quartz`；Agent 定义复用现有 `ai_sub_agent` 表
> （加 `category` 分类）。本文不引入 `AgentFactory` / `HarnessAgent` / `ChatRunCoordinator` 这套 App 化会话运行时——
> 独立定时任务为无头一次性执行，用不上会话 / 状态持久化 / 多渠道路由，避免平行体系与过度设计（工程契约 §2、§20）。

## 1. 选型与边界

### 1.1 为什么是 Quartz 而不是 XXL-Job

| 维度 | Quartz | XXL-Job |
| :--- | :--- | :--- |
| 部署形态 | 内嵌 JVM，零外部中间件 | 需独立部署调度 admin 服务端 + 网络连接 |
| starter 契合 | 自洽，符合「产出 jar 供下游依赖」（宪章 §1） | 强加外部中间件，违背 starter 自洽 |
| 调度控制 | 全程序化：CRON / FIXED_RATE / FIXED_DELAY / 暂停 / 恢复 / 取消 / 中断 | 定时策略在 admin 控制台配置，`schedule()` 仅接受 `NONE` 模式 |
| 持久化 / 集群 | 标准 JDBC JobStore 支持 | 由 admin 服务端承担 |
| 初始输入 `Msg` | 支持（序列化进 JobDataMap） | 经 JobParam |

结论：选 Quartz，对应扩展模块 `agentscope-extensions-scheduler-quartz`。

### 1.2 为什么复用扩展而不是自己封装 Quartz

扩展 `agentscope-extensions-scheduler-quartz` 已提供完整闭环：

- `AgentScheduler`（接口）：`schedule / cancel / getScheduledAgent / getAllScheduleAgentTasks / shutdown`；
- `QuartzAgentScheduler`（实现）：把 `ScheduleConfig` 映射为 Quartz Job/Trigger（CRON → `CronTrigger`，FIXED_RATE → `SimpleTrigger`，FIXED_DELAY → 一次性触发 + 完成后手动 reschedule），并负责 JDBC JobStore 持久化、`pause/resume/interrupt/getStatus`；
- `AgentQuartzJob`：Quartz 触发时经 `QuartzAgentSchedulerRegistry`（JVM 静态注册表）找回 scheduler，`task.run().block()` 执行；
- `BaseScheduleAgentTask`：每次触发用 `RuntimeAgentConfig`（name + model + sysPrompt + toolkit + hooks）**新建一个干净的 `ReActAgent`**，调用 `agent.call(msgs)`，状态隔离。

因此本设计**组合复用**扩展，不重写其调度 / 持久化 / 生命周期；本仓库只需提供「任务定义 → `AgentConfig` 转换 + 启动重注册 + 租户上下文传递」这层薄胶水。

### 1.3 为什么不走 AgentFactory / HarnessAgent / ChatRunCoordinator

- 定时任务是**无头、一次性、独立**的 Agent：它不属于任何 `ai_app`，不需要会话（`la_ai_chat_run`）、不需要跨轮状态持久化、不需要多渠道（钉钉 / 飞书）路由。
- `AgentFactory.getOrBuild(appId, tenantId)` 构建的是 App 化的 `HarnessAgent`（带 modelResolver / 状态存储 / 网关注册 / 配置失效），对独立定时任务属于过度设计。
- 两者**不冲突、各司其职**：App 会话走 `ChatRunCoordinator`；定时任务走扩展的 `AgentScheduler`。复用同一套「模型解析 + 工具白名单」范式即可。

## 2. 架构

```text
                 ┌─────────────────────────── lambda-fusion-ai ───────────────────────────┐
                 │                                                                          │
   定时任务 CRUD  │   ai_sub_agent (category=SCHEDULED_TASK, 唯一事实来源, 含 tenant_id)      │
   (REST)        │        │                                                                 │
       │         │        ▼ SubAgentServiceImpl (按 category 分支)                           │
       ▼         │   AgentTaskScheduler ──组合──▶ AgentScheduler (QuartzAgentScheduler)      │
   SubAgent/     │        │ 实体→AgentConfig           │ schedule/pause/resume/cancel          │
   ScheduledTask │        ▼                            ▼                                     │
   Controller    │   RuntimeAgentConfig          org.quartz.Scheduler (JDBC JobStore)        │
                 │   + ScheduleConfig                  │ 到点触发                              │
                 │                                     ▼                                     │
                 │                          AgentQuartzJob (扩展现成)                         │
                 │                                     │ task.run().block()                  │
                 │              ┌──────────────────────┴──────────────────────┐             │
                 │              ▼ TenantContextHolder              ▼ 新建 ReActAgent          │
                 │   AgentTenantJobListener (DB 层租户)      BaseScheduleAgentTask           │
                 │   before:setTenant / after:clear          (agent.call(msg), tenantId     │
                 │                                            注入 Msg/sysPrompt)            │
                 └──────────────────────────────────────────────────────────────────────────┘
```

| 组件 | 职责 | 来源 |
| :--- | :--- | :--- |
| `AgentScheduler` / `QuartzAgentScheduler` | 调度、JDBC JobStore 持久化、暂停 / 恢复 / 取消 | 扩展（现成） |
| `AgentQuartzJob` | Quartz 触发时定位任务并 `task.run().block()` | 扩展（现成） |
| `BaseScheduleAgentTask` | 每次触发新建 `ReActAgent` 并执行 | 扩展（现成） |
| `AgentTaskScheduler`（新增） | 组合 `AgentScheduler`；实体 → `AgentConfig` 转换；CRUD 联动 | 本仓库 |
| `AgentTenantJobListener`（新增） | 触发前 / 后恢复与清理 `TenantContextHolder`（DB 层租户） | 本仓库 |
| `AgentTaskBootstrap`（新增） | 启动时扫描启用任务，重注册进调度（DB 为事实来源） | 本仓库 |
| `SubAgentServiceImpl`（扩展） | 定时任务 CRUD，按 `category` 分支 | 本仓库（改） |

## 3. 数据模型

**复用 `ai_sub_agent`**，不新建平行表（契约 §20 单一事实来源）。追加 changeSet 到
`META-INF/db/changelogs/lambda-ai-changelog.xml`（不改基线 changeSet，只追加，§6.1；同步 `docs/sql/` 参考脚本 §6.2）：

| 字段 | 说明 |
| :--- | :--- |
| `category` | `SUB_AGENT`（默认，兼容存量）/ `SCHEDULED_TASK` |
| `schedule_mode` | `NONE` / `CRON` / `FIXED_RATE` / `FIXED_DELAY`（仅 SCHEDULED_TASK 有效） |
| `cron_expression` / `fixed_rate` / `fixed_delay` / `initial_delay` / `zone_id` | 调度参数（毫秒 / cron / 时区） |
| `input_msg` | 可选初始输入（JSON），触发时作为 `Msg` 传入 |
| `schedule_enabled` | 调度态（区别于现有 `enabled` 路由态） |

复用现有列：`name`（Agent 名）、`prompt`（系统提示词）、`model_id`、`tools_allow`、`temperature`、`tenant_id`、审计字段。

### 3.1 关键护栏：路由污染防线

`ai_sub_agent.description` 注释为「**主 agent 路由唯一依据**」。复用同一表后，所有「查子代理给主 Agent 路由」的
现网入口（如 `SubAgentServiceImpl.listEnabledByIds` 及 Agent 构建 / 网关路由处）**必须加 `category=SUB_AGENT`
过滤**，否则定时任务记录会被主 Agent 当作可调用子代理路由走。这是本次改动风险最高处，需逐处排查。

## 4. 包结构与代码组织（契约 §10）

挂到现有 `com.lambda.fusion.ai.subagent` 子域，不新建平行分层：

- `model/`：新增 `ScheduledTaskQuery`（继承 `PageQuery<T>`）与 `Create/UpdateScheduledTask`（或复用
  Create/UpdateSubAgent 加调度字段，按最小改动取舍）。
- `service/SubAgentService` + `service/impl/SubAgentServiceImpl`：新增定时任务方法
  （`pageScheduledTasks / createScheduledTask / updateScheduledTask / pause / resume / cancel / triggerNow`），
  按 `category` 分支；沿用该 Service 现有风格（现状未继承 `AbstractCrudService`，契约现状优先，不强制迁移）。
- `controller/`：定时任务 REST 端点（独立 `ScheduledTaskController` 或并入 `SubAgentController`，倾向独立同子域）。
- 调度装配：`AgentTaskScheduler` / `AgentTenantJobListener` / `AgentTaskBootstrap` 放 `subagent` 子域或
  `runtime` 下合适的子包（就近归属，不集中到 `commons/`，§10.3）。

实体 ↔ VO 走 `ConverterResolver`（§8.1）；Controller 仅经 Service（§9.3）；分页收敛 Service（§8.2）。

## 5. 自动配置（契约 §3 三件套）

- `AiConfigure` 内嵌 `ScheduleConfiguration`（沿用模块「嵌套条件配置」模式，参考 `GatewayConfiguration`），
  开关 `@ConditionalOnProperty(prefix = "lambda.fusion.ai.schedule", name = "enabled", havingValue = "true")`（§19.1）。
- `AiProperties` 加 `schedule` 嵌套块：`enabled`、`threadPoolSize`（Quartz worker 线程数 = 并发 Agent 上限）、
  `misfireThreshold`、`clustered`、`schedulerId`（§12.1）。
- 注册 `AgentScheduler` Bean：注入 Spring 的 `Scheduler`（`spring-boot-starter-quartz` 装配，JDBC JobStore），
  `QuartzAgentScheduler.builder().scheduler(springScheduler).schedulerId(...).autoStart(false).build()`；
  `autoStart(false)` 因 Spring 自启。**destroy 时不调用其 `shutdown()`**（其内部 `scheduler.shutdown(true)`
  会误关共享 Scheduler）。

## 6. 调度与执行核心

### 6.1 实体 → AgentConfig 转换

- `ModelConfig`：由 `model_id` 经现有模型解析（`ModelResolver` / `LlmModelService`）得到
  `io.agentscope.core.model.Model`，包一层 `ModelConfig.createModel()` 返回它（复用 subagent 已有模型解析，不重造）。
- `RuntimeAgentConfig.builder().name(tenantId + ":" + name).modelConfig(mc).sysPrompt(prompt).toolkit(toolkit).build()`；
  `toolkit` 按 `tools_allow` 复用现有工具装配逻辑。
- `ScheduleConfig.builder().cron(...) / fixedRate(...) / fixedDelay(...).initialDelay(...).zoneId(...).build()`
  （注意：扩展 builder 用 `cron()/fixedRate()/fixedDelay()` 隐式设置 mode，**无** `scheduleMode()` 方法）。
- JobKey/name = `tenantId + ":" + task.name`（调度内唯一，租户隔离）；JobDataMap 存 `tenantId`、`taskId`、
  `inputMsgJson`（供 Listener 与执行用）。

### 6.2 租户双通道传递

定时任务在 Quartz 后台线程触发，**无 HTTP 请求、无 `TenantContextInterceptor`**，`TenantContextHolder`
（ThreadLocal）为空。任务的 `tenant_id` 一直在记录里，触发时手动恢复：

- **DB 层**：`AgentTenantJobListener implements JobListener`，`jobToBeExecuted` 从 JobDataMap 取 `tenantId` →
  `TenantContextHolder.setTenant(...)`；`jobWasExecuted` 清理（覆盖本地 Mapper / 租户插件直连场景，契约 §5.1
  后台任务例外）。**禁止**用 `@InterceptorIgnore` 或手工 Wrapper 绕过（§5.2）。
- **Agent 上下文层**：触发 / 构建时把 `tenantId`（及必要用户标识）注入 `Msg` / sysPrompt，供 LLM 感知、
  以及走 Dubbo 远程的工具（如 `CurrentUserQueryTool`）把 tenantId 当参数传（DB 层 ThreadLocal 对远程调用无效）。

### 6.3 生命周期与一致性

- **启动重注册**：`AgentTaskBootstrap`（参考 `ChatRunStartupRecovery` 模式）启动后扫描
  `category=SCHEDULED_TASK AND schedule_enabled=1`，逐个 `schedule`。扩展把 agent 定义存内存 map，重启后任务仅剩
  控制壳（stub model），故须以 DB 为唯一事实来源重建（§20.3）。
- **CRUD 联动**：定时任务增 / 改 / 删 / 启停 → 同步 `AgentTaskScheduler`；子代理（`category=SUB_AGENT`）走原有
  `publishChanged()` 重建主 Agent 缓存。调度状态只由 `AgentScheduler` 一个组件解释（§20.3 单一事实来源）。

## 7. 前置条件（编译通过的前提）

1. 在 `agentscope-java` 仓 `mvn install`（或 `-pl agentscope-extensions/agentscope-extensions-scheduler -am`）
   把 `agentscope-extensions-scheduler-common / -quartz` 发到本地仓库。
2. 版本对齐：扩展当前 `2.0.3-SNAPSHOT`，与 `_lambda_cloud_parent` 的 `agentscope.version=2.0.0` 不一致 →
   发与 fusion 对齐的稳定版并对齐该属性；核对 `agentscope-bom` 是否已含 scheduler 模块，否则在 cloud-parent
   dependencyManagement 显式托管（§1.1）。**模块 POM 不写版本**（§1.2）。
3. Quartz：用 `spring-boot-starter-quartz`（SB 4.1 托管版本），`spring.quartz.job-store-type=jdbc`；
   多数据源下显式指定主库（`dynamic-datasource` 路由歧义）。

## 8. 前端（`_web` 独立仓库）

前端在 `_web/packages/system-ui` 下，沿用现有 `views/ai/sub-agents/` 的范式（TDesign + Vben
`useVbenVxeGrid` 表格 + `useVbenForm` 表单弹窗）：

- 新增 `views/ai/scheduled-tasks/`：`index.vue`（页壳）+ `scheduled-task-table.vue`（列表 + 启停 / 暂停 / 恢复 /
  立即执行操作）+ `scheduled-task-form-dialog.vue`（表单，含调度字段）。
- `api/ai/scheduled-task.ts`：`ScheduledTask` 类型（含 `category`、`scheduleMode`、`cronExpression`、
  `fixedRate`、`fixedDelay`、`zoneId`、`inputMsg`、`scheduleEnabled`）与分页 / 增删改 / 启停 / 暂停 / 恢复 /
  立即执行 API（`/v1/ai/scheduled-tasks/...`），经 `api/ai/index.ts` 导出。
- `router/ai.ts`：注册 `/ai/scheduled-tasks`（如「定时任务」，icon `lucide:clock`），挂在「AI 工作台」children 下。
- 表单 Schema：基础信息（name / modelId / prompt / 工具白名单）+ 调度配置（scheduleMode RadioGroup、按 mode
  条件显隐 cron / fixedRate / fixedDelay / zoneId、inputMsg、scheduleEnabled Switch）。
- 提交规范（`_web` 仓库）：commit scope 用包名 `@vben/system-ui`（非功能域名）；oxlint 禁数组回调引用，
  `.map(fn)` 写 `.map((x) => fn(x))`（lefthook 拦截）。

## 9. 开放点（实现时就近取舍，不阻塞设计）

1. 定时任务 CRUD 端点独立 `ScheduledTaskController` 还是并入 `SubAgentController` —— 倾向独立同子域。
2. FIXED_DELAY 首期是否支持（Quartz 原生不支持，扩展靠手动 reschedule）—— 默认首期 NONE/CRON/FIXED_RATE。
3. 集群跨节点任务同步 —— 本期单机 JDBC JobStore 即可，`clustered` 作配置项预留，不做额外 Dubbo 广播（§20 不预埋）。

## 10. 执行记录

定时任务执行后须「看得见」：每次执行（**定时 + 手动都记**）落一条记录，含任务、触发方式、状态、起止时间、
耗时、错误信息、最终输出文本；前端在任务行内点「执行记录」查抽屉明细。简版定位：只记**结果 + 状态**，不记
ReAct 过程明细；Java/API 字段 `output` 的全文存入数据库列 `result_output`，避开 JSqlParser 的 `OUTPUT` 关键字。

### 10.1 数据模型 `ai_scheduled_task_log`

新建平行表（不复用 `ai_sub_agent`，记录是「调度器之外的落库事实」，§20.3 不与触发态双写冲突）。该表是
跨租户可见的运维观测数据，不保存 `tenant_id` 和 BaseEntity 审计列；租户内查询必须先通过 `ai_sub_agent`
完成任务所有权校验，再按全局唯一 `task_id` 查询。Liquibase 保留初始 changeSet `lambda-ai-202608200012`，
并通过后续 changeSet 去租户/审计列、在表被手工删除时重建最终结构，以及把旧 `output` 列迁移为
`result_output`（§6.1 禁止改写已执行 changeSet）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | varchar(32) PK | 雪花 id |
| `task_id` | varchar(32) NN | 关联 `ai_sub_agent.id` |
| `task_name` | varchar(128) | 任务名快照（删任务后仍可读） |
| `trigger_type` | varchar(16) NN | `SCHEDULED`（定时）/ `MANUAL`（手动） |
| `status` | varchar(16) NN | `SUCCESS` / `FAILED` |
| `result_output` | longtext | Agent 最终输出全文（Java/API 字段仍为 `output`；定时路径暂为空，见开放点） |
| `error_message` | varchar(1024) | 失败信息（成功为空，截断 1024） |
| `duration_ms` | bigint | 耗时毫秒 |
| `started_at` / `finished_at` | datetime | 起止时间 |

索引：`idx_ai_scheduled_task_log_task`(task_id, started_at)、`idx_ai_scheduled_task_log_status`(status, started_at)。
§6.2 同步：ai 域无对应 `docs/sql/la_*.sql` 参考脚本，不同步。

### 10.2 双路径埋点

两条触发路径在**不同线程、不同收口点**，须分别埋点：

- **手动路径**（经 HTTP）：收口在 `AgentTaskScheduler.runOnce()` 的 try/catch——同步 `.block()` 拿到结果
  `Msg`，成功取 `Msg.getTextContent()` 记 SUCCESS，异常记 FAILED + error_message；记 started/finished/duration。
  此处线程带 HTTP 租户上下文。
- **定时路径**（Quartz worker 线程，**不经** `TenantAwareTask`）：扩展的 `AgentQuartzJob` 在 jar 内、拿不到返回
  `Msg`，只能经全局 Quartz `JobListener` 观测。新增 `AgentExecutionJobListener implements JobListener`，注册到共享
  `Scheduler`（`AiConfigure.ScheduleConfiguration` 里 `scheduler.getListenerManager().addJobListener(...)`）：
  - `jobToBeExecuted`：按 JobDataMap 的 `taskName`（`tenantId:name`）解析 tenantId → `TenantContextHolder.setTenant(...)`
    （**顺带补上** §6.2 中 `TenantAwareTask` 未覆盖定时路径的 DB 层租户缺口）；
  - `jobWasExecuted`：按 `jobException` 判成败、`context.getFireTime()`/`getJobRunTime()` 取起止与耗时写记录，
    finally 清理租户上下文；`taskId` 由 (tenantId, category=SCHEDULED_TASK, name) 反查 `SubAgentMapper`。

两处都调 `ScheduledTaskLogService.record(...)`。执行记录表本身无 `tenant_id`，其 Mapper 明确跳过租户行插件；
Quartz 恢复的租户上下文仍用于执行期间访问 `ai_sub_agent` 等租户业务表。落库异常只告警，不反向影响执行结果。

### 10.3 查询与前端入口

- 查询 API：`ScheduledTaskController` 加 `GET /v1/ai/scheduled-tasks/{id}/logs/page`，按 `task_id` 分页
  （`ScheduledTaskLogPage extends PageQuery`，复用 §8.2 范式）。Controller 路径先调用 `requireTask(id)`，由
  `ai_sub_agent` 的租户过滤完成所有权校验，再以全局唯一 `task_id` 查询执行记录；不得绕过该校验直接暴露日志分页。
- 前端：`api/ai/scheduled-task.ts` 加 `ScheduledTaskLog` 类型 + `pageScheduledTaskLogsApi`；`scheduled-task-table.vue`
  操作列加「执行记录」链接 → 打开 `scheduled-task-log-drawer.vue`（TDesign Drawer + `useVbenVxeGrid`：状态 Tag
  SUCCESS 绿/FAILED 红、触发方式、起止时间、耗时、错误；行点「详情」开二层抽屉看 output 全文）。

### 10.4 开放点

- **定时路径 `output` 为空**：Quartz `Job` 不回传 Agent 结果 `Msg`，`JobListener` 只能拿到状态/耗时/错误。首期定时
  路径只记状态 + 错误，手动路径记全文。后续如需定时也记 output，用 `RuntimeAgentConfig.hooks` 注入 AgentScope Hook
  在执行内捕获最终 `Msg`（两条路径通用）。

## 11. 验证

- 前置：先发 scheduler 并对齐 `agentscope.version`，确保 `_lambda_cloud_parent` 已 install（§1.3），
  `mvn -pl lambda-fusion-ai -am clean install` 能解析。
- 静态检查：`mvn compile`（Spotless Palantir + SpotBugs；ai 模块 spotbugs.skip=true，§13）。
- 测试：随 `mvn -pl lambda-fusion-ai test`（§15）。
- 端到端：startup（20005）配 `lambda.fusion.ai.schedule.enabled=true` + 主库 + Quartz JDBC；建一条
  `category=SCHEDULED_TASK`、`schedule_enabled=1`、FIXED_RATE（如 30s）记录，观察：按租户名注册进 Quartz、
  到点触发、JobListener 恢复 `TenantContextHolder`、新建 ReActAgent 执行；重启后从 DB 重注册恢复；
  `listEnabledByIds` 不含定时任务（路由未被污染）。
