package com.lambda.fusion.ai.apps.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 应用配置变更审计（append-only，非版本机制）。每次创建/更新/删除应用时记录变更前配置快照，
 * 仅供管理端查询历史、支撑人工回退；不参与运行时读取，不构成第二份配置事实，不改变「编辑即生效」语义。
 *
 * @author Jin
 */
@Data
@TableName("ai_app_config_audit")
@Schema(description = "应用配置变更审计")
public class AppConfigAuditEntity {

    @TableId(value = "id", type = IdType.INPUT)
    @Schema(description = "主键")
    private Long id;

    @TableField("tenant_id")
    @Schema(description = "租户ID")
    private String tenantId;

    @TableField("app_id")
    @Schema(description = "应用ID")
    private String appId;

    @TableField("operation")
    @Schema(description = "操作: CREATE/UPDATE/DELETE")
    private String operation;

    @TableField("config_json")
    @Schema(description = "变更前配置快照JSON(CREATE 为初始快照)")
    private String configJson;

    @TableField("operator")
    @Schema(description = "操作者")
    private String operator;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
