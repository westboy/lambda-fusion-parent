package com.lambda.fusion.message.template.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息模板实体：标题/正文支持 ${var} 占位符渲染，渠道附加参数（如短信运营商模板ID）存 CHANNEL_PARAMS JSON
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_message_templates")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class MessageTemplateEntity extends BaseEntity {

    @TableId
    private String id;

    @TableField("TENANT_ID")
    private String tenantId;

    /** 模板编码（业务幂等标识，租户内唯一） */
    @TableField("CODE")
    private String code;

    @TableField("NAME")
    private String name;

    /** 标题模板 */
    @TableField("TITLE")
    private String title;

    /** 正文模板 */
    @TableField("CONTENT")
    private String content;

    /** 正文类型：text/html/markdown */
    @TableField("CONTENT_TYPE")
    private String contentType;

    /** 渠道附加参数 JSON，如 {"sms":{"templateId":"..."}} */
    @TableField("CHANNEL_PARAMS")
    private String channelParams;

    /** 状态：0停用，1启用 */
    @TableField("STATUS")
    private Integer status;

    @TableField("REMARKS")
    private String remarks;
}
