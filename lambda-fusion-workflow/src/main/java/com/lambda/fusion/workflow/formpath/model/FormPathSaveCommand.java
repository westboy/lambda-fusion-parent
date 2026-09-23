package com.lambda.fusion.workflow.formpath.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 表单注册表保存命令
 *
 * @author jin
 */
@Data
@Schema(description = "表单注册表保存命令")
public class FormPathSaveCommand {

    @NotBlank(message = "表单名称不能为空")
    @Size(max = 100, message = "表单名称不能超过 100 字符")
    @Schema(description = "表单名称（设计器下拉显示）")
    private String formName;

    @NotBlank(message = "表单路径不能为空")
    @Size(max = 255, message = "表单路径不能超过 255 字符")
    @Schema(description = "表单路径/前端路由（落 flow_definition.form_path）")
    private String formPath;

    @Size(max = 255, message = "备注不能超过 255 字符")
    @Schema(description = "备注")
    private String remarks;
}
