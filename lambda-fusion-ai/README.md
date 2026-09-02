# Lambda Fusion AI

`lambda-fusion-ai` 是 Lambda Fusion 体系的 AI 能力模块，基于 **AgentScope 2.0** 构建，为 Spring Boot 应用提供多智能体应用托管能力：平台（ISV）预置智能体应用，每个应用绑定模型、系统提示词、工具、MCP 服务、知识库与子代理，对外提供运营商隔离的流式对话；WORKSPACE 型应用进一步对齐 AgentScope harness 完整能力（workspace / 技能 / 子代理 / 记忆 / 沙箱 / 自演化）。

> 深度文档见 `docs/skills/lambda-fusion-ai/SKILL.md`（自动配置入口、配置项、关键机制、常见改造入口）。本 README 是概览。

## 能力总览

| 能力 | 包 | 端点 | 说明 |
| --- | --- | --- | --- |
| LLM 提供方/模型 | `llm` | `/v1/ai/llm-providers` `/v1/ai/llm-models` | DB 驱动，API Key AES-GCM 加密；使用 AgentScope Provider SPI |
| 智能应用 | `apps` | `/v1/ai/apps` `/v1/ai/apps/available` | CHAT / WORKSPACE 两型，平台全局 |
| 对话会话 | `chat` | `/v1/ai/sessions` | SSE 流式聊天 + 主动出站 |
| MCP 服务 | `mcp` | `/v1/ai/mcp-servers` | stdio/sse/http/streamable_http |
| 知识库 RAG | `rag` | `/v1/ai/knowledge-bases` | 文档切块入库 + 对话检索注入（GENERIC/AGENTIC/BOTH） |
| 子代理 | `subagent` | `/v1/ai/sub-agents` | DB 驱动子代理定义，主 agent 按 description 自主路由 |
| 技能市场 | `skill` | `/v1/ai/skills` | MYSQL/POSTGRES/GIT/NACOS 仓库源 |
| 通道配置 | `channel` | `/v1/ai/channel-configs` | 外部通道→agent 路由绑定（DB 管理） |
| Gateway/通道适配器 | `runtime.gateway` | `POST /v1/ai/outbound/send` | HarnessGateway + 钉钉/飞书/企微适配器（条件装配） |
| 沙箱 | `runtime.sandbox` | — | HOST/Docker/K8s/E2B/Daytona/AgentRun |
| 状态存储 | `runtime.state` | — | MEMORY/FILE/MYSQL/POSTGRES/REDIS/OSS/COS |
| Workspace | `runtime.workspace` | `/v1/ai/apps/{id}/workspace/*` | 脚手架/文件管理/自演化审计 |
| API Key 加密 | `security` | — | AES-GCM |

> 能力表（app/provider/model/mcp/sub_agent/channel_config/knowledge_base）为平台级全局资源（无 `tenant_id`）；运营商隔离发生在会话层（session/message/audit 按 `tenant_id`）与运行时（agent 实例 + 工具按用户 tenant 查业务数据）。应用按 `audience`(B/C/ALL)+角色 或 `ownerId` 控制可见性。

## 应用分型

应用分两型，由 `appType` 决定能力栈（创建后不可变）：

| appType | workspace | 文件工具 | 技能/子代理 | 自演化 | 沙箱(shell) |
| --- | --- | --- | --- | --- | --- |
| `CHAT` | ✗ | ✗ | ✗ | ✗ | ✗ |
| `WORKSPACE` + `selfEvolve=false`（ASSISTANT） | ✓ 平台管理 | ✗ | ✓ | ✗ | 按 sandboxBackend |
| `WORKSPACE` + `selfEvolve=true`（AUTONOMOUS） | ✓ agent 可写 | ✓ | ✓ | ✓ | 按 sandboxBackend |

`sandboxBackend`（WORKSPACE 型）决定执行隔离：`HOST`（宿主，无 shell）/ `DOCKER` / `KUBERNETES` / `E2B` / `DAYTONA` / `AGENTRUN`（后 5 个启用 shell；扩展未安装时回退 HOST）。多轮上下文由 Agent 状态存储维持（所有 appType 共用，按 `state-store.type` 配置）。

## 架构

```
请求 ──▶ ChatController(SSE) ──▶ ChatService ──▶ ChatRunCoordinator
          ├─ ChatRunStateService    Run 状态/快照/最终消息（MySQL）
          ├─ ChatRunEventStore      当前进程短期回放与实时订阅
          └─ AgentFactory           按 (app,tenant) 构建+缓存 HarnessAgent
          │    ├─ ModelResolver              DB modelId -> AgentScope Model
          │    ├─ EmbeddingModelResolver      EMBEDDING 模型解析（RAG 用）
          │    ├─ ToolkitAssembler            本地 @Tool + MCP
          │    ├─ SubAgentDeclarationMapper   子代理 DB 声明 -> SubagentDeclaration
          │    ├─ SandboxSpecResolver         沙箱后端 spec
          │    └─ RagMiddleware / KnowledgeRetrievalTool  检索注入（条件挂载）
          └─ HarnessAgent.streamEvents -> Flux<AgentEvent> -> AG-UI -> SSE

外部通道（钉钉/飞书/企微/自定义 Channel Bean）──▶ ChannelManager ──▶ HarnessGateway ──▶ LF agent
```

## 代码结构

主包：`src/main/java/com/lambda/fusion/ai`

| 包 | 作用 |
| --- | --- |
| `llm` | LLM 提供方与模型管理（DB 驱动，API Key 加密） |
| `apps` | 智能应用 CRUD + workspace 文件管理 API |
| `chat` | 对话会话、SSE 流式聊天、主动出站 |
| `mcp` | MCP 服务管理 |
| `rag` | 知识库管理 + 文档切块入库 + 检索注入（中间件/工具）+ 原文件存储 |
| `subagent` | 子代理定义管理（DB 驱动） |
| `skill` | 技能市场管理（仓库源 MYSQL/POSTGRES/GIT/NACOS） |
| `channel` | 通道路由配置管理（DB 驱动，channelId→agent 绑定） |
| `runtime` | AgentScope 集成：`AgentFactory` / `ModelResolver` / `EmbeddingModelResolver` / `ToolkitAssembler` / `SubAgentDeclarationMapper` |
| `runtime.gateway` | Gateway/通道：`ChannelLifecycle` / `ChannelBootstrap` / `ChannelConfigApplier` / `RuntimeProperty` |
| `runtime.sandbox` | 沙箱后端解析：`SandboxSpecResolver` / `SandboxBackendProvider` |
| `runtime.state` | Agent 状态存储：`StateStoreProvider` / `StateStoreDataSources` |
| `runtime.workspace` | Workspace 路径/脚手架/文件服务/自演化审计 |
| `runtime.event` | `ConfigChangedEvent`（配置变更失效缓存） |
| `security` | API Key 加解密（AES-GCM） |

各扩展后端（沙箱/状态存储/技能仓库/通道适配器）的条件装配以 `AiConfigure` 的嵌套静态配置类收敛：每个后端一个 `@ConditionalOnClass` 嵌套类，扩展 jar 缺席时对应类不加载、优雅降级。

## 核心链路

- **Agent 构建**：`AgentFactory.getOrBuild(appId, tenantId)` 按 `appType` 分支；CHAT 关闭 workspace/子代理能力，WORKSPACE 开启（AGENTS.md/技能/子代理/记忆）+ 按 `sandboxBackend` 选文件系统 spec；`selfEvolve` 决定只读/可写。RAG 按 `ai_app.rag_mode`（GENERIC/AGENTIC/BOTH）挂中间件或注册工具。配置变更经 `ConfigChangedEvent` 失效缓存。
- **沙箱**：`SandboxSpecResolver` 按 `sandboxBackend` 找 `SandboxBackendProvider`（`AiConfigure.SandboxConfig` 各后端 `@ConditionalOnClass` 装配）构建 spec；后端不可用回退 HOST。
- **状态存储**：`StateStoreProvider` 按 `state-store.type` 解析后端（`AiConfigure.StateStoreConfig` 各后端 `@ConditionalOnClass` 装配）；分布式后端依赖对应 AgentScope 扩展，缺失时告警回退 MEMORY。
- **流式对话**：内部 ChatRun 已选定应用和 Agent，直接调用 `HarnessAgent.streamEvents`，把 `AgentEvent` 转成 AG-UI SSE；
  AgentScope 按 `(userId, ChatSession.id)` 保护核心状态调用。浏览器断开只解除订阅，后台 Run 继续执行并周期保存展示快照。
- **会话标识**：内部 ChatRun 的 `RuntimeContext.sessionId` 直接使用 `ChatSession.id`；`runId` 只标识一次业务回合。
  `gw-*` 只属于外部 Channel 的 Gateway 路由路径。
- **外部通道**：内置钉钉/飞书/企微适配器（`AiConfigure.ChannelAdapterConfig`，各 `@ConditionalOnClass`）+ 下游自定义 `Channel` Bean，`ChannelLifecycle` 自动注册到 `ChannelManager` 并经共享 `HarnessGateway` 路由到 LF agent（靠 `preferredAgentId` 或 `ChannelConfig.defaultAgentId` 指定 `app:{appId}:t:{tenantId}`）。通道→agent 的绑定由 `channel` 包的 `ai_channel_config` 表管理。
- **主动出站**：`POST /v1/ai/outbound/send`（`{sessionId, messages}` 回推会话最近入站通道，或 `{channelId, to, messages}` 显式投递）。内部 SSE 通道为请求/响应模型，不支持 proactive push 到 Web 端。
- **自演化审计**：selfEvolve 应用每轮对话后，`WorkspaceAuditRecorder` 扫描 workspace 中本轮变更文件，复制快照写入 `ai_app_workspace_audit`。

## 能力详解

### LLM 提供方与模型
提供方类型 `dashscope`/`openai`/`ollama`/`anthropic`/`gemini`/`deepseek`/`glm`/`kimi`/`minimax`；模型类型 `CHAT`/`EMBEDDING`/`IMAGE_GEN`/`VIDEO_GEN`/`AUDIO_GEN`。`ProviderType.supportedModelTypes` 描述厂商理论模型目录；管理端可选项和媒体模型保存校验使用 `MediaToolAdapterRegistry` 从真实适配器注册键推导的 `runtimeModelTypes` / `runtimeMediaOperations`。VECTOR/HYBRID 知识库使用的 EMBEDDING 模型必须配置默认向量维度，新建知识库会采用并固化该维度（也可在建库时显式覆盖）；FULL_TEXT 知识库不需要嵌入模型。API Key 使用 AES-GCM 加密存储；`ModelResolver` 通过 AgentScope `ModelRegistry` 按 modelId 解析 CHAT 模型，`EmbeddingModelResolver` 解析 EMBEDDING 模型（向量 RAG 用）。端点 `/v1/ai/llm-providers`、`/v1/ai/llm-models`。

### 智能应用
应用字段：`modelId` `imageModelId` `videoModelId` `audioModelId` `systemPrompt` `maxIters` `temperature` `toolsAllow` `toolsDeny` `mcpServerIds` `knowledgeBaseIds` `ragMode` `subAgentIds` `skillsAllow` `skillsDeny` `appType` `selfEvolve` `sandboxBackend` `audience`(B/C/ALL) `ownerId` `enabled`。CRUD 端点 `/v1/ai/apps`（ROLE_DEV，平台 ISV 管理全局能力）；用户可用应用 `GET /v1/ai/apps/available`（登录，按 audience+角色/owner 过滤）。`appType` 创建后不可变；其余字段可调。

### 媒体生成
- 对话接口没有媒体专用请求字段或前端创作控件；图片、视频、语音请求均作为普通用户消息进入统一 ReAct 链路，模型依据真实 ToolSchema 自主选择工具和填写参数。
- `ToolkitAssembler` 为 Web Chat 和 Channel 构建不同工具集：Web Chat 可挂载应用绑定的 AgentScope 官方媒体工具和配置独立、默认关闭的 `qwen_text_to_image`；Channel 不暴露依赖 ChatRun/附件物化的工具。知识库、本地、媒体和 MCP 等全部已注册来源最后统一受应用 `toolsAllow` / `toolsDeny` 控制。
- 官方 `ImageBlock` / `VideoBlock` / `AudioBlock` 结果由统一媒体物化链路写入 AI 文件存储（LOCAL / OSS）。客户端接收平台附件 `DataBlock` 和签名预览地址；模型侧结果由平台重新构造，只包含稳定 attachmentId，不透传供应商文本、metadata、临时 URL 或平台签名 URL。

### MCP 服务
传输 `stdio`/`sse`/`http`/`streamable_http`；`ToolkitAssembler` 按应用装载，单个失败不影响其余；`POST /v1/ai/mcp-servers/{id}/test` 测连通性。

### 知识库（RAG）
- 内容检索策略 `ai_knowledge_base.retrieval_mode`：`FULL_TEXT`（Lucene/SmartChineseAnalyzer/BM25，无需嵌入模型）、`VECTOR`（默认，向量余弦召回）、`HYBRID`（两路候选按 RRF 融合）。
- 文档上传只切割一次，再按策略写入 Lucene 和/或向量库；向量库支持 MEMORY（默认）/ PGVECTOR / OCEANBASE，原文件支持 LOCAL（默认）/ OSS。
- 检索模式 `ai_app.rag_mode`：`GENERIC`（默认，中间件自动检索注入）/ `AGENTIC`（注册 `retrieve_knowledge` 工具，模型自主调用）/ `BOTH`（两者兼有）。
- 默认关闭，由 `lambda.fusion.ai.rag.enabled` 开启；关闭时管理 CRUD 仍可用，检索注入不装配。
- Lucene 由 `lambda.lucene.enabled` 独立控制，索引为本地可重建投影，面向单机部署；多实例不要共同写入同一网络目录。
- 端点：`/v1/ai/knowledge-bases`、`/v1/ai/knowledge-bases/{kbId}/documents`（上传/下载/删除）。
- 详见 [RAG 文档切割与来源契约](../docs/design/rag-document-chunking.md)。

### 子代理
DB 驱动子代理定义（`ai_sub_agent`：`name`/`description`/`prompt`/`modelId`/`steps`/`toolsAllow`/`skillsAllow`/`workspaceMode`）。按应用绑定（`ai_app.sub_agent_ids`，仅 WORKSPACE 型），`AgentFactory` 构建期注册为 `SubagentDeclaration`，主 agent 经 harness 子代理体系（`agent_spawn`/`agent_send`）调度，**路由由 LLM 按 description 自主决策**。同名 `workspace/subagents/*.md` 文件覆盖 DB 声明。端点 `/v1/ai/sub-agents`。详见 SKILL.md「子代理」。

`expose_to_user` 发布事件由 Lambda Fusion 应用层补全应用、租户、用户和父会话信息，不修改 AgentScope 源码。记录中的子 Agent 类型会转换为带应用与租户作用域的内部标识，其他节点据此重建正确的父 Agent，再按其声明恢复子 Agent。直接对话必须通过 `FusionSubagentGateway`，同时校验应用、租户和用户；远程 Workspace 模式下使用同一 `DistributedStore` 的执行锁串行化跨节点请求。

升级前已生成、但未经过 Lambda Fusion 暴露事件补全的旧记录不会参与全局恢复；客户端需从父 Agent 重新生成新的 `subagentId`。平台外部通道若需要开放子 Agent 直接对话，也必须在其认证调用链中补全同样的业务身份后再调用该入口。

### 技能市场
技能仓库源按部署形态选择：`MYSQL`（默认）/ `POSTGRES`（可读写，admin CRUD）/ `GIT` / `NACOS`（只读 catalog）。`type=NONE` 或扩展未引入时技能市场禁用（WORKSPACE app 仅用 workspace 本地技能）。端点 `/v1/ai/skills`。

### 通道配置
`ai_channel_config` 表管理外部通道→agent 的路由绑定（按 channelId）。内置钉钉/飞书/企微通道适配器（`AiConfigure.ChannelAdapterConfig`，各 `@ConditionalOnClass`，扩展未引入时不装配）。端点 `/v1/ai/channel-configs`。

### 对话
`POST /v1/ai/sessions` 创建、`GET /v1/ai/sessions/page` 列表、`GET /v1/ai/sessions/{id}/messages` 历史、
`POST /v1/ai/sessions/{id}/chat`（SSE）流式对话。ChatRun 是 AgentScope 之上的业务状态：本地内存事件用于实时输出，
数据库快照用于页面恢复，状态仅有 `RUNNING / COMPLETED / STOPPED / FAILED`；AgentScope 的 `ASKING` 不复制成业务状态。
本模块不实现节点心跳、租约、远程停止、旧调用接管或全局维护扫描。多实例下若请求未落回执行实例，返回持久化
bootstrap 后关闭 SSE；用户可显式停止旧 Run 并创建新 Run。已持久化在 AgentScope `ASKING` 边界的 Run
可在进程重启后由用户显式确认并开始下一阶段，这不恢复旧阶段或自动执行工具。详见
[对话运行时设计](../docs/design/chat-runtime.md)。

### Workspace（WORKSPACE 型）
- 首次对话时按运营商自动脚手架（per-`tenantId` workspace）：`AGENTS.md`/`skills/`/`subagents/`/`memory/`/`knowledge/`/`tools.json`。
- 文件管理 API（ROLE_DEV，需指定 `tenantId` 查看对应运营商的 workspace）：
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
    enabled: true                                # FULL_TEXT/HYBRID 所需；本地单机索引
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
        store:
          type: MEMORY                              # MEMORY（默认）/ PGVECTOR / OCEANBASE
          pg-vector: { jdbc-url, username, password, schema } # 仅 PGVECTOR 需要
          ocean-base: { jdbc-url, username, password } # 仅 OCEANBASE 需要
        document-storage:
          type: LOCAL                               # LOCAL（默认）/ OSS
          local: { root }                           # 默认 ${workspace.root}/knowledge-files
          oss:   { client-name, key-prefix }        # 默认前缀 ai/knowledge/
```

| 项目 | 说明 |
| --- | --- |
| `security.encryption-key` | 未配置时启动告警，加密 API Key 时抛异常 |
| `audience.{b,c}-roles` | B/C 端角色名列表，用于 app 可见性过滤（下游自定义角色名） |
| `gateway.enabled` | 是否启用 HarnessGateway（默认 true）；关闭后无轮次串行锁与外部通道能力 |
| `state-store.type` | Agent 多轮记忆后端；分布式后端（MYSQL/POSTGRES/REDIS/OSS/COS）依赖对应 AgentScope 扩展，缺失回退 MEMORY |
| `memory.flush.mode` | WORKSPACE 自演化应用的长期记忆提取策略；默认 THROTTLED，可选 ALWAYS/THROTTLED/NEVER |
| `memory.flush.min-gap` | THROTTLED 模式最小刷新间隔，默认 10m；实际发生刷新时仍会等待本次记忆提取完成 |
| `sandbox.*` | 各沙箱后端凭据；对应扩展需在 classpath（ai 模块已 optional 引入） |
| `skill.repository.type` | 技能仓库源；`NONE` 或扩展缺失时技能市场禁用 |
| `tools.qwen-image.*` | 独立 `qwen_text_to_image` 工具配置；不读取应用媒体模型或 Skill，启用时 key、地址和固定模型名必填 |
| `lambda.lucene.*` | 本地全文索引开关和根目录；仅适合单机，关闭后不装配 Lucene Bean |
| `rag.enabled` | 知识库检索注入总开关（默认 false）；关闭时管理 CRUD 仍可用 |
| `rag.default-retrieval-mode` | 新建知识库未显式指定时的内容检索策略：FULL_TEXT / VECTOR / HYBRID |
| `rag.store.type` | 向量库后端；PGVECTOR 使用 `store.pg-vector.*` JDBC 连接；OCEANBASE 使用 `store.ocean-base.*` JDBC 连接 |
| `rag.document-storage.type` | AI 文件存储后端；知识库原文件、聊天附件和工具生成媒体共用 LOCAL / OSS |
| `chat.attachment.generated-media.*` | 工具结果 URL/Base64 媒体物化的大小、连接、请求和重定向限制 |
| `chat.attachment.preview.*` | 附件与生成媒体预览签名密钥和有效期；OSS 预览会二次重定向到短期预签名地址 |
| 数据源 | 元数据表使用 MySQL `master`；pgvector 连接走 `rag.store.pg-vector.*`；Lucene 不依赖 MySQL 全文索引版本 |

## 数据库对象

| 表 | 隔离 | 说明 |
| --- | --- | --- |
| `ai_llm_provider` `ai_llm_model` | 全局 | LLM 提供方/模型 |
| `ai_app` | 全局 | 智能应用（含 `audience`/`owner_id`/`knowledge_base_ids`/`rag_mode`/`sub_agent_ids`） |
| `ai_mcp_server` | 全局 | MCP 服务 |
| `ai_sub_agent` | 全局 | 子代理定义 |
| `ai_channel_config` | 全局 | 通道→agent 路由绑定 |
| `ai_knowledge_base` `ai_knowledge_document` | 全局 | 知识库/文档行 |
| `ai_chat_session` `ai_chat_message` | `tenant_id` | 会话/消息 |
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

## 错误码

`30000-39999` 段。枚举见 `com.lambda.fusion.ai.exception.AiErrorCode`。

## 扩展点

- **自定义本地工具**：类上标注 `@AiTool`（组合 `@Component`，须在组件扫描范围内），方法上标 AgentScope `@Tool`，`ToolkitAssembler` 启动时自动扫描；需 HITL 确认时方法级加 `@RequireConfirm` 或类级 `@AiTool(requireConfirm = true)`。按应用绑定、不参与全局扫描的工具（如 `KnowledgeRetrievalTool`）不加 `@AiTool`。
- **自定义沙箱后端**：实现 `SandboxBackendProvider`，在 `AiConfigure.SandboxConfig` 中仿照现有后端加一个带 `@ConditionalOnClass` 的嵌套配置类。
- **自定义状态存储后端**：在 `AiConfigure.StateStoreConfig` 中加嵌套配置类（对齐 MYSQL/POSTGRES 等现有实现）。
- **自定义外部通道**：实现 `io.agentscope.harness.agent.gateway.channel.Channel`，声明为 Spring Bean，`ChannelLifecycle` 自动注册到 `ChannelManager` 并经 `HarnessGateway` 路由。入站消息在 `preferredAgentId`（或 `ChannelConfig.defaultAgentId`）填 `app:{appId}:t:{tenantId}` 指定目标 agent。
- **换向量库后端**：改 `SimpleKnowledgeAdapter#createStore`（见 SKILL.md）。
- **扩展文档原文件存储**：实现 `DocumentFileStorage` 注册为 Bean（按 `type()` 路由）。

## 源码定位

| 模块 | 路径 |
| --- | --- |
| 自动配置入口 | `autoconfig/AiAutoConfiguration.java` |
| 模块装配 | `AiConfigure.java`（含 Gateway/Sandbox/StateStore/ChannelAdapter/Rag/SkillRepository 嵌套配置） |
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
| Workspace | `runtime/workspace/` |
| RAG | `rag/` 包 |
| 媒体生成工具 | `runtime/tools/ImageGenerationTool.java` / `runtime/tools/VideoGenerationTool.java` / `runtime/tools/AudioGenerationTool.java` |
| 工具媒体物化 | `chat/runtime/media/` |
| AI 文件存储 | `storage/` |
| 子代理 | `subagent/` 包 |
| 技能市场 | `skill/` 包 |
| 流式对话 | `chat/service/impl/ChatServiceImpl.java` |
| 主动出站 | `chat/controller/OutboundController.java` |
| 数据库变更 | `META-INF/db/changelogs/lambda-ai-changelog.xml` |

## 路线图

已完成：CHAT / WORKSPACE(ASSISTANT+AUTONOMOUS) / 自演化审计 / 多后端沙箱(Docker+K8s+E2B+Daytona+AgentRun) / audience+owner_id 可见性 / **知识库 RAG（Lucene FULL_TEXT + 向量 VECTOR + RRF HYBRID，原文件 LOCAL+OSS）** / **子代理（DB 驱动 + REST）** / **技能市场（MYSQL/POSTGRES/GIT/NACOS）** / **通道配置管理 + 钉钉/飞书/企微适配器** / **Agent 状态存储多后端（MEMORY/FILE/MYSQL/POSTGRES/REDIS/OSS/COS）** / **Workspace 分布式存储（LOCAL/MYSQL/POSTGRES）** / **Lambda 层子 Agent 共享注册、身份补全、跨节点恢复与执行串行** / HarnessGateway（per-session 串行 + 外部通道 SPI + 主动出站）。

待跟进：
- `WakeupDispatcher` + `MessageBus`（异步子代理完成 announce、团队消息、定时触发）
- 真实平台通道适配器打磨（钉钉/飞书/企微下游落地）
- 应用分享与权限（run/edit/fork）
- 成本/Token 统计
