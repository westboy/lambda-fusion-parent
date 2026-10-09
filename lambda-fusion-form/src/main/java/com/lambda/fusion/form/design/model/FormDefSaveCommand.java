package com.lambda.fusion.form.design.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 表单草稿保存命令（def + fields + relations 全量覆盖）
 *
 * @author Zx
 */
@Data
@Schema(description = "表单草稿保存命令")
public class FormDefSaveCommand {

    @NotBlank(message = "表单标识不能为空")
    @Size(max = 64, message = "表单标识不能超过 64 字符")
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "表单标识只能由小写字母、数字、下划线组成且以字母开头")
    @Schema(description = "表单标识（租户内唯一）")
    private String formKey;

    @NotBlank(message = "显示名不能为空")
    @Size(max = 128, message = "显示名不能超过 128 字符")
    @Schema(description = "显示名")
    private String formName;

    @NotBlank(message = "表单类型不能为空")
    @Schema(description = "SINGLE / TREE / MASTER_SUB")
    private String formType;

    @NotBlank(message = "目标表不能为空")
    @Size(max = 64, message = "目标表名不能超过 64 字符")
    @Schema(description = "目标物理表名")
    private String tableName;

    @Size(max = 64, message = "数据源 id 不能超过 64 字符")
    @Schema(description = "目标数据源注册 id（空=平台主库；创建后不可修改）")
    private String datasourceId;

    @Schema(description = "TREE 类型：树数据源 JSON")
    private String treeConfig;

    @Schema(description = "流程表单标识：允许被流程定义挂载（发布后生效）")
    private Boolean processForm;

    @Size(max = 256, message = "标题模板不能超过 256 字符")
    @Schema(description = "流程标题模板")
    private String titleTemplate;

    @Size(max = 128, message = "拦截器 bean 名不能超过 128 字符")
    @Schema(description = "数据写入拦截器 bean 名（FormDataInterceptor 实现，可空）")
    private String interceptor;

    @Size(max = 64, message = "逻辑删除列名不能超过 64 字符")
    @Schema(description = "逻辑删除列名（空=物理删除）；目标表必须含该列")
    private String logicDeleteColumn;

    @Schema(description = "列表级配置 JSON：布局/分页/按钮等")
    private String listConfig;

    @Schema(description = "详情呈现配置 JSON：{mode:dialog/drawer/full,width,columns,systemCollapsed}")
    private String detailConfig;

    @Schema(description = "表单与详情共享布局 JSON：{columns, items:[{field,span}]}")
    private String formLayout;

    @Schema(description = "保存时同步发布（默认 false）")
    private Boolean publishOnSave;

    @Schema(description = "乐观锁版本（更新时必传，新建忽略）")
    private Long version;

    @NotNull(message = "字段列表不能为空")
    @Schema(description = "字段列表")
    private List<@Valid FormFieldItem> fields = new ArrayList<>();

    @Schema(description = "主从关系列表")
    private List<@Valid FormRelationItem> relations = new ArrayList<>();
}
