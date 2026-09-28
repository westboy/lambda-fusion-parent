package com.lambda.fusion.form.design.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动态表单发布快照（不可变）
 *
 * @author Zx
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_form_def_version")
public class FormDefVersionEntity extends BaseEntity {

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("FORM_ID")
    private String formId;

    /** 版本号（表单内单调递增） */
    @TableField("VERSION")
    private Long version;

    /** def + fields + relations 全量快照 JSON（不可变） */
    @TableField("SNAPSHOT_JSON")
    private String snapshotJson;

    /** 内容指纹（发布去重） */
    @TableField("CONTENT_MD5")
    private String contentMd5;

    @TableField("PUBLISHED_BY")
    private String publishedBy;

    @TableField("PUBLISHED_AT")
    private LocalDateTime publishedAt;

    @TableField("TENANT_ID")
    private String tenantId;
}
