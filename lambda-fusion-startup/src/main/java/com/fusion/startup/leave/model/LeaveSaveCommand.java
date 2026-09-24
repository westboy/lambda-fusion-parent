package com.fusion.startup.leave.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 请假单保存命令
 *
 * @author jin
 */
@Data
@Schema(description = "请假单保存命令")
public class LeaveSaveCommand {

    @NotBlank(message = "申请人不能为空")
    @Size(max = 64, message = "申请人不能超过 64 字符")
    @Schema(description = "申请人")
    private String applicant;

    @NotNull(message = "请假天数不能为空")
    @Min(value = 1, message = "请假天数至少 1 天")
    @Schema(description = "请假天数（流程变量 days，供条件分支）")
    private Integer days;

    @NotBlank(message = "请假事由不能为空")
    @Size(max = 500, message = "请假事由不能超过 500 字符")
    @Schema(description = "请假事由")
    private String reason;
}
