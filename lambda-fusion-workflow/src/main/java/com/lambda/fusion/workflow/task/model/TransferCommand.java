package com.lambda.fusion.workflow.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 转办/委派命令
 *
 * @author jin
 */
@Data
@Schema(description = "转办/委派命令")
public class TransferCommand {

    @NotBlank(message = "目标办理人不能为空")
    @Schema(description = "目标办理人用户名")
    private String targetUsername;

    @Schema(description = "办理说明")
    private String message;
}
