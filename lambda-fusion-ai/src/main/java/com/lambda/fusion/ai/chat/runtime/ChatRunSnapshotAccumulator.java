package com.lambda.fusion.ai.chat.runtime;

import com.lambda.fusion.ai.AiConstants.ChatRunToolStatus;
import com.lambda.fusion.ai.chat.runtime.agui.InterruptFactory;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshotSanitizer;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行中快照投影器：消费已经标准化的 AG-UI 事件，生成浏览器恢复所需的最小投影。
 *
 * @author Jin
 */
public final class ChatRunSnapshotAccumulator {

    private final String runId;
    private String aguiRunId;
    private int phaseNo;
    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private String textMessageId;
    private String reasoningMessageId;
    private boolean textOpen;
    private boolean reasoningOpen;
    /** 工具调用的可变中间状态；{@code args} 用 {@link StringBuilder} 原地累积。 */
    private final Map<String, ToolCallState> tools = new LinkedHashMap<>();

    private List<ChatRunSnapshot.ToolCall> pendingTools;
    private List<ChatRunSnapshot.PendingInput> pendingInputs;
    private final java.util.LinkedHashSet<String> generatedAttachmentIds = new java.util.LinkedHashSet<>();

    /**
     * 根据已有快照创建累加器。
     *
     * @param snapshot 已持久化的执行快照
     */
    public ChatRunSnapshotAccumulator(ChatRunSnapshot snapshot) {
        runId = snapshot.runId();
        aguiRunId = snapshot.aguiRunId();
        phaseNo = snapshot.phaseNo();
        text.append(snapshot.text());
        reasoning.append(snapshot.reasoning());
        textMessageId = snapshot.textMessageId();
        reasoningMessageId = snapshot.reasoningMessageId();
        textOpen = snapshot.textOpen();
        reasoningOpen = snapshot.reasoningOpen();
        for (ChatRunSnapshot.ToolCall tool : snapshot.tools()) {
            tools.put(tool.toolCallId(), ToolCallState.fromRecord(tool));
        }
        pendingTools = snapshot.pendingTools();
        pendingInputs = snapshot.pendingInputs();
        generatedAttachmentIds.addAll(snapshot.generatedAttachmentIds());
    }

    /**
     * 开始新的执行阶段。
     *
     * @param nextAguiRunId 新阶段的 AG-UI 运行标识
     * @param nextPhaseNo 新阶段号
     */
    public void beginPhase(String nextAguiRunId, int nextPhaseNo) {
        aguiRunId = nextAguiRunId;
        phaseNo = nextPhaseNo;
        pendingTools = List.of();
        pendingInputs = List.of();
        closeOpenMessages();
    }

    /**
     * 追加回复文本。
     *
     * @param messageId 文本消息标识
     * @param delta 增量文本
     */
    private void appendText(String messageId, String delta) {
        textMessageId = messageId;
        textOpen = true;
        text.append(safe(delta));
    }

    /**
     * 追加推理文本。
     *
     * @param messageId 推理消息标识
     * @param delta 增量文本
     */
    private void appendReasoning(String messageId, String delta) {
        reasoningMessageId = messageId;
        reasoningOpen = true;
        reasoning.append(safe(delta));
    }

    /** 关闭当前文本和推理消息；仅经 {@code beginPhase} 与 {@code apply} 的增量驱动。 */
    public void closeOpenMessages() {
        closeText();
        closeReasoning();
    }

    /** 记录已物化生成附件，使生命周期恢复不再反向解析工具结果展示文本。 */
    public void addGeneratedAttachmentId(String attachmentId) {
        if (attachmentId != null) {
            generatedAttachmentIds.add(attachmentId);
        }
    }

    /** 关闭当前文本消息。 */
    private void closeText() {
        textOpen = false;
    }

    /** 关闭当前推理消息。 */
    private void closeReasoning() {
        reasoningOpen = false;
    }

    /** 应用一批已标准化的 AG-UI 事件。 */
    public void apply(List<AguiEvent> events) {
        if (events != null) {
            events.forEach(this::apply);
        }
    }

    /**
     * 应用单个已标准化的 AG-UI 事件，按事件类型分派到文本、推理、工具调用或中断投影。
     *
     * <p>文本与推理互斥：任一侧开始即关闭另一侧的未闭合消息。生命周期、状态和自定义事件
     * 不属于浏览器恢复快照，直接忽略。
     *
     * @param event 已标准化的 AG-UI 事件
     */
    private void apply(AguiEvent event) {
        switch (event) {
            case AguiEvent.TextMessageStart start -> {
                closeReasoning();
                textMessageId = start.messageId();
                textOpen = true;
            }
            case AguiEvent.TextMessageContent content -> appendText(content.messageId(), content.delta());
            case AguiEvent.TextMessageEnd ignored -> closeText();
            case AguiEvent.ReasoningMessageStart start -> {
                closeText();
                reasoningMessageId = start.messageId();
                reasoningOpen = true;
            }
            case AguiEvent.ReasoningMessageContent content -> appendReasoning(content.messageId(), content.delta());
            case AguiEvent.ReasoningMessageEnd ignored -> closeReasoning();
            case AguiEvent.ReasoningEnd ignored -> closeReasoning();
            case AguiEvent.ToolCallStart start ->
                upsertTool(start.toolCallId(), start.toolCallName(), null, null, ChatRunToolStatus.RUNNING.getCode());
            case AguiEvent.ToolCallArgs args -> appendToolArgs(args.toolCallId(), args.delta());
            case AguiEvent.ToolCallEnd end -> setToolStatus(end.toolCallId(), ChatRunToolStatus.COMPLETE.getCode());
            case AguiEvent.ToolCallResult result -> setToolResult(result.toolCallId(), result.content());
            case AguiEvent.RunFinished finished -> applyInterrupts(finished.outcome());
            default -> {
                // 生命周期、状态和自定义事件不属于浏览器恢复快照。
            }
        }
    }

    /** 追加工具调用参数增量：在已有参数尾部原地拼接 {@code delta}，状态保持 RUNNING。 */
    private void appendToolArgs(String toolCallId, String delta) {
        ToolCallState state = tools.computeIfAbsent(toolCallId, id -> new ToolCallState());
        state.args.append(safe(delta));
        state.status = ChatRunToolStatus.RUNNING.getCode();
    }

    /** 补写工具调用结果并置为 COMPLETE。 */
    private void setToolResult(String toolCallId, String result) {
        upsertTool(toolCallId, null, null, result, ChatRunToolStatus.COMPLETE.getCode());
        sanitizeCompletedTool(toolCallId);
    }

    /** 仅更新工具调用状态。 */
    private void setToolStatus(String toolCallId, String status) {
        upsertTool(toolCallId, null, null, null, status);
        if (ChatRunToolStatus.COMPLETE.getCode().equals(status)) {
            sanitizeCompletedTool(toolCallId);
        }
    }

    /**
     * 工具进入 COMPLETE 终态后内容不再变化：立即脱敏并把结果写回中间状态，之后每次快照构建
     * 不再对大内容做完整脱敏（记录构造器的兜底脱敏对 ≤2048 的已脱敏内容开销小）。
     */
    private void sanitizeCompletedTool(String toolCallId) {
        ToolCallState current = tools.get(toolCallId);
        if (current == null) {
            return;
        }
        ChatRunSnapshot.ToolCall sanitized = ChatRunSnapshotSanitizer.sanitizeTool(current.toRecord(toolCallId));
        current.toolCallName = sanitized.toolCallName();
        current.args.setLength(0);
        current.args.append(sanitized.args());
        current.result = sanitized.result();
        current.status = sanitized.status();
    }

    /**
     * 处理阶段结束事件中的中断结果：按 reason 分流为待确认（ASKING 投影，{@code pendingTools}）
     * 与待用户输入（AWAITING_INPUT 投影，{@code pendingInputs}），同时关闭未闭合的文本与推理消息。
     *
     * @param outcome 阶段结束结果；非中断结果（正常完成等）不改变投影
     */
    private void applyInterrupts(AguiEvent.RunFinishedOutcome outcome) {
        if (!(outcome instanceof AguiEvent.RunFinishedInterruptOutcome(List<AguiEvent.Interrupt> interrupts))) {
            return;
        }
        List<ChatRunSnapshot.ToolCall> asks = new ArrayList<>();
        List<ChatRunSnapshot.PendingInput> inputs = new ArrayList<>();
        for (AguiEvent.Interrupt interrupt : interrupts) {
            if (InterruptFactory.REASON_INPUT_REQUIRED.equals(interrupt.reason())) {
                upsertAwaitingInputTool(interrupt);
                inputs.add(toPendingInput(interrupt));
            } else {
                asks.add(upsertAskingTool(interrupt));
            }
        }
        pendingTools = List.copyOf(asks);
        pendingInputs = List.copyOf(inputs);
        closeOpenMessages();
    }

    /** 以 ASKING 状态补写工具调用并返回其快照投影。 */
    private ChatRunSnapshot.ToolCall upsertAskingTool(AguiEvent.Interrupt interrupt) {
        String toolCallId = interrupt.toolCallId() == null ? interrupt.id() : interrupt.toolCallId();
        ToolCallState current = findTool(toolCallId);
        Object metadataName =
                interrupt.metadata() == null ? null : interrupt.metadata().get("toolName");
        String toolName =
                metadataName == null ? (current == null ? "" : current.toolCallName) : String.valueOf(metadataName);
        upsertTool(toolCallId, toolName, null, null, ChatRunToolStatus.ASKING.getCode());
        return findTool(toolCallId).toRecord(toolCallId);
    }

    /** 以 AWAITING_INPUT 状态补写挂起的交互工具调用。 */
    private void upsertAwaitingInputTool(AguiEvent.Interrupt interrupt) {
        String toolCallId = interrupt.toolCallId() == null ? interrupt.id() : interrupt.toolCallId();
        ToolCallState current = findTool(toolCallId);
        Object metadataName =
                interrupt.metadata() == null ? null : interrupt.metadata().get(InterruptFactory.METADATA_TOOL_NAME);
        String toolName =
                metadataName == null ? (current == null ? "" : current.toolCallName) : String.valueOf(metadataName);
        upsertTool(toolCallId, toolName, null, null, ChatRunToolStatus.AWAITING_INPUT.getCode());
    }

    /** 把输入型 Interrupt 转为待输入投影：保留问题、交互类型与恢复值 JSON Schema。 */
    private ChatRunSnapshot.PendingInput toPendingInput(AguiEvent.Interrupt interrupt) {
        String toolCallId = interrupt.toolCallId() == null ? interrupt.id() : interrupt.toolCallId();
        ToolCallState current = findTool(toolCallId);
        String toolName = current == null ? "" : current.toolCallName;
        Object kind =
                interrupt.metadata() == null ? null : interrupt.metadata().get(InterruptFactory.METADATA_INPUT_KIND);
        return new ChatRunSnapshot.PendingInput(
                toolCallId,
                toolName,
                safe(interrupt.message()),
                kind == null ? InterruptFactory.KIND_TEXT : String.valueOf(kind),
                interrupt.responseSchema() == null
                        ? ""
                        : JsonUtils.getJsonCodec().toJson(interrupt.responseSchema()));
    }

    /**
     * 生成当前执行快照。
     *
     * @return 执行快照
     */
    public ChatRunSnapshot buildSnapshot() {
        return new ChatRunSnapshot(
                runId,
                aguiRunId,
                phaseNo,
                text.toString(),
                reasoning.toString(),
                textMessageId,
                reasoningMessageId,
                textOpen,
                reasoningOpen,
                tools.entrySet().stream()
                        .map(entry -> entry.getValue().toRecord(entry.getKey()))
                        .toList(),
                pendingTools,
                pendingInputs,
                List.copyOf(generatedAttachmentIds));
    }

    /** 判断当前是否处于待交互状态（存在待确认或待用户输入投影）。 */
    public boolean hasPendingInteraction() {
        return !pendingTools.isEmpty() || !pendingInputs.isEmpty();
    }

    /** 查找工具调用的当前状态；不存在时返回 {@code null}。 */
    private ToolCallState findTool(String toolCallId) {
        return tools.get(toolCallId);
    }

    /**
     * 按 {@code toolCallId} 插入或合并工具调用状态：传入 {@code null} 的字段保持不变，
     * 非空字段覆盖；没有已有状态时空字段落到空串（状态除外，仍可能为 {@code null}）。
     *
     * @param toolCallId 工具调用标识
     * @param toolCallName 工具名；{@code null} 表示保持不变
     * @param args 工具参数（全量覆盖，仅供快照恢复路径）；{@code null} 表示保持不变
     * @param result 工具结果；{@code null} 表示保持不变
     * @param status 工具状态码；{@code null} 表示保持不变
     */
    private void upsertTool(String toolCallId, String toolCallName, String args, String result, String status) {
        ToolCallState current = tools.computeIfAbsent(toolCallId, id -> new ToolCallState());
        if (toolCallName != null) {
            current.toolCallName = safe(toolCallName);
        }
        if (args != null) {
            current.args.setLength(0);
            current.args.append(args);
        }
        if (result != null) {
            current.result = safe(result);
        }
        if (status != null) {
            current.status = status;
        }
    }

    /** 把 {@code null} 规整为空串，避免快照投影携带空值。 */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 工具调用的可变中间状态：参数用 {@link StringBuilder} 原地累积，避免流式参数事件
     * 每次到达都做全量字符串拼接（O(n²) 拷贝）；仅在构建快照时物化为不可变记录。
     */
    private static final class ToolCallState {

        private String toolCallName;
        private final StringBuilder args = new StringBuilder();
        private String result;
        private String status;

        static ToolCallState fromRecord(ChatRunSnapshot.ToolCall record) {
            ToolCallState state = new ToolCallState();
            state.toolCallName = record.toolCallName();
            state.args.append(safe(record.args()));
            state.result = record.result();
            state.status = record.status();
            return state;
        }

        ChatRunSnapshot.ToolCall toRecord(String toolCallId) {
            return new ChatRunSnapshot.ToolCall(toolCallId, toolCallName, args.toString(), result, status);
        }
    }
}
