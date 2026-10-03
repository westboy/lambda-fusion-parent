package com.lambda.fusion.form.design.model;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.form.design.entity.FormRelationEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 主从关系项（草稿保存与详情共用）
 *
 * @author Zx
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoConverter(target = FormRelationEntity.class, isReverse = true)
@Schema(description = "主从关系项")
public class FormRelationItem extends BaseVO<FormRelationEntity> {

    @NotBlank(message = "子表单标识不能为空")
    @Size(max = 64, message = "子表单标识不能超过 64 字符")
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "子表单标识只能由小写字母、数字、下划线组成且以字母开头")
    @Schema(description = "子表单 formKey（同租户内解析）")
    private String subFormKey;

    @NotBlank(message = "子表外键列不能为空")
    @Size(max = 64, message = "外键列不能超过 64 字符")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$", message = "外键列只能由字母、数字、下划线组成且以字母开头")
    @Schema(description = "子表外键列")
    private String foreignKey;

    @Schema(description = "CASCADE_DELETE 级联删除 / FORBID_DELETE 禁止删除（默认 CASCADE_DELETE）")
    private String cascadeStrategy = "CASCADE_DELETE";

    @Schema(description = "子表列表呈现配置 JSON：{columns:[{field,width}]}，field 为子表单字段标识（未配置=全量可见字段）")
    private String subTableConfig;
}
