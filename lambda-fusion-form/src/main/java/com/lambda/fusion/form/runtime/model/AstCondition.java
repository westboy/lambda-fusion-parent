package com.lambda.fusion.form.runtime.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 查询 AST 条件项
 *
 * @author Zx
 */
@Data
@Schema(description = "查询条件")
public class AstCondition {

    @Schema(description = "字段标识（必须在 queryShow 白名单内）")
    private String field;

    @Schema(description = "操作符：eq/ne/like/in/between/ge/le/isNull/isNotNull")
    private String op;

    @Schema(description = "值；between 为长度 2 数组，in 为数组，isNull/isNotNull 忽略")
    private Object value;
}
