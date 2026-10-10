package com.lambda.fusion.ai.runtime.workspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_app_workspace_audit")
@Schema(description = "应用 workspace 自演化审计")
public class WorkspaceAuditEntity {

    @TableId(value = "id", type = IdType.INPUT)
    @Schema(description = "主键")
    private Long id;

    @TableField("tenant_id")
    @Schema(description = "租户ID")
    private String tenantId;

    @TableField("app_id")
    @Schema(description = "应用ID")
    private String appId;

    @TableField("session_id")
    @Schema(description = "触发会话ID")
    private String sessionId;

    @TableField("file_path")
    @Schema(description = "变更文件相对路径")
    private String filePath;

    @TableField("operation")
    @Schema(description = "操作: MODIFIED/CREATED")
    private String operation;

    @TableField("snapshot_path")
    @Schema(description = "快照文件相对路径")
    private String snapshotPath;

    @TableField("operator")
    @Schema(description = "操作者")
    private String operator;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
