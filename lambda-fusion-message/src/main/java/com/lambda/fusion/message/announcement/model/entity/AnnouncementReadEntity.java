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
 * 公告已读记录实体
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_announcement_reads")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class AnnouncementReadEntity extends BaseEntity {

    @TableId
    private String id;

    @TableField("ANNOUNCEMENT_ID")
    private String announcementId;

    @TableField("TENANT_ID")
    private String tenantId;

    @TableField("READER")
    private String reader;

    @TableField("READ_TIME")
    private LocalDateTime readTime;
}
