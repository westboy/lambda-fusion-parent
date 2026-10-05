package com.lambda.fusion.form.runtime.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 动态数据分页结果（含全量口径合计行）
 *
 * @author Zx
 */
@Getter
@Builder
@Schema(description = "动态数据分页结果")
public class FormDataPageView {

    @Schema(description = "当前页码，从 1 开始")
    private long pageNum;

    @Schema(description = "每页条数")
    private long pageSize;

    @Schema(description = "总记录数")
    private long total;

    @Schema(description = "总页数")
    private long totalPages;

    @Schema(description = "当前页数据")
    private List<Map<String, Object>> records;

    @Schema(description = "合计行（按筛选后的全量集合计算，key=字段标识；数值列 SUM、其余 COUNT）")
    private Map<String, Object> summaries;
}
