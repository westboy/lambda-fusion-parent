package com.lambda.fusion.workflow.instance.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Data;

/**
 * 发起流程命令
 *
 * @author jin
 */
@Data
@Schema(description = "发起流程命令")
public class StartCommand {

    @NotBlank(message = "流程编码不能为空")
    @Schema(description = "流程编码（对应已发布定义的 flow_code）")
    private String flowCode;

    @NotBlank(message = "业务单据 ID 不能为空")
    @Schema(description = "业务单据 ID（落 flow_instance.business_id）")
    private String businessId;

    @Schema(description = "业务类型（存 ext，供 WorkflowBusinessProvider 路由标题解析）")
    private String bizType;

    @Schema(description = "流程变量（供 skip_condition 条件分支与监听器使用）")
    private Map<String, Object> variable;
}
