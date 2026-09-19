# 媒体生成调用架构

## 核心决策

聊天接口只接收普通消息正文和附件，不定义 `mediaGeneration` 等媒体专用字段，也不在前端提供媒体创作模式。
用户用自然语言描述图片、视频或语音需求，请求沿统一的 AgentScope ReAct 链路执行，由模型依据当前 Toolkit
中真实的 ToolSchema 选择工具并填写参数。

链路不创建 `ToolUseBlock`，不使用 `ToolChoice.Specific`，不通过应用媒体绑定强制路由，也不手工合成
Agent 记忆、工具事件或媒体意图提示。工具是否可用由 Toolkit 注册和应用工具策略决定，而不是由聊天请求协议决定。

## 职责边界

### 对话界面

- 对话框只负责普通消息和附件输入，不展示与执行能力脱节的“AI 创作”类型选择。
- 历史消息直接展示服务端保存的原始正文，不解析旧版媒体 JSON，也不维护兼容分支。
- 没有匹配工具或工具调用失败时，模型应依据实际执行结果如实说明。

### 工具协议

- 工具自身的 ToolSchema 是名称、参数和调用协议的唯一执行权威。
- 应用的 `imageModelId`、`videoModelId`、`audioModelId` 只负责把已接通的官方工具挂入 Toolkit。
- 独立自定义工具只读取自身配置，不读取应用媒体绑定、模型表或 Skill 配置。
- Skill 可以建议模型是否使用工具，但不负责注册工具，也不形成工具依赖。
- 所有本地工具、知识库工具、独立工具、应用绑定工具和 MCP 工具注册完成后，统一应用 `toolsAllow` / `toolsDeny`；
  黑名单优先，非空白名单只保留明确列出的工具。

## 调用链

```text
SendMessage(content + attachmentIds)
  -> Bean Validation（要求正文或附件至少存在一项）
  -> ChatRun 保存原始用户消息
  -> ChatRunCoordinator
  -> AgentExecutionAdapter.stream(原始用户消息)
  -> HarnessAgent.streamEvents
  -> 模型依据 Toolkit ToolSchema 自主决定是否调用匹配工具
  -> MaterializingMediaTool
       - 将上游临时 URL / Base64 保存为平台附件
       - 通过 ToolEmitter 向客户端发送附件 DataBlock
       - 给下一轮模型只保留文本和稳定 attachmentId
  -> 模型继续推理并生成最终答复
```

## 工具来源

### 应用绑定的官方工具

`MediaGenerationBindingResolver` 校验应用绑定的模型、提供方启用状态、API Key 和当前运行时实现范围。
现阶段接通 DashScope 图片、视频、语音以及 OpenAI 图片工具。模型名和凭据来自应用绑定记录，但这些记录
不会进入用户提示，也不决定模型必须调用哪个工具。

`MediaToolAdapterRegistry` 的 `(ProviderType, MediaGenerationOperation)` 注册键同时负责适配器创建、
`runtimeMediaOperations` 展示和服务端保存校验。厂商理论上支持、但当前没有注册适配器的能力不得创建为
可绑定媒体模型；例如 MiniMax 在实现独立协议适配器前不提供媒体模型选项。

### 独立 Qwen Image 工具

`qwen_text_to_image` 是普通的独立工具：

- 配置前缀为 `lambda.fusion.ai.tools.qwen-image`，默认关闭；
- 启用时 API Key、base URL、固定的厂商模型名和超时必须由自身配置提供；
- 模型名不作为工具参数交给 LLM，也不从平台模型表或应用媒体绑定查询；
- 只挂载到具备会话、租户和 Run 上下文的 Web Chat Toolkit，以复用附件物化；Channel 使用独立 Agent/Toolkit，
  不暴露 Qwen 和应用绑定媒体工具；
- 是否对某个应用可用，最终由该应用的工具白黑名单决定；
- 不进入全局 `@AiTools` 列表，因此不会被定时 Agent 以不同的结果语义调用。

Qwen、MiniMax 等协议的请求结构和响应结构不同。新增厂商时应实现对应的普通工具或官方适配器，不能把
不同协议塞入一个假兼容工具。

## 媒体结果契约

上游生成地址通常短期有效，工具调用成功后必须先完成平台附件存储。客户端与模型使用两个明确投影：

- 客户端接收物化后的完整 `DataBlock`，其中预览地址指向平台附件接口，负责实际媒体展示；
- 模型只接收平台重新构造的文本结果和稳定 `attachmentId`；上游 TextBlock、metadata、临时 URL 和会过期的
  签名预览 URL 均不进入模型结果。

这种投影避免 OpenAI-compatible 模型因 `role=tool` 的多模态内容返回 400，也避免模型把内部签名地址写进
Markdown 答复。结果装饰器不实现禁止重复调用的硬编码状态机；模型是否再次使用工具由正常 ReAct 上下文、
工具结果和用户消息共同决定。如果用户在后续消息明确要求重做，模型可正常再次调用工具。

## 验证重点

- 前端不包含媒体创作控件、媒体专用请求字段或旧媒体 JSON 解析器。
- `SendMessage` 只承载 `content`、`clientRequestId` 和 `attachmentIds`，不提交模型、工具名或厂商参数。
- 应用安全视图不返回媒体能力副本；没有应用媒体绑定时，独立工具仍可按工具策略参与选择。
- 用户消息正文按原样持久化并传给 Agent，执行只有统一的 ReAct `stream(Msg)` 路径。
- 所有工具来源（包括知识库工具）注册完成后才应用应用级白黑名单，独立 Qwen 工具同样受控。
- Channel Agent 使用独立缓存和 Toolkit，不包含依赖 ChatRun 的附件、Qwen 或应用绑定媒体工具。
- 模型管理前后端都以当前运行时能力为可配置边界。
- 真实两轮 ReAct 中，客户端事件包含平台附件 `DataBlock`，第二次模型调用只包含文本和 attachmentId。
- 模型上下文不包含上游 URL、`preview` 签名 URL 或禁止再次调用的硬编码结果策略。
- 前端只使用工具事件中的平台附件地址展示媒体，不为生成图片额外渲染消息气泡背景。
