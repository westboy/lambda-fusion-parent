package com.lambda.fusion.logger.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.logger.api.OperationLogQuery;
import com.lambda.fusion.logger.service.OperationLogService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作日志查询接口
 *
 * @author zx
 */
@RestController
@RequestMapping({"/logger/operation"})
@Tag(name = "操作日志")
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class OperationLogController {

    private final OperationLogService operationLogService;

    @SaCheckPermission(value = "logger:operation:list")
    @GetMapping({"/page", "/page/{number:\\d+}", "/page/{number:\\d+}/size/{size:\\d+}"})
    @Operation(summary = "分页查询操作日志", description = "按模块、操作人、时间范围分页查询操作日志")
    public PageView<OperationLogRecord> page(
            @PathVariable(required = false) Integer number,
            @PathVariable(required = false) Integer size,
            @Valid OperationLogQuery query) {
        if (number != null) {
            query.setPageNum(number);
        }
        if (size != null) {
            query.setPageSize(size);
        }
        return operationLogService.page(query);
    }

    @SaCheckPermission(value = "logger:operation:list")
    @GetMapping("/modules")
    @Operation(summary = "检索业务模块", description = "按关键字模糊检索已有业务模块（去重），供下拉远程检索")
    public List<String> modules(@RequestParam(required = false) String keyword) {
        return operationLogService.listModules(keyword);
    }
}
