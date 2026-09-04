package com.lambda.fusion.message.announcement.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知公告实体（读扩散，可见性判定在读取时经 MessageRecipientResolver 解析）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_announcements")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class AnnouncementEntity extends BaseEntity {

    @TableId
    private String id;

    @TableField("TENANT_ID")
    private String tenantId;

    @TableField("TITLE")
    private String title;

    @TableField("CONTENT")
    private String content;

    /** 正文类型：text/html/markdown */
    @TableField("CONTENT_TYPE")
    private String contentType;

    /** 可见范围：all全员/user指定用户 */
    @TableField("SCOPE_TYPE")
    private String scopeType;

    /** 范围目标 JSON 数组（用户名列表），all 时为空 */
    @TableField("SCOPE_TARGETS")
    private String scopeTargets;

    /** 发布状态：0草稿，1已发布，2已撤回 */
    @TableField("PUBLISH_STATUS")
    private Integer publishStatus;

    @TableField("PUBLISH_TIME")
    private LocalDateTime publishTime;

    @TableField("EFFECTIVE_FROM")
    private LocalDateTime effectiveFrom;

    @TableField("EFFECTIVE_TO")
    private LocalDateTime effectiveTo;

    /** 是否置顶：0否，1是 */
    @TableField("IS_TOP")
    private Integer isTop;

    @TableField("ORDER_NO")
    private Integer orderNo;

    @TableField("ENABLED")
    private Integer enabled;

    @TableField("REMARKS")
    private String remarks;
}
