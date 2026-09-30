package com.lambda.fusion.form.design.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.lambda.fusion.core.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动态表单定义（草稿工作行）
 *
 * @author Zx
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_form_def")
public class FormDefEntity extends BaseEntity {

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    private String id;

    /** 表单标识（租户内唯一，URL 与权限码的一部分） */
    @TableField("FORM_KEY")
    private String formKey;

    @TableField("FORM_NAME")
    private String formName;

    /** SINGLE 单表 / TREE 左树右表 / MASTER_SUB 主从 */
    @TableField("FORM_TYPE")
    private String formType;

    /** 目标物理表名（changelog 治理下已存在的表） */
    @TableField("TABLE_NAME")
    private String tableName;

    /** TREE 类型：树数据源 JSON（字典/自关联字段/另一表单） */
    @TableField("TREE_CONFIG")
    private String treeConfig;

    /** 挂接的 warm-flow 流程编码（P2） */
    @TableField("FLOW_CODE")
    private String flowCode;

    /** DRAFT 草稿 / PUBLISHED 已发布 / DISABLED 停用 */
    @TableField("STATUS")
    private String status;

    /** 对外生效的快照版本号（空=从未发布） */
    @TableField("PUBLISHED_VERSION")
    private Long publishedVersion;

    /** 流程标题模板 */
    @TableField("TITLE_TEMPLATE")
    private String titleTemplate;

    /** FormDataInterceptor bean 名（P2，可空） */
    @TableField("INTERCEPTOR")
    private String interceptor;

    /** 保存草稿时同步发布（默认 false） */
    @TableField("PUBLISH_ON_SAVE")
    private Boolean publishOnSave;

    /** 跳过契约列校验与填充（TENANT_ID + 审计四列）；缺 TENANT_ID 时需同步配置 tenant.ignore-tables */
    @TableField("SKIP_CONTRACT_COLUMNS")
    private Boolean skipContractColumns;

    /** 逻辑删除列名（空=物理删除）；目标表必须含该列 */
    @TableField("LOGIC_DELETE_COLUMN")
    private String logicDeleteColumn;

    /** 列表级配置 JSON：布局/分页/按钮等 */
    @TableField("LIST_CONFIG")
    private String listConfig;

    /** 乐观锁版本（@Version） */
    @Version
    @TableField("VERSION")
    private Long version;

    @TableField("TENANT_ID")
    private String tenantId;
}
