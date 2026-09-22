package com.lambda.fusion.workflow.task.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.Data;

/**
 * 加签/减签命令（加签：加办理人；减签：移除办理人，接口路径区分）
 *
 * @author jin
 */
@Data
@Schema(description = "加签/减签命令")
public class SignatureCommand {

    @NotEmpty(message = "办理人列表不能为空")
    @Schema(description = "办理人用户名列表（加签为新增人，减签为被移除人）")
    private List<String> handlers;

    @Schema(description = "办理说明")
    private String message;
}
