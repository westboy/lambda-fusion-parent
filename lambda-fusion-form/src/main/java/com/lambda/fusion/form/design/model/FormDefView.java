package com.lambda.fusion.form.design.model;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单草稿详情视图（def + fields + relations）
 *
 * @author Zx
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoConverter(target = FormDefEntity.class, isReverse = true)
@Schema(description = "表单草稿详情")
public class FormDefView extends BaseVO<FormDefEntity> {

    @Schema(description = "表单ID")
    private String id;

    @Schema(description = "表单标识（租户内唯一）")
    private String formKey;

    @Schema(description = "显示名")
    private String formName;

    @Schema(description = "SINGLE / TREE / MASTER_SUB")
    private String formType;

    @Schema(description = "目标物理表名")
    private String tableName;

    @Schema(description = "TREE 类型：树数据源 JSON")
    private String treeConfig;

    @Schema(description = "挂接的 warm-flow 流程编码（P2）")
    private String flowCode;

    @Schema(description = "DRAFT / PUBLISHED / DISABLED")
    private String status;

    @Schema(description = "对外生效的快照版本号（空=从未发布）")
    private Long publishedVersion;

    @Schema(description = "流程标题模板")
    private String titleTemplate;

    @Schema(description = "FormDataInterceptor bean 名（P2，可空）")
    private String interceptor;

    @Schema(description = "乐观锁版本")
    private Long version;

    @Schema(description = "租户")
    private String tenantId;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "修改人")
    private String updatedBy;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "字段列表")
    private List<FormFieldItem> fields = new ArrayList<>();

    @Schema(description = "主从关系列表")
    private List<FormRelationItem> relations = new ArrayList<>();
}
