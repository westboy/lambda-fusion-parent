package com.lambda.fusion.form.design.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 表单定义迁移载体（导出/导入）
 *
 * <p>导出刻意剥离环境绑定字段（datasourceId/interceptor/version 等），导入侧强制置空并由
 * 保存期校验兜底：目标环境需重新绑定数据源与拦截器后再发布；导入一律创建为新草稿。
 *
 * @param formatVersion 载体格式版本（当前 1）
 * @param def 表单定义（与设计器保存命令同构）
 * @author Zx
 */
@Data
@Schema(description = "表单定义迁移载体")
public class FormDefTransfer {

    /** 当前载体格式版本 */
    public static final int FORMAT_VERSION = 1;

    @Schema(description = "载体格式版本")
    @NotNull
    private int formatVersion;

    @Schema(description = "表单定义")
    @Valid
    @NotNull
    private FormDefSaveCommand def;

    public FormDefTransfer() {}

    public FormDefTransfer(int formatVersion, FormDefSaveCommand def) {
        this.formatVersion = formatVersion;
        this.def = def;
    }
}
