package com.lambda.fusion.message.notification.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息主表实体（写扩散源）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_messages")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class MessageEntity extends BaseEntity {

    @TableId
    private String id;

    @TableField("TENANT_ID")
    private String tenantId;

    /** 消息分类：system/user/biz */
    @TableField("CATEGORY")
    private String category;

    /** 级别：info/important/urgent */
    @TableField("LEVEL")
    private String level;

    @TableField("TITLE")
    private String title;

    @TableField("CONTENT")
    private String content;

    /** 正文类型：text/html/markdown */
    @TableField("CONTENT_TYPE")
    private String contentType;

    @TableField("SENDER")
    private String sender;

    @TableField("BIZ_TYPE")
    private String bizType;

    @TableField("BIZ_ID")
    private String bizId;

    @TableField("REMARKS")
    private String remarks;
}
