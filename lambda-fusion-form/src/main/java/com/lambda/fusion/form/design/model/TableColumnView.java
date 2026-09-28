package com.lambda.fusion.form.design.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 物理表列元数据视图（选表导列）
 *
 * @author Zx
 */
@Data
@AllArgsConstructor
@Schema(description = "物理表列元数据")
public class TableColumnView {

    @Schema(description = "列名（大写）")
    private String columnName;

    @Schema(description = "列注释")
    private String remarks;

    @Schema(description = "数据库类型（如 varchar/int/datetime）")
    private String dbType;

    @Schema(description = "建议的字段类型（FORM 白名单枚举）")
    private String suggestedFieldType;

    @Schema(description = "是否主键")
    private Boolean primaryKey;

    @Schema(description = "是否可空")
    private Boolean nullable;

    @Schema(description = "列长度（数值型为精度）")
    private Integer length;
}
