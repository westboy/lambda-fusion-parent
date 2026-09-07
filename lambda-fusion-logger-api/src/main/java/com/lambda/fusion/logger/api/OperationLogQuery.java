package com.lambda.fusion.logger.api;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作日志通用查询条件
 *
 * @author zx
 */
@Getter
@Setter
public class OperationLogQuery {

    /** 页码（从 1 开始）。 */
    private long pageNum = 1;

    /** 每页大小。 */
    private long pageSize = 10;

    /** 业务模块，精确匹配（可空）。 */
    private String module;

    /** 操作人用户名，模糊匹配（可空）。 */
    private String operator;

    /** 起始时间（含，可空）。 */
    private LocalDateTime startTime;

    /** 结束时间（含，可空）。 */
    private LocalDateTime endTime;
}
