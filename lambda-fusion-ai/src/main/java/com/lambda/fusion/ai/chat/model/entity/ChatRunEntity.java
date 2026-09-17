package com.lambda.fusion.ai.chat.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 持久化的一次逻辑对话回合。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_chat_run")
public class ChatRunEntity extends BaseEntity {

    @TableId("id")
    private String id;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("session_id")
    private String sessionId;

    @TableField("client_request_id")
    private String clientRequestId;

    @TableField("request_hash")
    private String requestHash;

    @TableField("user_message_id")
    private Long userMessageId;

    @TableField("assistant_message_id")
    private Long assistantMessageId;

    @TableField("status")
    private String status;

    @TableField("finish_reason")
    private String finishReason;

    @TableField("phase_no")
    private Integer phaseNo;

    @TableField("agui_run_id")
    private String aguiRunId;

    @TableField("snapshot_json")
    private String snapshotJson;

    /** 快照版本围栏：每次快照写入 +1，检查点按版本 CAS，迟到的写入被拒绝。 */
    @TableField("snapshot_version")
    private Long snapshotVersion;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
