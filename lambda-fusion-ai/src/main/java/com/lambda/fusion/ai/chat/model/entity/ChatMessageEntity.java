package com.lambda.fusion.ai.chat.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_chat_message")
@Schema(description = "对话消息")
public class ChatMessageEntity {

    @TableId(value = "id", type = IdType.INPUT)
    @Schema(description = "主键")
    private Long id;

    @TableField("tenant_id")
    @Schema(description = "租户ID")
    private String tenantId;

    @TableField("session_id")
    @Schema(description = "会话ID")
    private String sessionId;

    @TableField("role")
    @Schema(description = "角色: user/assistant/tool/system")
    private String role;

    @TableField("content")
    @Schema(description = "消息内容")
    private String content;

    @TableField("tool_call")
    @Schema(description = "工具调用 JSON")
    private String toolCall;

    @TableField("tokens_in")
    @Schema(description = "输入 token 数")
    private Integer tokensIn;

    @TableField("tokens_out")
    @Schema(description = "输出 token 数")
    private Integer tokensOut;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
