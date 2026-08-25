package com.lambda.fusion.ai.chat.runtime;

import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunFinishReason;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationCommand;
import com.lambda.fusion.ai.chat.model.ChatRunFinalizationResult;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.runtime.event.ChatRunEventStore;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.service.ChatRunStateService;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * 运行终态落库器：从最终 UI 投影生成业务消息，提交目标终态，并在当前 JVM 发布终态事件。
 * 由执行实例在实例锁内调用；抛出 {@code RuntimeException} 表示提交失败，
 * 由实例按退避策略重试。未提交成功（并发落败）时按持久化事实回读并同步运行实体内存字段。
 *
 * @author Jin
 */
public final class ChatExecutionFinalizer {

    private final ChatRunStateService runService;
    private final ChatRunEventStore eventStore;
    private final AiProperties properties;

    /**
     * 创建终态落库器。
     *
     * @param runService 运行状态服务
     * @param eventStore 运行事件存储
     * @param properties AI 模块配置
     */
    public ChatExecutionFinalizer(
            ChatRunStateService runService, ChatRunEventStore eventStore, AiProperties properties) {
        this.runService = runService;
        this.eventStore = eventStore;
        this.properties = properties;
    }

    /**
     * 提交业务终态：数据库事务保存业务消息并清空运行中快照，随后发布本地终态事件，
     * 并同步运行实体的内存字段。
     *
     * @param run 运行实体
     * @param snapshot 已闭合的最终执行快照
     * @param status 目标终态
     * @param reason 结束原因
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     */
    public void commitTerminal(
            ChatRunEntity run,
            ChatRunSnapshot snapshot,
            ChatRunStatus status,
            ChatRunFinishReason reason,
            ChatRunFailureCode errorCode,
            String errorMessage) {
        String toolJson = snapshot.tools().isEmpty()
                ? null
                : JsonUtils.getJsonCodec()
                        .toJson(snapshot.tools().stream()
                                .map(ChatExecutionFinalizer::toPersistedToolCall)
                                .toList());
        ChatRunFinalizationResult result = runService.finalizeExecution(
                run, new ChatRunFinalizationCommand(status, reason, snapshot, toolJson, errorCode, errorMessage));
        run.setStatus(result.status());
        run.setFinishReason(result.finishReason());
        run.setErrorCode(result.errorCode());
        run.setErrorMessage(result.errorMessage());
        if (!result.committed()) {
            ChatRunEntity persisted = runService.loadCurrentOrIdentity(run);
            run.setAguiRunId(persisted.getAguiRunId());
        }
        ChatRunStatus actualStatus = ChatRunStatus.valueOf(run.getStatus());
        AguiEvent terminalEvent = actualStatus == ChatRunStatus.FAILED
                ? new AguiEvent.RunError(
                        run.getSessionId(),
                        run.getAguiRunId(),
                        StringUtils.defaultIfBlank(run.getErrorMessage(), "对话运行失败"),
                        run.getErrorCode())
                : new AguiEvent.RunFinished(
                        run.getSessionId(), run.getAguiRunId(), null, new AguiEvent.RunFinishedSuccessOutcome());
        eventStore.appendTerminalIfAbsent(
                run.getId(), run.getAguiRunId(), terminalEvent, actualStatus.name(), run.getFinishReason());
        eventStore.markTerminal(
                run.getId(), Duration.ofSeconds(properties.getChat().getRun().getTerminalTtlSeconds()));
    }

    private static Map<String, String> toPersistedToolCall(ChatRunSnapshot.ToolCall tool) {
        Map<String, String> record = new LinkedHashMap<>();
        record.put("toolCallId", tool.toolCallId());
        record.put("toolCallName", tool.toolCallName());
        record.put("args", tool.args());
        record.put("result", tool.result());
        return record;
    }
}
