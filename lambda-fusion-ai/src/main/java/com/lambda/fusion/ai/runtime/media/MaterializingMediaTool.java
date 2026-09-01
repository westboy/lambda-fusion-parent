package com.lambda.fusion.ai.runtime.media;

import com.lambda.fusion.ai.chat.runtime.media.ToolResultMediaMaterializer;
import com.lambda.fusion.ai.chat.runtime.media.ToolResultMediaMaterializer.Context;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * AgentScope 媒体工具结果存储装饰器。
 *
 * <p>代理名称、描述、JSON Schema 和执行语义均来自已注册的 AgentScope 工具；这里只在结果返回
 * Toolkit 前完成平台文件存储。媒体块通过 AgentScope 原生流式事件交给客户端，文本摘要作为最终结果
 * 进入 Agent 记忆，避免把多模态 {@code role=tool} 内容回灌给不兼容的模型。
 *
 * @author Jin
 */
public final class MaterializingMediaTool extends ToolBase {

    private final AgentTool delegate;
    private final ToolResultMediaMaterializer materializer;

    public MaterializingMediaTool(AgentTool delegate, ToolResultMediaMaterializer materializer) {
        super(toolMetadata(delegate));
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.materializer = Objects.requireNonNull(materializer, "materializer");
    }

    private static Builder toolMetadata(AgentTool delegate) {
        Objects.requireNonNull(delegate, "delegate");
        boolean concurrencySafe = !(delegate instanceof ToolBase base) || base.isConcurrencySafe();
        return ToolBase.builder()
                .name(delegate.getName())
                .description(delegate.getDescription())
                .inputSchema(delegate.getParameters())
                .readOnly(delegate.isReadOnly())
                .concurrencySafe(concurrencySafe);
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Context context = materializationContext(param.getRuntimeContext());
        return delegate.callAsync(param)
                .flatMap(result -> materializer.materialize(
                        result, context, param.getToolUseBlock().getId()))
                .map(result -> emitMediaAndProjectForModel(param, result));
    }

    /**
     * 媒体块通过 AgentScope 原生 {@code ToolEmitter} 进入客户端事件流，最终工具结果只保留文本供
     * 下一轮模型推理使用。这样附件仍由平台持久化和展示，同时避免 OpenAI-compatible 模型拒绝
     * role=tool 的多模态内容。模型只接收稳定的附件 ID，不接收面向客户端的临时签名 URL。
     */
    private static ToolResultBlock emitMediaAndProjectForModel(ToolCallParam param, ToolResultBlock result) {
        List<String> attachmentIds = result.getOutput().stream()
                .filter(DataBlock.class::isInstance)
                .map(DataBlock.class::cast)
                .map(DataBlock::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (attachmentIds.isEmpty()) {
            return result;
        }

        param.getEmitter().emit(result);
        ContentBlock modelSummary = TextBlock.builder()
                .text(("The media generation call completed successfully and produced %d media item(s), "
                                + "stored as conversation attachment IDs: %s. "
                                + "The client displays these attachments separately.")
                        .formatted(attachmentIds.size(), attachmentIds))
                .build();
        // 投影结果完全由平台重建，不透传可能包含上游 URL、签名 URL 或提供方私有字段的文本和 metadata。
        return new ToolResultBlock(
                result.getId(), result.getName(), List.of(modelSummary), Map.of(), result.getState());
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        return delegate instanceof ToolBase base
                ? base.checkPermissions(toolInput, context)
                : super.checkPermissions(toolInput, context);
    }

    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        return delegate instanceof ToolBase base
                ? base.matchRule(ruleContent, toolInput)
                : super.matchRule(ruleContent, toolInput);
    }

    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        return delegate instanceof ToolBase base
                ? base.generateSuggestions(toolInput)
                : super.generateSuggestions(toolInput);
    }

    private static Context materializationContext(RuntimeContext runtimeContext) {
        String runId = RuntimeProperty.runId(runtimeContext);
        String tenantId = RuntimeProperty.tenantId(runtimeContext);
        String sessionId = RuntimeProperty._sessionId(runtimeContext);
        if (runId == null || tenantId == null || sessionId == null) {
            throw new IllegalStateException("媒体工具缺少 runId、tenantId 或 sessionId 运行上下文");
        }
        return new Context(runId, sessionId, tenantId);
    }
}
