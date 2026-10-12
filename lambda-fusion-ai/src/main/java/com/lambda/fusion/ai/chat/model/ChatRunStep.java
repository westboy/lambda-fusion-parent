package com.lambda.fusion.ai.chat.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 对话运行执行步骤：推理段、文本段与工具调用按发生顺序的持久化事实，供前端时间线懒加载。
 *
 * <p>步骤由执行实例在检查点、待交互提交与终态收口时增量写入；reasoning/text 步骤的正文互斥分段，
 * 工具步骤在工具结果到达时闭合（挂起未决的工具在终态收口时按当前状态补记）。
 *
 * @param position   Run 内步骤序号（从 1 开始，唯一键 run_id + step_no 幂等）
 * @param phaseNo    执行阶段号
 * @param kind       步骤类型：reasoning/text/tool_call
 * @param content    步骤正文（reasoning/text）；工具步骤为空
 * @param toolCallId 工具调用 ID（仅 tool_call）
 * @param toolName   工具名称（仅 tool_call）
 * @param toolArgs   工具参数 JSON，已脱敏截断（仅 tool_call）
 * @param toolResult 工具结果 JSON，已脱敏截断（仅 tool_call）
 * @param status     工具调用状态（仅 tool_call）
 * @param startedAt  步骤开始时间（事件到达时采集）
 * @param finishedAt 步骤结束时间（事件到达时采集）
 * @author Jin
 */
@Schema(description = "对话运行执行步骤")
public record ChatRunStep(
        int position,
        int phaseNo,
        String kind,
        String content,
        String toolCallId,
        String toolName,
        String toolArgs,
        String toolResult,
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {}
