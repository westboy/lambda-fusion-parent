package com.lambda.fusion.ai.chat.runtime.snapshot;

import java.util.List;

/**
 * 对话执行快照。
 *
 * @param runId 运行标识
 * @param aguiRunId AG-UI 运行标识
 * @param phaseNo 执行阶段号
 * @param text 已生成的回复文本
 * @param reasoning 已生成的推理文本
 * @param textMessageId 文本消息标识
 * @param reasoningMessageId 推理消息标识
 * @param textOpen 文本消息是否处于打开状态
 * @param reasoningOpen 推理消息是否处于打开状态
 * @param tools 工具调用状态
 * @param pendingTools 待确认工具调用
 * @param pendingInputs 待用户输入的挂起工具调用
 * @param generatedAttachmentIds 当前运行已物化的生成附件 ID
 * @author Jin
 */
public record ChatRunSnapshot(
        String runId,
        String aguiRunId,
        int phaseNo,
        String text,
        String reasoning,
        String textMessageId,
        String reasoningMessageId,
        boolean textOpen,
        boolean reasoningOpen,
        List<ToolCall> tools,
        List<ToolCall> pendingTools,
        List<PendingInput> pendingInputs,
        List<String> generatedAttachmentIds) {

    /** 归一化可空文本和工具调用集合。 */
    public ChatRunSnapshot {
        text = text == null ? "" : text;
        reasoning = reasoning == null ? "" : reasoning;
        tools = ChatRunSnapshotSanitizer.sanitizeTools(tools);
        pendingTools = ChatRunSnapshotSanitizer.sanitizePendingTools(pendingTools);
        pendingInputs = ChatRunSnapshotSanitizer.sanitizePendingInputs(pendingInputs);
        generatedAttachmentIds = ChatRunSnapshotSanitizer.sanitizeAttachmentIds(generatedAttachmentIds);
    }

    /**
     * 创建空执行快照。
     *
     * @param runId 运行标识
     * @param aguiRunId AG-UI 运行标识
     * @param phaseNo 执行阶段号
     * @return 空执行快照
     */
    public static ChatRunSnapshot empty(String runId, String aguiRunId, int phaseNo) {
        return new ChatRunSnapshot(
                runId, aguiRunId, phaseNo, "", "", null, null, false, false, List.of(), List.of(), List.of(),
                List.of());
    }

    /**
     * 生成下一确认阶段的持久化快照：保留累计内容与工具历史，关闭消息并清除待确认与待输入投影。
     *
     * @param nextAguiRunId 下一阶段 AG-UI 运行标识
     * @param nextPhaseNo 下一阶段号
     * @return 下一阶段快照
     */
    public ChatRunSnapshot beginPhase(String nextAguiRunId, int nextPhaseNo) {
        return new ChatRunSnapshot(
                runId,
                nextAguiRunId,
                nextPhaseNo,
                text,
                reasoning,
                textMessageId,
                reasoningMessageId,
                false,
                false,
                tools,
                List.of(),
                List.of(),
                generatedAttachmentIds);
    }

    /** 判断当前是否存在未决交互（待确认或待输入）。 */
    public boolean hasPendingInteraction() {
        return !pendingTools.isEmpty() || !pendingInputs.isEmpty();
    }

    /**
     * 工具调用快照。
     *
     * @param toolCallId 工具调用标识
     * @param toolCallName 工具名称
     * @param args 工具参数 JSON
     * @param result 工具结果 JSON
     * @param status 工具调用状态
     */
    public record ToolCall(String toolCallId, String toolCallName, String args, String result, String status) {}

    /**
     * 待用户输入的挂起工具调用快照。
     *
     * @param toolCallId 工具调用标识
     * @param toolCallName 工具名称
     * @param question 展示给用户的问题
     * @param inputKind 交互类型：single_choice / multi_choice / text
     * @param responseSchemaJson 恢复值 JSON Schema
     */
    public record PendingInput(
            String toolCallId, String toolCallName, String question, String inputKind, String responseSchemaJson) {}
}
