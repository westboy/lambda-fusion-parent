package com.lambda.fusion.workflow.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.Data;

/**
 * 通过命令
 *
 * @author jin
 */
@Data
@Schema(description = "通过命令")
public class ApproveCommand {

    @Schema(description = "审批意见")
    private String message;

    @Schema(description = "流程变量（供后续条件分支使用）")
    private Map<String, Object> variable;
}
