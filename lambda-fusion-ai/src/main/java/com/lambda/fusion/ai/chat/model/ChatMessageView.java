package com.lambda.fusion.ai.chat.model;

import com.lambda.fusion.ai.chat.model.entity.ChatMessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 历史消息视图：平铺 {@link ChatMessageEntity} 全字段（前端无感迁移），并携带该消息的附件列表。
 *
 * @author Jin
 */
@Data
@Schema(description = "对话消息(含附件)")
public class ChatMessageView {

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "会话ID")
    private String sessionId;

    @Schema(description = "角色: user/assistant/tool/system")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "工具调用 JSON")
    private String toolCall;

    @Schema(description = "输入 token 数")
    private Integer tokensIn;

    @Schema(description = "输出 token 数")
    private Integer tokensOut;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "归属运行的开始时间（助手消息经 Run 反查；前端收起态总耗时展示用）")
    private LocalDateTime runStartedAt;

    @Schema(description = "归属运行的结束时间")
    private LocalDateTime runFinishedAt;

    @Schema(description = "该消息的附件列表")
    private List<ChatAttachmentView> attachments;

    public static ChatMessageView of(ChatMessageEntity entity, List<ChatAttachmentView> attachments) {
        ChatMessageView view = new ChatMessageView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setSessionId(entity.getSessionId());
        view.setRole(entity.getRole());
        view.setContent(entity.getContent());
        view.setToolCall(entity.getToolCall());
        view.setTokensIn(entity.getTokensIn());
        view.setTokensOut(entity.getTokensOut());
        view.setCreatedAt(entity.getCreatedAt());
        view.setAttachments(attachments);
        return view;
    }
}
