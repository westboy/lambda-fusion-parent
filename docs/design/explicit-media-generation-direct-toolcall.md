# 重构:显式多媒体生成改为 Toolkit 直调,顺带补齐多厂商能力

## 架构定位:两层互补能力

多媒体能力分两层,各司其职、不冲突:

- **代码工具层(主干)**:吃 `MediaGenerationRequest` 固定字段、后端注入 apiKey、内部按厂商翻译请求体、
  结果走物化渲染链。服务 CHAT 显式创作(skill/沙箱进不去,只能靠它)与 WORKSPACE 标配厂商。
  可靠、可测试、apiKey 安全。
- **skill + 沙箱层(长尾)**:WORKSPACE 应用里,对没写成代码工具的厂商或特殊玩法(特殊模型、组合参数、
  后处理脚本),由 SKILL.md 指导 Agent 在沙箱(DOCKER/K8s/E2B/DAYTONA/AGENTRUN,HOST 可跑 shell)里
  调 API + 落盘 + 用工作区工具发布。新增厂商零发版,只加一份 SKILL.md。

两层共用底层:WORKSPACE 应用既挂代码工具(`registerMediaGenerationTools` 对所有 app 生效)又能加载 skill,
Agent 优先用已绑定代码工具,遇没覆盖的厂商/玩法再走 skill+沙箱。

**实施顺序**:先做代码工具主干(即本文的直调重构),它顺带解决 ExplicitToolCall 的 deprecated 坑且
CHAT/WORKSPACE 都受益;skill+沙箱作第二步,按厂商配 SKILL.md,工作量小。

## Context(问题)

显式媒体生成(页面选"文生图"+固定参数)现在走的是一条**迂回链**:
`MediaGenerationRequest` → `MediaGenerationRequestValidator` 产出 `ToolUseBlock` →
`ExplicitToolCall`(标记)→ `ExplicitToolCallMiddleware`(用**假模型** `ExplicitToolModel` 骗 agent
"模型决定调此工具",不请求对话模型)→ agent 执行工具 → `ExplicitToolCallHook`(在 deprecated 的
`PostActingEvent` 里 `stopAgent()` 拦停,防止继续推理)。

这条迂回链的根因:AgentScope 2.0.1 没有公开的"强制执行一个已注册工具"入口。它带来三个问题:
1. 依赖 `@Deprecated(forRemoval)` 的 `PostActingEvent`,踩上游未修复的 #2362 坑。
2. 工具实现被 AgentScope 写死的 SDK 绑架(DashScope baseUrl 进不去、OpenAI 只认 dall-e、MiniMax 没工具)。
3. 三件套(Hook/Middleware/标记 record)+ 注册入口,纯粹是为了"直调一个工具"这件本可很简单的事。

**地基已验证**:`Toolkit.callTool(ToolCallParam)` 是公开 API(`Toolkit.java:490`),返回
`Mono<ToolResultBlock>`,内部含工具查找/参数校验/超时重试/异常转换,`ToolCallParam` 可携带
`RuntimeContext`(物化要用的 runId/tenantId/sessionId 照常)。可完全脱离 ReAct 循环直调。

## 目标

把显式多媒体生成从"假模型骗 ReAct + PostActing 拦停"重构为 **`Toolkit.callTool` 直调**,
整体删除 `ExplicitToolCallHook`/`ExplicitToolCallMiddleware`/`ExplicitToolCall` 及注册入口,
摆脱 deprecated `PostActingEvent`。同时把工具实现换成自家 HttpClient 直连,补齐 MiniMax/火山/千问能力。

## 保留(不动)

- **HITL 权限确认**:已确认显式媒体工具是 per-app `new` 的薄适配器,非 `@AiTools` Bean,
  不进 `askToolNames`,本就走 BYPASS 不确认。直调 `callTool` 行为一致,不破坏现有 HITL 设计
  (`buildPermissionContext` 只服务 `@RequireConfirm` 的全局工具,保持不变)。
- **事件流/前端渲染**:前端靠 `AgentEventMapper` 消费 `ToolResultStartEvent/Delta/EndEvent`。
  直调只返回 `Mono<ToolResultBlock>` 不发事件,需在直调前后**手动发射同名事件**喂给 mapper,渲染不变。
- **`MediaGenerationRequest` → `MediaGenerationRequestValidator` 产出 `ToolUseBlock`** 这段不动。

## 设计

### 1. 直调执行器(替代 ExplicitToolCall 三件套)

`AgentExecutionAdapter.streamExplicitMediaTool` 重写:不再 `agent.streamEvents` 驱动 ReAct,改为:
```
emit ToolResultStartEvent(toolCallId)
→ toolkit.callTool(ToolCallParam{ toolUseBlock=mediaToolCall, runtimeContext })
   (经 MaterializingMediaTool 物化,内部拿 RuntimeContext 的 runId/tenantId/sessionId)
→ emit ToolResultEndEvent(toolCallId, resultBlock)
→ 组装成与现状一致的 Flux<AgentEvent>
```
- `toolkit` 从 `agent` 侧获取或装配期传入(待确认 HarnessAgent 是否暴露 Toolkit;若不便,
  由 `ToolkitAssembler.build` 返回/缓存后注入执行器)。
- 事件用 AgentScope 现有 `ToolResultStartEvent`/`ToolResultEndEvent` 类型构造,
  保持 `AgentEventMapper` 映射、前端渲染、`ToolResultMediaMaterializer` 物化全部不变。
- 失败路径:tool 返回 ERROR 块 → 现状由 `streamExplicitMediaTool` 检测 `ToolResultState.ERROR`
  抛错,直调下同样检测并转为失败事件/异常。

### 2. 删除显式调用三件套

- 删 `runtime/media/ExplicitToolCallHook.java`、`ExplicitToolCallMiddleware.java`、`ExplicitToolCall.java`。
- 删 `AgentFactory.registerExplicitToolCallStopHook`(行 483-487)及其调用点。
- `AgentExecutionAdapter`:移除 `ExplicitToolCall` 上下文注入(行 78/86-88)与假模型驱动逻辑。
- `RagMiddleware:41` 对 `ExplicitToolCall` 的判断一并清理(确认其语义后)。

### 3. 多媒体工具换自家实现 + 补厂商

- 工具输入锁死 `MediaGenerationRequest` 固定字段(prompt/size/n/quality...),与厂商无关。
- 新增/替换工具内部按 `providerType` 把固定字段翻译成各家请求体,HttpClient5 直连
  `provider.baseUrl + skill约定相对路径`,apiKey 装配期注入。返回官方 `ImageBlock`(URL 源)交物化。
- `AiConstants.ProviderType`:MINIMAX 等的 `runtimeMediaOperations` 补上对应操作;火山等按需提供枚举。
- `MediaGenerationBindingResolver.isOpenAiDallE`:dall-e 白名单只锁真·OPENAI,放开兼容厂商。
- `ToolkitAssembler.buildMediaTool`:兼容厂商分支走新工具,不再 `throw unsupportedMediaBinding`。

### 4. skill(每厂商一份,可选但推荐)

- 往 `ai_skill` 写调用说明:何时用、prompt 怎么组织、该家 model 名/size 格式/相对路径。
  不碰 apiKey、不让 LLM 拼整包 body。WORKSPACE 应用 `skills_allow` 放行。
- CHAT 显式创作走 `MediaGenerationRequest` 直调,不依赖 skill。

## 涉及文件(预判)

| 操作 | 文件 |
|---|---|
| 改 | `chat/runtime/AgentExecutionAdapter.java`(直调 + 手动发事件) |
| 删 | `runtime/media/ExplicitToolCallHook/Middleware/ExplicitToolCall.java` |
| 改 | `runtime/AgentFactory.java`(删注册入口) |
| 改 | `rag/runtime/RagMiddleware.java`(清理 ExplicitToolCall 判断) |
| 新增 | `runtime/tools/<某厂商>ImageTool` 或统一 OpenAI 兼容工具 |
| 改 | `AiConstants.java` / `MediaGenerationBindingResolver.java` / `ToolkitAssembler.java` |
| 数据 | `ai_skill` 厂商调用说明 |
| 测 | 直调执行器 + 新工具的单测 |

## 风险 / 待验证(已全部验证 ✅)

- **Toolkit 直调入口** ✅:`HarnessAgent.getDelegate()` → `ReActAgent.getToolkit()`
  (`ReActAgent.java:3739` 公开 getter),`Toolkit.callTool(ToolCallParam)` 直出 `Mono<ToolResultBlock>`。
- **手动发射 ToolResult 事件** ✅:`ToolResultStartEvent`/`ToolResultEndEvent` 为公开类、公开构造器
  (id/createdAt/replyId/toolCallId/toolCallName/state),可构造后喂给 `AgentEventMapper`,前端渲染不变。
- **记忆/状态写回**:直调绕过 ReAct 的记忆写入。显式媒体结果需决定是否写回对话记忆;
  可用 `ReActAgent.getAgentState`/`saveAgentState` 手动写回(`AgentExecutionAdapter.denyPendingToolCalls`
  已有直接读写 AgentState 的先例)。**对齐现状**:保持与现行 ReAct 路径一致的可见性,避免前台丢上下文。

## 验证

1. `mvn -pl lambda-fusion-ai -am clean install` 编译 + Spotless/SpotBugs 过。
2. 单测:直调执行器(事件序列、物化、ERROR 兜底)、新工具(固定字段→请求体翻译、URL 提取)。
3. 起 `lambda-fusion-startup`:CHAT 显式文生图( DashScope + 一家兼容厂商)走通且执行完即停;
   WORKSPACE 自主调一家兼容厂商文生图走通;apiKey 不进 prompt/日志。
