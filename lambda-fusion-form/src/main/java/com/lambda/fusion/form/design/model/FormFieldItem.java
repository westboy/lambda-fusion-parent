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

    @Schema(description = "校验规则 JSON")
    private String validation;

    @Schema(description = "DICT 类型的字典编码")
    private String dictType;

    @Schema(description = "前端组件名")
    private String uiComponent;

    @Schema(description = "组件属性 JSON")
    private String uiProps;

    @Schema(description = "列表显示（默认 true）")
    private Boolean listShow = Boolean.TRUE;

    @Schema(description = "查询项显示（默认 false）")
    private Boolean queryShow = Boolean.FALSE;

    @Schema(description = "查询默认操作符（eq/ne/like/in/between/ge/le/isNull/isNotNull，空则按类型取默认）")
    private String queryOp;

    @Schema(description = "表单显示（默认 true）")
    private Boolean formShow = Boolean.TRUE;

    @Schema(description = "字段顺序")
    private Integer sort = 0;
}
