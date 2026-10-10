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

    @Schema(description = "随办理提交的表单数据（动态表单单据，按当前节点可填写白名单校验后回写业务行并并入流程变量）")
    private Map<String, Object> formData;
}
