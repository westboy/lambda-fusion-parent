package com.lambda.fusion.form.design.model;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.form.design.entity.FormFieldEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单字段项（草稿保存与详情共用）
 *
 * @author Zx
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoConverter(target = FormFieldEntity.class, isReverse = true)
@Schema(description = "表单字段项")
public class FormFieldItem extends BaseVO<FormFieldEntity> {

    @NotBlank(message = "字段标识不能为空")
    @Size(max = 64, message = "字段标识不能超过 64 字符")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "字段标识只能由字母、数字、下划线组成且以字母开头")
    @Schema(description = "字段标识（前端 vModel）")
    private String fieldName;

    @Size(max = 128, message = "字段显示名不能超过 128 字符")
    @Schema(description = "业务显示名")
    private String label;

    @NotBlank(message = "列名不能为空")
    @Size(max = 64, message = "列名不能超过 64 字符")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "列名只能由字母、数字、下划线组成且以字母开头")
    @Schema(description = "物理列映射")
    private String columnName;

    @NotBlank(message = "字段类型不能为空")
    @Schema(description = "STRING/TEXT/INT/DECIMAL/DATE/DATETIME/DICT/BOOL/FILE")
    private String fieldType;

    @Schema(description = "可空（默认 true）")
    private Boolean nullable = Boolean.TRUE;

    @Schema(description = "默认值")
    private String defaultValue;

    @Schema(description = "DICT 类型的字典编码")
    private String dictType;

    @Schema(description = "查询区配置 JSON：{show, op, defaultValue}")
    private String queryConfig;

    @Schema(description = "列表列配置 JSON：{show, width, align, format}")
    private String listConfig;

    @Schema(description = "表单与详情配置 JSON：{show, component, props, rules, readonly}")
    private String formConfig;

    /** 关联字段配置 JSON：{targetFormKey, displayField}（仅 LOOKUP 类型） */
    private String lookupConfig;

    @Schema(description = "是否落库（默认 true；false=不入库/纯展示或计算字段）")
    private Boolean persisted = Boolean.TRUE;

    @Schema(description = "一对一扁平模式的从表归属（空=主表字段；FLAT 关系时为子表单 formKey）")
    private String subFormKey;

    @Schema(description = "字段顺序")
    private Integer sort = 0;
}
