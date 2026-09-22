package com.lambda.fusion.workflow.definition.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 流程定义保存命令（设计器 JSON 导入）
 *
 * @author jin
 */
@Data
@Schema(description = "流程定义保存命令")
public class DefinitionSaveCommand {

    @NotBlank(message = "设计器 JSON 不能为空")
    @Schema(description = "设计器产出的定义 JSON（flowCode 唯一，重复保存由引擎按版本升级处理）")
    private String defJson;
}
