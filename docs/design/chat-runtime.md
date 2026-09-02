# 对话运行时设计

> 状态：当前唯一有效设计。本文取代原 `agui-integration.md`、`chat-run-resume.md`、
> `chat-run-agentscope-execution.md`、`chat-run-cluster.md` 和
> `chat-run-tool-confirm-context-recovery.md`。

## 1. 设计结论

一次业务对话请求对应一个 `ChatRun`，并在创建它的应用节点上执行到底。浏览器连接只是该
Run 的观察者：关闭浏览器、切换会话或 SSE 断开都不取消 Agent。浏览器重新打开后读取运行中
快照，并继续订阅原节点的本地事件。

本模块不实现应用节点之间的执行接管。执行节点不可用时，由用户显式停止/放弃旧 Run，再以新
`clientRequestId` 重试。AgentScope 的多节点能力属于底层 Agent 服务与状态存储能力，不等于
Lambda Fusion 业务 Run 的跨节点迁移协议。

HITL 暂停是一个窄例外：AgentScope 已经把 `ASKING` 状态持久化，旧阶段源流也已经结束。进程重启后，
用户显式提交 `/confirm` 时允许从 AgentState 与 Run 快照重建本地确认实例并开始下一阶段。这是恢复一个
已暂停的业务交互，不是接管仍在执行的模型或工具调用。

## 2. 单一事实来源

| 数据 | 唯一职责 | 生命周期 |
| --- | --- | --- |
| AgentScope AgentState / memory | Agent 执行上下文、模型工作记忆、ASKING/HITL 状态 | 由 AgentScope 管理 |
| `ai_chat_message` | 用户可见的产品对话历史、附件关联、权限查询和审计 | 长期保存 |
| `ai_chat_run` | 一次请求的业务身份、幂等键、四态终态和运行中 UI 投影 | Run 记录长期保存；快照仅 RUNNING |
| 当前 JVM 事件缓冲 | 把实时 AG-UI 事件送给当前订阅者，并消除“快照后、订阅前”的极短竞态 | 仅进程内；终态后短期释放 |
| 当前 JVM 阶段句柄 | 本地 stop、内存引导与容量释放；不是 Run 注册表或所有权记录 | 单个 Agent 源流及尾处理结束即释放 |

`ai_chat_message` 不是 AgentScope memory 的副本。前者服务产品历史、附件、租户权限、分页和审计；
后者服务模型执行，允许被裁剪、摘要或采用不同存储结构。两者不能互相替代。

`snapshot_json` 也不是第三份对话记录。它只保存尚未完成的助手气泡投影：已生成文本、推理文本、
工具展示状态和待确认工具。Run 进入 `COMPLETED`、`STOPPED` 或 `FAILED` 后，助手结果已经写入
`ai_chat_message`，必须清空快照。

## 3. 状态与执行边界

业务 Run 只有四个持久化状态：

- `RUNNING`：请求尚未进入终态，包括 AgentScope 正在等待人工确认的阶段；
- `COMPLETED`：正常完成；
- `STOPPED`：用户显式停止或放弃；
- `FAILED`：启动、执行或持久化失败。

不再复制 AgentScope 的 `ASKING` 状态为第二套业务状态。待确认信息通过运行中快照的
`pendingTools` 投影给前端；前端可以显示“待确认”，但不得把它当成服务端状态。

一个 Run 可以因 HITL 分成多个 `phaseNo`。`phaseNo` 只是确认命令的幂等/过期校验键，
`aguiRunId` 是当前阶段的 AG-UI 协议标识；二者都不表达节点所有权、租约或接管关系。

## 4. 正常链路

### 4.1 创建与执行

1. `POST /v1/ai/sessions/{sessionId}/chat` 以 `clientRequestId` 幂等创建 Run 和用户消息。
2. 当前应用节点预占一个本地容量许可，并在后台执行器构造本阶段 `ChatExecutionInstance`；句柄只服务
   本阶段的本地控制，通过 Harness `streamEvents()` 订阅细粒度 `AgentEvent`。
3. 上一 Run 业务终态后，其记忆尾部不阻塞下一 Run 启动（安全性依据见 workspace 存储设计第 4 节）。
3. 薄适配器把 `AgentEvent` 映射成 AgentScope 官方 `AguiEvent`。
4. 同一批 `AguiEvent` 同时送往前端和运行中快照投影器；禁止再维护一套独立的
   `AgentEvent -> SnapshotDelta` 解释逻辑。
5. 快照按时间间隔写入数据库。事件数量与数据库快照之间没有持久化水位协议。

### 4.2 浏览器断开与恢复

SSE 完成、超时、网络错误和浏览器取消只解除订阅，不调用 `agent.interrupt()`。

恢复顺序固定为：

1. 查询会话的业务消息历史；
2. 查询活动 Run 与 Run 详情；
3. 若 Run 已终态，重新加载 `ai_chat_message`；
4. 若 Run 仍为 `RUNNING`，调用
   `GET /v1/ai/sessions/{sessionId}/runs/{runId}/events`；
5. 服务端先用当前内存快照（同节点）或数据库快照合成 AG-UI 引导事件，再从内部本地游标继续
   发送实时事件。

内部游标只解决本 JVM 的快照/订阅竞态，不出现在 HTTP 参数、AG-UI JSON 或数据库字段中。
前端不保存、提交或解释事件序号。

若请求没有落到持有实例的节点，服务端只返回最后一次数据库快照并结束连接，不在该节点恢复
Agent、不伪造接管。部署层应对会话/Run 使用粘性路由；前端可做有限次数重连，随后提示用户放弃
并重试。

上述限制针对 `/events` 的自动恢复。若快照明确包含 `pendingTools`，用户随后显式调用 `/confirm`，
服务端可以按 4.3 节恢复已经暂停的 AgentScope 确认上下文。

### 4.3 HITL

AgentScope `REQUIRE_USER_CONFIRM` 是确认事实的来源：

1. 映射器输出标准 `RUN_FINISHED` interrupt；
2. 快照投影器从同一事件生成 `pendingTools`；
3. 数据库快照提交成功后才向订阅者发布 interrupt；
4. 每个 HITL 阶段源流排空后释放本地阶段句柄；`/confirm` 只在持久化快照确有 `pendingTools` 时，
   从 Run 快照与 AgentState 构造下一阶段实例；
5. 根据 `phaseNo`、快照工具 ID 与 AgentScope 当前 ASKING 工具三方校验；
6. 校验成功后进入下一 phase，ChatRun 持久化状态仍为 `RUNNING`。

这里需要“事实先于信号”，但不需要 Redis Stream、分布式序号或 fencing。单实例锁内先提交运行中
快照，再追加本地 interrupt 事件即可。

### 4.4 终结

终结事务负责：

- CAS 写入业务终态；
- 保存最终助手消息及工具展示记录；
- 清空 `snapshot_json`；
- 更新会话最后消息时间。

事务成功后在当前 JVM 发布一个 `RUN_FINISHED` 或 `RUN_ERROR`。终态事件不持久化；终态恢复直接
读取 Run 状态与业务消息，不依赖事件重放。

## 5. 停止、节点故障与重试

`POST /runs/{runId}/stop` 是用户显式放弃 Run 的业务操作：

- 请求落在执行节点：先提交 `STOPPED`，再协作式中断本地 Agent；
- 请求落在其他节点或原节点已失效：把数据库 Run 提交为 `STOPPED`，不发送远程 stop、不建立心跳或
  租约；若快照处于 HITL 暂停，可加载 AgentScope 持久化状态补写拒绝结果，但不恢复旧阶段或执行工具；
- 原节点若稍后恢复并提交结果，数据库终态 CAS 会拒绝覆盖 `STOPPED`。

“拒绝最终提交”不等于回滚外部世界。旧 Agent 在终态写入前已经成功调用的邮件、支付、工单等
外部工具副作用无法由 ChatRun 撤销。因此节点不可用时系统不得自动接管或自动重放；用户点击
“放弃并重试”是一次明确选择，副作用敏感工具仍应自行使用业务幂等键。

### 5.1 终结兜底与最小化终态

执行节点终结提交（助手消息、附件投影与终态 CAS）失败时按有限次数退避重试；重试耗尽或遇确定性
投影错误后，切换到最小化终态提交——不再执行消息与附件投影，仅把仍处非终态的 Run 提交为 `FAILED`
（错误信息携带终结失败原因），随后以更长退避持续重试直至数据库恢复收敛。任何路径都不得把 Run
永久留在 `RUNNING` 阻塞会话；投影缺失由产品侧以"失败 Run"呈现，助手消息与附件后续不做部分补齐。

最小化终态是数据行上的一次数据库 CAS，不表达节点所有权：没有心跳、租约、fencing 或节点发现，
兜底提交不推断"原节点已死"，只表达"该 Run 的业务终结已无法完整完成"。因此本机制不违反第 6 节的
多节点边界；截断瞬间的外部工具副作用仍由用户显式放弃并重试来收敛。绝对截止时间（`deadline_at`）
列暂不引入；若未来需要更早的会话解锁，可作为数据属性扩展，同样不属所有权协议。

## 6. 多节点部署边界

本设计允许部署多个 Lambda Fusion 节点，但每个 Run 仍是单节点执行：

- 负载均衡负责把首次请求分配到一个节点，并尽量对后续 Run 请求保持亲和；
- 数据库保证业务幂等、终态 CAS 和浏览器可见的最近快照；
- AgentScope 负责底层 Agent 状态、memory、工具确认与其自身支持的分布式组件；
- Lambda Fusion 不实现 owner、epoch、lease、heartbeat、fencing、节点发现、远程停止或故障接管；
  5.1 节的绝对截止时间兜底是数据属性加 CAS，不属上述任何一项。

本地阶段句柄和容量许可不是节点所有权协议。请求节点没有句柄时只能读取数据库快照，禁止据此把
`RUNNING` 判定为孤儿或改写为失败；是否进入终态只由显式停止、执行结果及数据库 CAS 决定。

显式 HITL 确认不属于故障接管：只有持久化快照存在待确认工具、AgentScope 状态仍为 `ASKING` 且用户主动
提交决策时，才允许在请求节点开始下一阶段。没有后台扫描器，也不会自动执行或重放工具。

进程崩溃后，运行中数据库记录不会被后台扫描器自动改写。用户看到恢复失败后可显式停止旧 Run，
再发起新请求。这是有意选择的产品语义，而不是待补齐的分布式协议。

## 7. AG-UI 复用方式

模块显式依赖 `agentscope-extensions-agui`，复用其：

- `AguiEvent` 协议模型；
- `AguiEventEncoder` / AgentScope JSON 编码；
- 标准文本、推理、工具、interrupt 与终态事件结构。

不直接套用完整 `AguiAgentAdapter`，原因是当前 AgentScope 2.0.0 适配器使用较粗粒度的
`Agent.stream()`，没有覆盖 Harness `streamEvents()` 的细粒度 HITL 事件；其默认 Spring SSE
生命周期还会在客户端断开时中断 Agent，也没有 Lambda Fusion 的租户/应用运行上下文和浏览器恢复
契约。这里保留的本地适配代码只能做这些明确差异，不得再次实现一套 AG-UI DTO、状态机或编码器。

前端继续只使用 TDesign 内置 AG-UI Adapter 解析增量事件。业务代码只观察 `chatRunId`、`phaseNo`、
终态和 interrupt，不做第二次文本/工具合并。

## 8. HTTP 与前端契约

| 接口 | 语义 |
| --- | --- |
| `POST /{id}/chat` | 幂等创建或查看同一请求的 Run，并打开事件流 |
| `GET /{id}/runs/active` | 返回唯一 `RUNNING` Run；没有则为空 |
| `GET /{id}/runs/{runId}` | 返回业务状态、phase 和待确认工具投影 |
| `GET /{id}/runs/{runId}/events` | 总是先引导当前运行中气泡，再尝试接本地实时事件；无查询参数 |
| `POST /{id}/runs/{runId}/confirm` | 提交当前 phase 的 HITL 决策；进程重启后可从持久化 ASKING 状态开始下一阶段 |
| `POST /{id}/runs/{runId}/stop` | 显式放弃 Run；非执行节点只提交业务终态 |

前端 API 类型只接受四个服务端状态。`AWAITING_CONFIRM`、`STOPPING` 等展示状态必须是组件内部派生
状态，不得混入服务端 `ChatRunStatus`。

前端实现入口固定为 `_lambda_fusion_web/packages/business/ai-ui/src`：`api/chat.ts` 维护上述 HTTP 类型，
`components/chat-panel.vue` 负责 TDesign AG-UI 流、页面恢复和显式放弃。恢复只做有限次重连；仍无法回到
原执行节点时显示“放弃本次运行”，停止成功后清空旧 Run 与旧 `clientRequestId`，下一次发送创建新 Run。

## 9. 明确删除的设计

以下能力不属于本模块需求，禁止重新引入：

- per-phase Redis Stream；
- Redis 与数据库一致的事件序号、快照水位；
- 数据库时间协调；
- owner + epoch + lease fencing；
- 面向持久化日志的 XREAD；
- Pub/Sub stop、心跳、节点发现与后台维护扫描；
- 自动跨节点接管或自动重放工具调用；
- 终态完整渲染快照；
- `AgentEvent` 到 AG-UI 和快照的两套平行解释器。

## 10. 验收场景

1. 正常流式文本、推理和工具卡片只由 TDesign AG-UI Adapter 合并。
2. 关闭浏览器不会中断 Agent；回到同一会话可先看到最近快照并继续增长。
3. HITL 页面刷新后仍显示待确认工具，重复确认由 `phaseNo` 幂等保护。
4. Run 完成后 `snapshot_json` 为空，历史从 `ai_chat_message` 完整显示。
5. SSE JSON 与 HTTP 都不暴露 `seq`、`bootstrapSeq`、`afterSeq`。
6. 请求落到非执行节点时不会恢复执行或远程停止；恢复有限失败后由前端提示显式放弃重试。
7. 用户停止后旧节点迟到的最终结果不能覆盖 `STOPPED`。
8. Agent 在 HITL 边界暂停后重启应用，确认接口仍能校验持久化 ASKING 状态并进入下一 phase。
