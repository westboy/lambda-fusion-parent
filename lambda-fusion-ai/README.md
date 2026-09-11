# Lambda Fusion AI

`lambda-fusion-ai` 是 Lambda Fusion 体系的 AI 能力模块，基于 **AgentScope 2.0** 构建，为 Spring Boot 应用提供多智能体应用托管能力：平台（ISV）预置智能体应用，每个应用绑定模型、系统提示词、工具、MCP 服务、知识库与子代理，对外提供运营商隔离的流式对话；WORKSPACE 型应用进一步对齐 AgentScope harness 完整能力（workspace / 技能 / 子代理 / 记忆 / 沙箱 / 自演化）。此外提供应用发布（独立 URL 分发）、聊天附件（上传/预览/按需读取）、HITL 人机协同（工具确认/挂起输入）、定时 Agent 任务与多实例配置失效广播。

> 深度设计文档见 `docs/design/`：[对话运行时](../docs/design/chat-runtime.md)、[应用发布](../docs/design/ai-app-publishing.md)、[媒体生成](../docs/design/media-generation-architecture.md)、[定时 Agent 任务](../docs/design/scheduled-agent-task.md)、[RAG 文档切割](../docs/design/rag-document-chunking.md)、[子代理远程恢复](../docs/design/subagent-remote-agent.md)、[Workspace 存储](../docs/design/agentscope-workspace-storage.md)。本 README 是概览。

## 能力总览

| 能力 | 包 | 端点 | 说明 |
| --- | --- | --- | --- |
| LLM 提供方/模型 | `llm` | `/v1/ai/llm-providers` `/v1/ai/llm-models` | DB 驱动，API Key AES-GCM 加密；使用 AgentScope Provider SPI |
| 智能应用 | `apps` | `/v1/ai/apps` `/v1/ai/apps/available` | CHAT / WORKSPACE 两型，平台全局 |
| 应用发布 | `apps` | `/v1/ai/apps/{id}/publication` `/v1/ai/public/apps` | publishCode 独立 URL 分发，匿名资料页 |
| 对话会话 | `chat` | `/v1/ai/sessions` | SSE 流式聊天 + Run 生命周期 + HITL 确认 |
| 聊天附件 | `chat.attachment` | `/v1/ai/chat/attachments` | 图片/文档上传、签名预览、按需读取工具、孤儿回收 |
| MCP 服务 | `mcp` | `/v1/ai/mcp-servers` | stdio/sse/http/streamable_http |
| 知识库 RAG | `rag` | `/v1/ai/knowledge-bases` | 文档切块入库 + 对话检索注入（GENERIC/AGENTIC/BOTH） |
| 子代理 | `subagent` | `/v1/ai/sub-agents` | DB 驱动子代理定义，主 agent 按 description 自主路由 |
| 定时任务 | `schedule` | `/v1/ai/scheduled-tasks` | Quartz 集群调度无头 Agent 执行 + 站内信通知 |
| 技能市场 | `skill` | `/v1/ai/skills` | MYSQL/POSTGRES/GIT/NACOS 仓库源 + 技能包导入 |
| 通道配置 | `channel` | `/v1/ai/channel-configs` | 外部通道→agent 路由绑定（DB 管理） |
| Gateway/通道适配器 | `runtime.gateway` | `POST /v1/ai/outbound/send` | HarnessGateway + 钉钉/飞书/企微适配器（条件装配） |
| 沙箱 | `runtime.sandbox` | — | HOST/Docker/K8s/E2B/Daytona/AgentRun |
| 状态存储 | `runtime.state` | — | MEMORY/FILE/MYSQL/POSTGRES/REDIS/OSS/COS |
| Workspace | `runtime.workspace` | `/v1/ai/apps/{id}/workspace/*` | 脚手架/文件管理/自演化审计 |
| API Key 加密 | `security` | — | AES-GCM |

> 能力表（app/provider/model/mcp/sub_agent/channel_config/knowledge_base）为平台级全局资源（无 `tenant_id`）；运营商隔离发生在会话层（session/message/run/attachment/audit 按 `tenant_id`）与运行时（agent 实例 + 工具按用户 tenant 查业务数据）。应用按 `audience`(B/C/ALL)+角色 或 `ownerId` 控制可见性。

## 应用分型

应用分两型，由 `appType` 决定能力栈（创建后不可变）：

| appType | workspace | 文件工具 | 技能/子代理 | 自演化 | 沙箱(shell) |
| --- | --- | --- | --- | --- | --- |
| `CHAT` | ✗ | ✗ | ✗ | ✗ | ✗ |
| `WORKSPACE` + `selfEvolve=false`（ASSISTANT） | ✓ 平台管理 | ✗ | ✓ | ✗ | 按 sandboxBackend |
| `WORKSPACE` + `selfEvolve=true`（AUTONOMOUS） | ✓ agent 可写 | ✓ | ✓ | ✓ | 按 sandboxBackend |

`sandboxBackend`（WORKSPACE 型）决定执行隔离：`HOST`（宿主，无 shell）/ `DOCKER` / `KUBERNETES` / `E2B` / `DAYTONA` / `AGENTRUN`（后 5 个启用 shell；扩展未安装时回退 HOST）。多轮上下文由 Agent 状态存储维持（所有 appType 共用，按 `state-store.type` 配置）。应用另有终端用户上传开关 `enableImageUpload` / `enableFileUpload`（默认开），控制会话附件上传入口。

## 架构

```
请求 ──▶ ChatController(SSE) ──▶ ChatServiceImpl ──▶ ChatRunService(归属校验) ──▶ ChatExecutionService
          ├─ ChatRunStateService     Run 状态机（CAS + REQUIRES_NEW）
          ├─ ChatRunEventStore       当前进程短期回放与实时订阅（bootstrap + SSE 心跳）
          ├─ ChatExecutionCapacityLimiter  全局/每用户并发许可（仅本节点）
          └─ ChatExecutionInstanceFactory
               └─ ChatExecutionInstance  单 Run 执行实例（实例锁 + 阶段推进 + 快照检查点）
                    ├─ AgentExecutionAdapter     RuntimeContext 构造 / HITL 阶段边界截断
                    ├─ AgentStreamLifecycle      源流订阅 + 交互超时 + 强制 dispose
                    ├─ ChatExecutionSnapshotBuilder → snapshot_json（延迟检查点落库）
                    ├─ ChatExecutionFinalizer    终态提交（锁外执行，DB 行锁串行）
                    └─ AgentFactory              按 (app,tenant,profile) 构建+缓存 HarnessAgent
                          ├─ ModelResolver / EmbeddingModelResolver   DB modelId -> AgentScope Model
                          ├─ ToolkitAssembler        本地 @AiTools + 媒体绑定 + MCP + ChatRun 专属工具
                          ├─ SubAgentDeclarationMapper  子代理 DB 声明 -> SubagentDeclaration
                          ├─ SandboxSpecResolver     沙箱后端 spec
                          └─ RagMiddleware / KnowledgeRetrievalTool  检索注入（条件挂载）
          └─ AgentEventMapper: AgentEvent → AG-UI 事件 → SSE

配置变更 ──▶ ConfigChangedEvent ──▶ DubboConfigInvalidationBroadcaster ──(Dubbo broadcast)──▶ 各实例 AgentFactory 缓存失效

定时触发 ──▶ Quartz(JDBC JobStore, 集群) ──▶ AgentTaskScheduler ──▶ 一次性 ReActAgent 执行 ──▶ 执行日志 + 站内信通知

外部通道（钉钉/飞书/企微/自定义 Channel Bean）──▶ ChannelManager ──▶ HarnessGateway ──▶ LF agent
```

## 代码结构

主包：`src/main/java/com/lambda/fusion/ai`

| 包 | 作用 |
| --- | --- |
| `llm` | LLM 提供方与模型管理（DB 驱动，API Key 加密） |
| `apps` | 智能应用 CRUD + 发布/下线 + 配置审计 + workspace 文件管理 API |
| `chat` | 对话会话、SSE 流式聊天、Run 生命周期、主动出站 |
| `chat.attachment` | 聊天附件：上传/消息组装/按需读取工具/预览签名/孤儿回收/工作区产物发布 |
| `chat.runtime` | 对话执行运行时：`ChatExecutionService` / `ChatExecutionInstance(Factory)` / `ChatExecutionFinalizer` / `AgentExecutionAdapter` / `AgentStreamLifecycle` / `ChatExecutionCapacityLimiter` / 快照 / AG-UI 编码 / HITL 校验器 |
| `mcp` | MCP 服务管理 |
| `rag` | 知识库管理 + 文档切块入库 + 检索注入（中间件/工具）+ 原文件存储 |
| `subagent` | 子代理定义管理（DB 驱动，与定时任务共用表） |
| `schedule` | 定时 Agent 任务：Quartz 调度 + 执行日志 + 站内信通知 |
| `skill` | 技能市场管理（仓库源 MYSQL/POSTGRES/GIT/NACOS + 技能包检查/导入） |
| `channel` | 通道路由配置管理（DB 驱动，channelId→agent 绑定） |
| `runtime` | AgentScope 集成：`AgentFactory` / `ModelResolver` / `EmbeddingModelResolver` / `ToolkitAssembler` / `SubAgentDeclarationMapper` |
| `runtime.gateway` | Gateway/通道：`ChannelLifecycle` / `ChannelBootstrap` / `ChannelConfigApplier` / `RuntimeProperty` / `FusionSubagentGateway` |
| `runtime.sandbox` | 沙箱后端解析：`SandboxSpecResolver` / `SandboxBackendProvider` |
| `runtime.state` | Agent 状态存储：`StateStoreProvider` / `StateStoreDataSources` |
| `runtime.workspace` | Workspace 路径/脚手架/文件服务/自演化审计 |
| `runtime.event` | `ConfigChangedEvent` + Dubbo 多实例缓存失效广播 |
| `security` | API Key 加解密（AES-GCM） |

各扩展后端（沙箱/状态存储/技能仓库/通道适配器/定时任务等）的条件装配以 `AiConfigure` 的嵌套静态配置类收敛：每个后端一个 `@ConditionalOnClass` 嵌套类，扩展 jar 缺席时对应类不加载、优雅降级。

## 核心链路

- **Agent 构建**：`AgentFactory.getOrBuild(appId, tenantId, profile)` 按 `appType` 分支（CHAT_RUN 与 CHANNEL 双运行场景，Channel Agent 缓存键带 `-channel` 后缀）；CHAT 关闭 workspace/子代理能力，WORKSPACE 开启（AGENTS.md/技能/子代理/记忆）+ 按 `sandboxBackend` 选文件系统 spec；`selfEvolve` 决定只读/可写。RAG 按 `ai_app.rag_mode`（GENERIC/AGENTIC/BOTH）挂中间件或注册工具。配置变更经 `ConfigChangedEvent` 失效缓存；多实例下经 Dubbo broadcast 扩散到各节点（`cluster.invalidation-broadcast`）。被淘汰的 HarnessAgent 延迟到在途 Run 终态后才 close。
- **流式对话**：`ChatServiceImpl` 是唯一 SSE 入口——只订阅 Run 事件，浏览器断开仅解除订阅，后台 Run 继续执行。`ChatExecutionService` 以 DB Run 为业务状态与并发裁决的唯一来源，JVM 只保留本地阶段句柄；`ChatExecutionInstance` 在实例锁内处理事件回调与 HITL 交互，**终态提交移到锁外**（DB 行锁保证串行；提交失败有限重试，耗尽降级为最小失败终态），快照检查点（`snapshot_json`，默认 15s 间隔）周期落库供断线恢复。Agent 事件经 `AgentEventMapper` 转成 AG-UI SSE。
- **HITL 人机协同**：两类机制——① 工具执行前确认（`@RequireConfirm`/`@AiTools(requireConfirm=true)`），② 挂起式提问（`UserInteractionTools` 的 `ask_single_choice`/`ask_multi_choice`/`ask_text_input` 抛 `ToolSuspendException` 挂起 Agent）。Agent 挂起时 ChatRun 仍为 RUNNING，待交互体现在快照 `pendingTools`/`pendingInputs`；用户经 confirm/input 端点提交，`ConfirmationValidator`/`ToolInputValidator` 校验三方 ID 一致后以 CAS 推进下一阶段。
- **会话标识**：内部 ChatRun 的 `RuntimeContext.sessionId` 直接使用 `ChatSession.id`；`runId` 只标识一次业务回合。`gw-*` 只属于外部 Channel 的 Gateway 路由路径。
- **外部通道**：内置钉钉/飞书/企微适配器（`AiConfigure.ChannelAdapterConfig`，各 `@ConditionalOnClass`）+ 下游自定义 `Channel` Bean，`ChannelLifecycle` 自动注册到 `ChannelManager` 并经共享 `HarnessGateway` 路由到 LF agent（靠 `preferredAgentId` 或 `ChannelConfig.defaultAgentId` 指定 `app:{appId}:t:{tenantId}`）。通道→agent 的绑定由 `channel` 包的 `ai_channel_config` 表管理。
- **主动出站**：`POST /v1/ai/outbound/send`（`{sessionId, messages}` 回推会话最近入站通道，或 `{channelId, to, messages}` 显式投递）。内部 SSE 通道为请求/响应模型，不支持 proactive push 到 Web 端。
- **定时任务**：Quartz JDBC JobStore（支持集群）调度，每次触发**新建一次性 ReActAgent 无头执行**（不经 App 会话运行时），执行结束写日志表并经 Dubbo 调 message 模块给任务创建人发站内信。
- **自演化审计**：selfEvolve 应用每轮对话后，`WorkspaceAuditRecorder` 扫描 workspace 中本轮变更文件，复制快照写入 `ai_app_workspace_audit`。

## 能力详解

### LLM 提供方与模型
提供方类型 `dashscope`/`openai`/`ollama`/`anthropic`/`gemini`/`deepseek`/`glm`/`kimi`/`minimax`；模型类型 `CHAT`/`EMBEDDING`/`IMAGE_GEN`/`VIDEO_GEN`/`AUDIO_GEN`。`ProviderType.supportedModelTypes` 描述厂商理论模型目录；管理端可选项和媒体模型保存校验使用 `MediaToolAdapterRegistry` 从真实适配器注册键推导的 `runtimeModelTypes` / `runtimeMediaOperations`。VECTOR/HYBRID 知识库使用的 EMBEDDING 模型必须配置默认向量维度（`embedding_dimensions`），新建知识库会采用并固化该维度（也可在建库时显式覆盖）；FULL_TEXT 知识库不需要嵌入模型。API Key 使用 AES-GCM 加密存储；`ModelResolver` 通过 AgentScope `ModelRegistry` 按 modelId 解析 CHAT 模型，`EmbeddingModelResolver` 解析 EMBEDDING 模型（向量 RAG 用）。端点 `/v1/ai/llm-providers`、`/v1/ai/llm-models`。

### 智能应用
应用字段：`modelId` `imageModelId` `videoModelId` `audioModelId` `systemPrompt` `maxIters` `temperature` `toolsAllow` `toolsDeny` `mcpServerIds` `knowledgeBaseIds` `ragMode` `subAgentIds` `skillsAllow` `skillsDeny` `appType` `selfEvolve` `sandboxBackend` `audience`(B/C/ALL) `ownerId` `enableImageUpload` `enableFileUpload` `enabled` 及发布字段。CRUD 端点 `/v1/ai/apps`（权限 `ai:app:*`）；用户可用应用 `GET /v1/ai/apps/available`（`ai:app:available`，按 audience+角色/owner 过滤，返回含上传开关的安全视图）。`appType` 创建后不可变；其余字段可调，变更前后写配置审计（见下）。**配置审计**：`ai_app_config_audit` append-only 记录 CREATE/UPDATE（旧值快照）/DELETE 三类操作，供人工核对回退，暂无查询端点。

### 应用发布
发布状态直接挂在 `ai_app` 行上（`publish_code`/`publish_status`/`published_at`），**不复制配置快照**——运行配置以 `ai_app` 当前行为唯一事实来源，编辑即生效。`PUT /v1/ai/apps/{id}/publication` 发布（幂等，校验应用已启用且模型有效，生成 32 位 `publishCode`），`DELETE` 下线（幂等，保留 publishCode，重发链接不变）。已发布应用对外两个端点：`GET /v1/ai/public/apps/{publishCode}/profile`（匿名，返回裁剪视图 publishCode/name/avatar/description，需 startup 配置 sa-token ignored 放行）与 `GET /v1/ai/public/apps/{publishCode}/access`（登录，复用应用可见性校验返回安全视图）。发布状态不参与后台/普通对话的可见性，仅控制独立 URL 入口。详见 [应用发布设计](../docs/design/ai-app-publishing.md)。

### 对话
会话与 Run 端点（`/v1/ai/sessions`）：

| 端点 | 权限 | 说明 |
| --- | --- | --- |
| `POST /v1/ai/sessions` | `ai:chat-session:add` | 创建会话 |
| `GET /v1/ai/sessions/page` | `ai:chat-session:page` | 分页会话 |
| `GET /v1/ai/sessions/{id}` | `ai:chat-session:get` | 会话详情 |
| `DELETE /v1/ai/sessions/{id}` | `ai:chat-session:delete` | 删除会话（拦截活跃 Run；删 Run/附件/消息并清理 AgentScope 持久化状态） |
| `GET /v1/ai/sessions/{id}/messages?before=&limit=` | `ai:chat-session:message-page` | 消息历史**游标分页**（默认 50、上限 200，`before` 为消息 ID 游标） |
| `POST /v1/ai/sessions/{id}/chat` | `ai:chat-session:chat` | 流式对话（SSE，带心跳） |
| `GET /v1/ai/sessions/{id}/runs/active` | `ai:chat-session:active-run` | 当前活跃 Run |
| `GET /v1/ai/sessions/{id}/runs/{runId}` | `ai:chat-session:run-get` | Run 详情 |
| `GET /v1/ai/sessions/{id}/runs/{runId}/events` | `ai:chat-session:run-events` | 续看 Run 事件（SSE，重连先发快照 bootstrap） |
| `POST /v1/ai/sessions/{id}/runs/{runId}/confirm` | `ai:chat-session:run-confirm` | HITL 工具确认（SSE） |
| `POST /v1/ai/sessions/{id}/runs/{runId}/input` | `ai:chat-session:run-input-submit` | HITL 挂起输入提交（SSE） |
| `POST /v1/ai/sessions/{id}/runs/{runId}/stop` | `ai:chat-session:run-stop` | 停止 Run |

ChatRun 是 AgentScope 之上的业务状态：本地内存事件用于实时输出，数据库快照用于页面恢复，状态仅有 `RUNNING / COMPLETED / STOPPED / FAILED`；AgentScope 的 `ASKING`（工具确认/挂起输入）不复制成业务状态，体现为快照 pendingTools/pendingInputs 与工具状态枚举。本模块不实现节点心跳、租约、远程停止、旧调用接管或全局维护扫描。多实例下若请求未落回执行实例，返回持久化 bootstrap 后关闭 SSE；用户可显式停止旧 Run 并创建新 Run。并发受 `ChatExecutionCapacityLimiter` 限制（全局 `max-active-runs` + 每用户 `max-active-runs-per-user`，仅本节点）。详见 [对话运行时设计](../docs/design/chat-runtime.md)。

### 聊天附件
- 上传 `POST /v1/ai/chat/attachments`（`ai:chat-attachment:upload`，multipart `sessionId`+`file`）：支持图片（jpg/jpeg/png/gif/webp）与文档（pdf/doc/docx/txt/md），受应用级 `enableImageUpload`/`enableFileUpload` 开关校验；另有详情/删除/登录下载（支持 Range）与**签名直链** `/{id}/preview|file?expires=&sig=`（HMAC-SHA256，默认 TTL 1h，OSS 后端 302 到短期预签名地址；Office 原件另存派生 PDF 预览）。
- 发送消息时 `SendMessage.attachmentIds` 绑定消息；图片+视觉模型内联 base64，文档在 `max-inline-chars`（默认 8000）预算内全文内联，超出则注入附件清单，由 `search_chat_attachment`/`read_chat_attachment` 工具（仅 ChatRun 可见）按需 grep 定位与分段读取。
- 原文件与知识库共用 `rag.document-storage` 后端（LOCAL/OSS）；未绑定消息且超保留期（默认 168h）的孤儿附件由后台周期清理。
- `publish_workspace_file` 工具（`@AiTools` 全局）：把 AgentScope 工作区 `outputs/` 产物发布为当前对话附件（PDF/Office/文本等，Tika MIME + 结构校验，大小受媒体物化限制）。

### 媒体生成
- 对话接口没有媒体专用请求字段或前端创作控件；图片、视频、语音请求均作为普通用户消息进入统一 ReAct 链路，模型依据真实 ToolSchema 自主选择工具和填写参数。
- `ToolkitAssembler.build(app, includeChatRunTools)` 构建 Web Chat 与 Channel 两套工具集：应用绑定的官方媒体工具（`MediaGenerationBindingResolver` + `MediaToolAdapterRegistry` 解析 IMAGE/VIDEO/AUDIO 绑定）与独立配置的 `qwen_text_to_image`（默认关闭）仅挂载到 Web Chat；`search_chat_attachment`/`read_chat_attachment` 等 ChatRun 专属工具 Channel 不可见（`publish_workspace_file` 因 `@AiTools` 已全局可见）。知识库、本地、媒体和 MCP 等全部已注册来源最后统一受应用 `toolsAllow` / `toolsDeny` 控制。
- 所有媒体工具统一经 `MaterializingMediaTool` 装饰：结果返回前由 `ToolResultMediaMaterializer` 把 URL/Base64 媒体下载存入平台 AI 文件存储（LOCAL / OSS），客户端接收平台附件 `DataBlock` 和签名预览地址；模型侧结果只保留稳定 attachmentId，不透传供应商文本、metadata、临时 URL 或平台签名 URL。
- 详见 [媒体生成架构](../docs/design/media-generation-architecture.md)。

### MCP 服务
传输 `stdio`/`sse`/`http`/`streamable_http`；`ToolkitAssembler` 按应用装载，单个失败不影响其余；`POST /v1/ai/mcp-servers/{id}/test` 测连通性。

### 知识库（RAG）
- 内容检索策略 `ai_knowledge_base.retrieval_mode`：`FULL_TEXT`（无需嵌入模型）、`VECTOR`（默认，向量余弦召回）、`HYBRID`（两路候选按 RRF 融合）。全文索引后端 `rag.full-text.type`：`LUCENE`（本地单机，SmartChineseAnalyzer/BM25）或 `OCEANBASE`（OB 4.3.5+ MySQL 模式全文索引，所有知识库共享 `ai_kb_full_text_chunk` 表按 kb_id 隔离）。
- 文档上传只切割一次，再按策略写入全文/向量库；向量库支持 MEMORY（默认）/ PGVECTOR / OCEANBASE，原文件支持 LOCAL（默认）/ OSS。
- 检索模式 `ai_app.rag_mode`：`GENERIC`（默认，中间件自动检索注入）/ `AGENTIC`（注册 `retrieve_knowledge` 工具，模型自主调用）/ `BOTH`（两者兼有）。
- 诊断端点：`POST /v1/ai/knowledge-bases/{id}/retrieval-test`（`ai:knowledge-base:retrieval-test`，检索探针）与 `POST /v1/ai/knowledge-bases/{id}/indexes/rebuild`（`ai:knowledge-base:index-rebuild`，异步重建索引）。
- 默认关闭，由 `lambda.fusion.ai.rag.enabled` 开启；关闭时管理 CRUD 仍可用，检索注入不装配。Lucene 由 `lambda.lucene.enabled` 独立控制，索引为本地可重建投影，面向单机部署。
- 端点：`/v1/ai/knowledge-bases`、`/v1/ai/knowledge-bases/{kbId}/documents`（上传/下载/删除）。
- 详见 [RAG 文档切割与来源契约](../docs/design/rag-document-chunking.md)。

### 子代理
DB 驱动子代理定义（`ai_sub_agent`：`name`/`description`/`prompt`/`modelId`/`steps`/`toolsAllow`/`skillsAllow`/`workspaceMode`，`category=SUB_AGENT`；定时任务复用同表，见下节）。按应用绑定（`ai_app.sub_agent_ids`，仅 WORKSPACE 型），`AgentFactory` 构建期注册为 `SubagentDeclaration`，主 agent 经 harness 子代理体系（`agent_spawn`/`agent_send`）调度，**路由由 LLM 按 description 自主决策**。同名 `workspace/subagents/*.md` 文件覆盖 DB 声明。端点 `/v1/ai/sub-agents`。详见 [子代理远程恢复设计](../docs/design/subagent-remote-agent.md)。

`expose_to_user` 发布事件由 Lambda Fusion 应用层补全应用、租户、用户和父会话信息，不修改 AgentScope 源码。记录中的子 Agent 类型会转换为带应用与租户作用域的内部标识，其他节点据此重建正确的父 Agent，再按其声明恢复子 Agent。直接对话必须通过 `FusionSubagentGateway`，同时校验应用、租户和用户；远程 Workspace 模式下使用同一 `DistributedStore` 的执行锁串行化跨节点请求。

升级前已生成、但未经过 Lambda Fusion 暴露事件补全的旧记录不会参与全局恢复；客户端需从父 Agent 重新生成新的 `subagentId`。平台外部通道若需要开放子 Agent 直接对话，也必须在其认证调用链中补全同样的业务身份后再调用该入口。

### 定时任务
DB 驱动的定时 Agent 任务（`ai_sub_agent` 表 `category=SCHEDULED_TASK` 行：`name`/`prompt`/`modelId` 必填；调度字段 `scheduleMode`(CRON|FIXED_RATE)/`cronExpression`/`fixedRate`/`zoneId`/`initialDelay`/`inputMsg`/`scheduleEnabled`；工具白名单 `toolsAllow`、`temperature`/`topP`/`steps`）。机制：

- 调度走 AgentScope 扩展 `QuartzAgentScheduler`（Quartz JDBC JobStore，支持集群），调度键为 `任务主键:任务名`；重启后 `AgentTaskBootstrap` 从 DB 全量重注册（DB 为唯一事实来源）。
- 每次触发**新建一次性 ReActAgent**（不绑定 App、不走会话运行时、不参与子代理路由），系统提示词自动注入租户上下文；触发时在 Quartz worker 线程恢复租户上下文。
- 手动触发 `POST /{id}/trigger` 同步执行一次（不注册 Quartz）；执行结果写 `ai_scheduled_task_log`（SCHEDULED/MANUAL、SUCCESS/FAILED、耗时、输出/错误摘要），完成后经 Dubbo 调 message 模块给创建人发站内信（成功 info / 失败 important）。
- 端点 `/v1/ai/scheduled-tasks`（权限 `ai:scheduled-task:*`）：page / get / status（Quartz TriggerState）/ logs/page / add / update / delete / pause / resume / trigger。
- 配置 `lambda.fusion.ai.schedule.*`（enabled 默认 false / schedulerId / threadPoolSize 默认 4 / datasource 默认 master）。

详见 [定时 Agent 任务设计](../docs/design/scheduled-agent-task.md)。

### 技能市场
技能仓库源按部署形态选择：`MYSQL`（默认）/ `POSTGRES`（可读写，admin CRUD）/ `GIT` / `NACOS`（只读 catalog）。`type=NONE` 或扩展未引入时技能市场禁用（WORKSPACE app 仅用 workspace 本地技能）。支持技能包（zip）检查与导入（`SkillPackageReader`）。端点 `/v1/ai/skills`。

### 通道配置
`ai_channel_config` 表管理外部通道→agent 的路由绑定（按 channelId）。内置钉钉/飞书/企微通道适配器（`AiConfigure.ChannelAdapterConfig`，各 `@ConditionalOnClass`，扩展未引入时不装配）。端点 `/v1/ai/channel-configs`。

### Workspace（WORKSPACE 型）
- 首次对话时按运营商自动脚手架（per-`tenantId` workspace）：`AGENTS.md`/`skills/`/`subagents/`/`memory/`/`knowledge/`/`tools.json`/`outputs/`（产物目录，供 `publish_workspace_file` 发布）。
- 文件管理 API（权限 `ai:app-workspace:list`/`read`/`write`/`audit-list`，需指定 `tenantId` 查看对应运营商的 workspace）：
  - `GET /v1/ai/apps/{id}/workspace/files?tenantId=` 列出
  - `GET /v1/ai/apps/{id}/workspace/file?tenantId=&path=` 读取
  - `PUT /v1/ai/apps/{id}/workspace/file?tenantId=&path=` 写入
  - `GET /v1/ai/apps/{id}/workspace/audit?tenantId=` 自演化审计记录
- `workspace.storage.type` 是部署级且不可由应用覆盖：`LOCAL` 保持原本地单节点行为；`MYSQL`/`POSTGRES` 供多节点共享，并要求 `state-store.type` 同时使用分布式后端。切换类型会进入新的存储命名空间，不迁移、复制或删除旧数据。

## 配置

AI 配置前缀为 `lambda.fusion.ai`，单机全文索引使用独立前缀 `lambda.lucene`：

```yaml
lambda:
  lucene:
    enabled: true                                # LUCENE 全文检索所需；本地单机索引
    directory: ${user.home}/.agentscope/fusion/rag/lucene
  fusion:
    ai:
      runtime:
        default-max-iters: 10
      security:
        encryption-key: ${AI_ENCRYPTION_KEY}      # AES 密钥，生产必配
      audience:
        b-roles: [ROLE_OPERATOR]                    # B 端角色名（下游自定义）
        c-roles: [ROLE_CONSUMER]                    # C 端角色名
      gateway:
        enabled: true                               # 关闭则回退直连 agent.streamEvents（无串行锁/通道）
      cluster:
        invalidation-broadcast: true               # 多实例配置变更 Dubbo 广播失效缓存
      state-store:
        type: MEMORY                                # MEMORY/FILE/MYSQL/POSTGRES/REDIS/OSS/COS
        root:                                       # FILE 模式根目录；默认 ${workspace.root}/state
        mysql:    { datasource, database, table, create-if-not-exist }
        postgres: { datasource, schema, table, create-if-not-exist }
        redis:    { key-prefix }
        oss:      { endpoint, access-key-id, access-key-secret, bucket-name, key-prefix }
        cos:      { region, secret-id, secret-key, bucket-name, key-prefix }
      workspace:
        root: ${user.home}/.agentscope/fusion      # workspace 根
        storage:
          type: LOCAL                              # LOCAL（单节点）/ MYSQL / POSTGRES
          mysql:    { datasource }                 # 默认 master
          postgres: { datasource }                 # 默认 ai-postgres
      memory:
        flush:
          mode: THROTTLED                          # ALWAYS / THROTTLED / NEVER
          min-gap: 10m                             # THROTTLED 最小刷新间隔
      sandbox:
        isolation-scope: AGENT                      # AGENT|USER|SESSION|GLOBAL
        docker:    { image, network, cpu-count, memory-size-bytes, workspace-root }
        kubernetes:{ master-url, namespace, image, workspace-root, service-account, token }
        e2b:       { api-key, template-id, domain, workspace-root }
        daytona:   { api-key, api-url, workspace-root }
        agentrun:  { api-key, api-url, workspace-root }
      skill:
        repository:
          type: MYSQL                               # MYSQL/POSTGRES/GIT/NACOS/NONE
          mysql:    { datasource, create-if-not-exist, writeable }
          postgres: { datasource, create-if-not-exist, writeable }
          git:      { remote-url, branch, local-path, source }
          nacos:    { server-addr, namespace-id, access-key, secret-key }
      tools:
        qwen-image:
          enabled: false                            # 独立普通工具，默认关闭
          api-key: ${AI_QWEN_IMAGE_API_KEY:}
          base-url: ${AI_QWEN_IMAGE_BASE_URL:}
          model-name: ${AI_QWEN_IMAGE_MODEL_NAME:qwen-image-3.0-pro}
      rag:
        enabled: false                              # 默认关闭；开启后装配检索注入
        default-retrieval-mode: VECTOR              # FULL_TEXT / VECTOR / HYBRID
        default-limit: 5
        default-score-threshold: 0.5
        max-inject-chars: 4000
        full-text:
          type: LUCENE                              # LUCENE / OCEANBASE
          table-name: ai_kb_full_text_chunk         # 仅 OCEANBASE
        store:
          type: MEMORY                              # MEMORY（默认）/ PGVECTOR / OCEANBASE
          pg-vector: { jdbc-url, username, password, schema } # 仅 PGVECTOR 需要
          ocean-base: { jdbc-url, username, password } # 仅 OCEANBASE 需要
        document-storage:
          type: LOCAL                               # LOCAL（默认）/ OSS
          local: { root }                           # 默认 ${workspace.root}/knowledge-files
          oss:   { client-name, key-prefix }        # 默认前缀 ai/knowledge/
      chat:
        run:
          connection-timeout-seconds: 300           # SSE 连接超时
          heartbeat-interval-seconds: 15            # SSE 心跳（0 关闭）
          max-run-duration-seconds: 1800            # 单次 Agent 执行上限
          stop-grace-seconds: 30                    # 强停前等待协作式停止
          terminal-ttl-seconds: 600                 # 终态后内存事件保留
          max-events: 4096                          # 单 Run 事件数上限
          max-bytes: 8388608                        # 单 Run 事件字节数上限
          max-active-runs: 200                      # 单实例并发 Run 上限
          max-active-runs-per-user: 4               # 每用户并发 Run 上限
          subscriber-queue-size: 256                # 单订阅者事件队列
          snapshot-interval-seconds: 15             # 快照检查点间隔
          enable-reasoning: true                    # 推理过程事件推送
        attachment:
          max-file-size-mb: 10                      # 单附件大小上限
          max-count: 5                              # 单消息附件数上限
          max-inline-chars: 8000                    # 文档内联预算，超出走按需读取工具
          max-tool-read-chars: 4000                 # read_chat_attachment 单次读取
          image-types: [jpg, jpeg, png, gif, webp]
          doc-types: [pdf, doc, docx, txt, md]
          preview:
            secret: ${AI_ATTACHMENT_PREVIEW_SECRET:}  # 签名密钥，未配置不生成直链
            ttl-seconds: 3600
          generated-media: { max-file-size-mb: 100, connect-timeout-seconds: 10, request-timeout-seconds: 120, max-redirects: 3 }
          orphan-cleanup: { enabled: true, retention-hours: 168, batch-size: 200, interval-minutes: 60 }
      schedule:
        enabled: false                              # 定时任务总开关
        scheduler-id: lambda-fusion-ai-scheduler
        thread-pool-size: 4                         # Quartz 并发上限
        datasource: master                          # Quartz JDBC JobStore 数据源
```

| 项目 | 说明 |
| --- | --- |
| `security.encryption-key` | 未配置时启动告警，加密 API Key 时抛异常 |
| `audience.{b,c}-roles` | B/C 端角色名列表，用于 app 可见性过滤（下游自定义角色名） |
| `gateway.enabled` | 是否启用 HarnessGateway（默认 true）；关闭后无轮次串行锁与外部通道能力 |
| `cluster.invalidation-broadcast` | 多实例下配置变更经 Dubbo broadcast 失效各节点 Agent 缓存（默认 true） |
| `state-store.type` | Agent 多轮记忆后端；分布式后端（MYSQL/POSTGRES/REDIS/OSS/COS）依赖对应 AgentScope 扩展，缺失回退 MEMORY |
| `memory.flush.mode` | WORKSPACE 自演化应用的长期记忆提取策略；默认 THROTTLED，可选 ALWAYS/THROTTLED/NEVER |
| `memory.flush.min-gap` | THROTTLED 模式最小刷新间隔，默认 10m |
| `sandbox.*` | 各沙箱后端凭据；对应扩展需在 classpath（ai 模块已 optional 引入） |
| `skill.repository.type` | 技能仓库源；`NONE` 或扩展缺失时技能市场禁用 |
| `tools.qwen-image.*` | 独立 `qwen_text_to_image` 工具配置；不读取应用媒体模型或 Skill，启用时 key、地址和固定模型名必填 |
| `lambda.lucene.*` | 本地全文索引开关和根目录；仅适合单机，关闭后不装配 Lucene Bean |
| `rag.enabled` | 知识库检索注入总开关（默认 false）；关闭时管理 CRUD 仍可用 |
| `rag.full-text.type` | 全文索引后端：LUCENE（本地单机）/ OCEANBASE（OB 4.3.5+ 全文索引，共享 chunk 表） |
| `rag.default-retrieval-mode` | 新建知识库未显式指定时的内容检索策略：FULL_TEXT / VECTOR / HYBRID |
| `rag.store.type` | 向量库后端；PGVECTOR 使用 `store.pg-vector.*` JDBC 连接；OCEANBASE 使用 `store.ocean-base.*` JDBC 连接 |
| `rag.document-storage.type` | AI 文件存储后端；知识库原文件、聊天附件和工具生成媒体共用 LOCAL / OSS |
| `chat.run.*` | Run 执行参数：SSE 超时/心跳、执行上限、并发许可、事件缓冲、快照间隔、推理事件 |
| `chat.attachment.*` | 附件参数：大小/数量/类型/内联预算、签名直链密钥与 TTL、媒体物化限制、孤儿回收 |
| `schedule.*` | 定时任务：开关、Quartz 调度标识、线程数（并发上限）、JobStore 数据源；集群经 `spring.quartz.*` 配置 |
| 数据源 | 元数据表使用 MySQL `master`；pgvector/OB 连接走各自 JDBC 配置；Lucene 不依赖 MySQL 全文索引版本 |

## 数据库对象

| 表 | 隔离 | 说明 |
| --- | --- | --- |
| `ai_llm_provider` `ai_llm_model` | 全局 | LLM 提供方/模型（EMBEDDING 模型含 `embedding_dimensions`） |
| `ai_app` | 全局 | 智能应用（含 `audience`/`owner_id`/`knowledge_base_ids`/`rag_mode`/`sub_agent_ids`/上传开关/`publish_code`/`publish_status`） |
| `ai_app_config_audit` | `tenant_id` | 应用配置审计（append-only，CREATE/UPDATE 旧值/DELETE 快照） |
| `ai_mcp_server` | 全局 | MCP 服务 |
| `ai_sub_agent` | 全局 | 子代理定义（`category=SUB_AGENT`）与定时任务定义（`category=SCHEDULED_TASK`，含调度字段）共用表 |
| `ai_scheduled_task_log` | — | 定时任务执行记录（SCHEDULED/MANUAL、SUCCESS/FAILED、耗时、输出） |
| `ai_channel_config` | 全局 | 通道→agent 路由绑定 |
| `ai_skill` | 全局 | 技能市场 |
| `ai_knowledge_base` `ai_knowledge_document` | 全局 | 知识库/文档行 |
| `ai_kb_full_text_chunk` | 全局 | OCEANBASE 全文索引 chunk（所有知识库共享按 kb_id 隔离） |
| `ai_chat_session` `ai_chat_message` | `tenant_id` | 会话/消息 |
| `ai_chat_run` | `tenant_id` | 对话运行（含 `snapshot_json`/`phase_no`/终态，断线恢复的事实来源） |
| `ai_chat_attachment` | `tenant_id` | 聊天附件（`message_id` NULL=未发送，含派生预览路径） |
| `ai_app_workspace_audit` | `tenant_id` | 自演化审计 |

迁移：`META-INF/db/changelogs/lambda-ai-changelog.xml`。

## 快速开始

注册提供方 → 注册模型 → 创建应用 → 创建会话 → 流式对话。

创建 WORKSPACE 自演化应用：
```http
POST /v1/ai/apps
{
  "name": "自演化助手",
  "systemPrompt": "你是一位会持续学习的助手",
  "modelId": "model-id",
  "appType": "WORKSPACE",
  "selfEvolve": true,
  "sandboxBackend": "HOST",
  "enabled": true
}
```

带知识库的应用（需先 `rag.enabled=true` + 创建知识库 + 上传文档）：
```http
POST /v1/ai/apps
{
  "appType": "WORKSPACE",
  "modelId": "model-id",
  "knowledgeBaseIds": ["kb-id"],
  "ragMode": "GENERIC",
  "enabled": true
}
```

沙箱执行（需下游引入对应扩展依赖 + 配置凭据 + Docker/集群可用）：
```http
POST /v1/ai/apps   { "appType":"WORKSPACE", "selfEvolve":true, "sandboxBackend":"DOCKER", ... }
```

创建定时任务（需 `schedule.enabled=true`，cron 每天早 8 点执行）：
```http
POST /v1/ai/scheduled-tasks
{
  "name": "日报生成",
  "prompt": "汇总今日数据并生成日报",
  "modelId": "model-id",
  "scheduleMode": "CRON",
  "cronExpression": "0 0 8 * * ?",
  "scheduleEnabled": true
}
```

## 错误码

`30000-39999` 段。枚举见 `com.lambda.fusion.ai.exception.AiErrorCode`。

## 扩展点

- **自定义本地工具**：类上标注 `@AiTools`（组合 `@Component`，须在组件扫描范围内），方法上标 AgentScope `@Tool`，`ToolkitAssembler` 启动时自动扫描；需 HITL 确认时方法级加 `@RequireConfirm` 或类级 `@AiTools(requireConfirm = true)`。按应用绑定、不参与全局扫描的工具（如 `KnowledgeRetrievalTool`、`ChatAttachmentContentTool`）不加 `@AiTools`。
- **自定义沙箱后端**：实现 `SandboxBackendProvider`，在 `AiConfigure.SandboxConfig` 中仿照现有后端加一个带 `@ConditionalOnClass` 的嵌套配置类。
- **自定义状态存储后端**：在 `AiConfigure.StateStoreConfig` 中加嵌套配置类（对齐 MYSQL/POSTGRES 等现有实现）。
- **自定义外部通道**：实现 `io.agentscope.harness.agent.gateway.channel.Channel`，声明为 Spring Bean，`ChannelLifecycle` 自动注册到 `ChannelManager` 并经 `HarnessGateway` 路由。入站消息在 `preferredAgentId`（或 `ChannelConfig.defaultAgentId`）填 `app:{appId}:t:{tenantId}` 指定目标 agent。
- **换向量库后端**：改 `SimpleKnowledgeAdapter#createStore`；换全文索引后端实现 `KnowledgeFullTextIndex`。
- **扩展文档原文件存储**：实现 `DocumentFileStorage` 注册为 Bean（按 `type()` 路由）。

## 源码定位

| 模块 | 路径 |
| --- | --- |
| 自动配置入口 | `autoconfig/AiAutoConfiguration.java` |
| 模块装配 | `AiConfigure.java`（含 Gateway/Sandbox/StateStore/ChannelAdapter/Rag/SkillRepository/Schedule/DubboInvalidation 等嵌套配置） |
| 配置属性 | `AiProperties.java` |
| Agent 工厂 | `runtime/AgentFactory.java` |
| 模型解析 | `runtime/ModelResolver.java` / `runtime/EmbeddingModelResolver.java` |
| 子代理声明映射 | `runtime/SubAgentDeclarationMapper.java` |
| 沙箱装配 | `AiConfigure.SandboxConfig`（嵌套配置类） |
| 状态存储装配 | `AiConfigure.StateStoreConfig`（嵌套配置类） |
| Gateway/通道装配 | `AiConfigure.GatewayConfiguration` / `AiConfigure.ChannelAdapterConfig` |
| 通道生命周期 | `runtime/gateway/ChannelLifecycle.java` |
| 通道路由配置 | `channel/` 包 |
| 上下文透传 | `runtime/gateway/RuntimeProperty.java` |
| 子代理直连网关 | `runtime/gateway/FusionSubagentGateway.java` |
| Workspace | `runtime/workspace/` |
| RAG | `rag/` 包 |
| 媒体生成工具 | `runtime/tools/ImageGenerationTool.java` / `runtime/tools/VideoGenerationTool.java` / `runtime/tools/AudioGenerationTool.java` / `runtime/tools/QwenImageGenerationTool.java` |
| 工具媒体物化 | `runtime/media/MaterializingMediaTool.java` + `chat/runtime/media/` |
| AI 文件存储 | `storage/` |
| 聊天附件 | `chat/attachment/` + `chat/service/impl/ChatAttachmentServiceImpl.java` |
| 子代理 | `subagent/` 包 |
| 定时任务 | `schedule/` 包 |
| 对话执行运行时 | `chat/runtime/`（ChatExecutionService / ChatExecutionInstance / AgentExecutionAdapter 等） |
| 流式对话 | `chat/service/impl/ChatServiceImpl.java` |
| 主动出站 | `chat/controller/OutboundController.java` |
| 多实例缓存失效 | `runtime/event/DubboConfigInvalidationBroadcaster.java` |
| 数据库变更 | `META-INF/db/changelogs/lambda-ai-changelog.xml` |

## 路线图

已完成：CHAT / WORKSPACE(ASSISTANT+AUTONOMOUS) / 自演化审计 / 多后端沙箱(Docker+K8s+E2B+Daytona+AgentRun) / audience+owner_id 可见性 / 知识库 RAG（FULL_TEXT(Lucene+OceanBase) + 向量 VECTOR(MEMORY/PGVECTOR/OCEANBASE) + RRF HYBRID，原文件 LOCAL+OSS，检索探针与索引重建） / 子代理（DB 驱动 + REST + 跨节点恢复与执行串行） / 技能市场（MYSQL/POSTGRES/GIT/NACOS + 技能包导入） / 通道配置管理 + 钉钉/飞书/企微适配器 / Agent 状态存储多后端（MEMORY/FILE/MYSQL/POSTGRES/REDIS/OSS/COS） / Workspace 分布式存储（LOCAL/MYSQL/POSTGRES） / HarnessGateway（per-session 串行 + 外部通道 SPI + 主动出站）/ **应用发布（publishCode 独立 URL + 配置审计）** / **聊天附件（上传/签名预览/按需读取/孤儿回收）** / **HITL 人机协同（工具确认 + 挂起输入）** / **定时 Agent 任务（Quartz 集群 + 执行日志 + 站内信通知）** / 多实例配置失效广播。

待跟进：
- `WakeupDispatcher` + `MessageBus`（异步子代理完成 announce、团队消息、定时触发）
- 真实平台通道适配器打磨（钉钉/飞书/企微下游落地）
- 应用分享与权限（run/edit/fork）
- 成本/Token 统计
- 定时任务执行记录的输出正文留存与 `AgentExecutionJobListener` 调度键格式对齐
