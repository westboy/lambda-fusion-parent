package com.lambda.fusion.ai.chat.model;

import com.lambda.fusion.ai.AiConstants.ChatRunFailureCode;
import com.lambda.fusion.ai.AiConstants.ChatRunFinishReason;
import com.lambda.fusion.ai.AiConstants.ChatRunStatus;
import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;

/**
 * 对话运行终结命令。
 *
 * @param targetStatus 目标状态
 * @param finishReason 结束原因
 * @param snapshot 最终执行快照
 * @param toolCallJson 工具调用记录 JSON
 * @param errorCode 错误码
 * @param errorMessage 错误信息
 * @author Jin
 */
public record ChatRunFinalizationCommand(
        ChatRunStatus targetStatus,
        ChatRunFinishReason finishReason,
        ChatRunSnapshot snapshot,
        String toolCallJson,
        ChatRunFailureCode errorCode,
        String errorMessage) {}
