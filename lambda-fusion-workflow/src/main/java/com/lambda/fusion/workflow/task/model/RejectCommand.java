package com.lambda.fusion.workflow.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 退回命令（退回上一节点）
 *
 * @author jin
 */
@Data
@Schema(description = "退回命令")
public class RejectCommand {

    @Schema(description = "退回原因")
    private String message;
}
