# 多厂商多媒体生成:两层能力架构(SPI + 配置 / skill + 沙箱)

> 前序:`explicit-media-generation-direct-toolcall.md` 已完成显式媒体生成的 Toolkit.callTool 直调重构。
> 本文定义其上的**多厂商扩展架构**。

## 架构总览:两层互补

多媒体生成能力分两层,不是二选一,而是各司其职:

### 第一层:内置工具(SPI 接口 + 配置驱动,用户可扩展)

- **定位**:主干能力。吃 `MediaGenerationRequest` 固定字段(prompt/width/height/count/quality/voice...),
  后端注入厂商 key,结果走物化渲染链(`MaterializingMediaTool` → 平台附件 → 前端渲染)。
- **场景**:CHAT 显式创作(skill/沙箱进不去,只能靠它)+ WORKSPACE 标配厂商。
- **绑定**:与**模型 + 模型场景**(`MediaGenerationOperation` × provider)绑定。
- **可扩展**:平台定义 SPI 接口,接入方实现并注册新厂商;长尾厂商用配置驱动通用 HTTP 工具,只改库不发版。

### 第二层:skill + 沙箱

- **定位**:长尾/特殊能力。厂商 key 注入**环境变量**(skill 里 `$ENV_VAR` 引用,不写明文),
  LLM 从对话/上下文提取参数,在沙箱执行 curl/脚本。
- **场景**:WORKSPACE 应用里没写成代码工具的厂商、异步任务(视频提交→轮询→下载)、特殊玩法、后处理脚本。
- **扩展**:零 Java 改动,新增一份 SKILL.md 即可。

## 参数如何传给 skill(关键机制)

全走 skill 的前提是 LLM 能拿到参数。两个来源:

1. **对话口述**:LLM 读 skill 后自己从用户需求提取("用 wan2.7-image 画、5秒、16:9" → model/duration/ratio)。
   这是 skill 提示词的本职,不改代码。
2. **`mediaGeneration` 结构化参数注入上下文**:现在它只走直调、不进 LLM 上下文。断点在
   `ChatExecutionService.startExecution`(约326行)——`buildUserMsg` 只用 `userMessage.getContent()`,
   没带 mediaGeneration。**改法**:构造用户消息时若 mediaGeneration 非空,把结构化参数渲染成一段文本附进消息,
   让走 skill 的 LLM 能读到确定的 width/height/count/voice 等。

## 第一层:SPI + 配置 详细设计

### SPI 接口(代码实现,常见厂商)

平台定义 SPI,接入方实现"固定字段 → 厂商请求 → 官方媒体块":

```java
public interface MediaGenerationProvider {
    ProviderType type();                                   // 厂商标识
    boolean supports(MediaGenerationOperation op);          // 该厂商运行时接通了哪些操作
    Mono<ToolResultBlock> generate(MediaGenerationContext ctx); // 固定字段 + baseUrl + apiKey(已解密)
}
```

- `MediaGenerationContext`:封装固定字段(prompt/width/height/count/quality/voice...)+ modelName + baseUrl + apiKey。
  apiKey 由装配期 `KeyEncryptionService.decrypt` 注入,不进 prompt。
- 平台内置实现:DashScope(全操作)、OpenAI(文生图)、TokenPlan(文生图)等。
- 接入方扩展:实现接口、注册为 Bean(或 SPI 发现),`ToolkitAssembler` 按 `binding.providerType()` 路由到对应 provider。

### 配置驱动(长尾厂商,免发版)

- 对"同步 HTTP、请求-响应"的长尾厂商,提供一个**通用 HTTP provider 实现**,从 provider/model 配置读
  endpoint 相对路径 + 请求模板 + 响应提取路径,直调。新增厂商只改库配置。
- 异步/结构特殊的(视频轮询)不进通用 provider,留给第二层 skill。

### 装配路由

`ToolkitAssembler.buildMediaTool`:按 `binding.providerType()` 在已注册的 `MediaGenerationProvider` 里
找 `supports(operation)` 的实现;找不到则看是否配置了通用 HTTP 元数据;都没有则降级(skill 兜底)。
产出统一经 `MaterializingMediaTool` 物化。

## 第二层:skill 详细设计

- 每家一份 SKILL.md:触发条件、参数提取指引、该家 model 名/size 格式、相对路径、异步轮询脚本(视频)。
- key 环境变量注入,不写明文。
- 配套 `docs/skills/<name>/SKILL.md` 范本 + 导入 `ai_skill`。

## 与既有链路的衔接

- **`MediaGenerationRequest` → 直调**:保留。CHAT 显式创作走第一层(直调 + 物化),确定可靠。
- **显式创作不二次 HITL**:用户显式选择即确认。
- **WORKSPACE**:第一层工具照常挂载 + skill 渐进披露;Agent 优先用已绑定代码工具,长尾走 skill+沙箱。

## 落地步骤(建议顺序)

1. `mediaGeneration` 注入用户消息上下文(第二层参数传递的前提,改动最小)。
2. 第一层 SPI 接口 + 平台内置实现(先 Token Plan 文生图样板)+ `ToolkitAssembler` 路由。
3. Token Plan 视频/语音 SKILL.md(第二层,覆盖异步/特殊)。
4. 配置驱动通用 provider(待接第二三家、出现重复模式后提炼,避免过早抽象)。
