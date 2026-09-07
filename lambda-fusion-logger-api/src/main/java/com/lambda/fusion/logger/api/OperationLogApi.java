package com.lambda.fusion.logger.api;

import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.cloud.logger.service.OperationService;
import com.lambda.fusion.core.pagination.PageView;
import java.util.List;

/**
 * 远程操作日志服务：{@link OperationService} 经 Dubbo 跨服务暴露的契约，额外提供分页查询。
 *
 * @author zx
 */
public interface OperationLogApi extends OperationService {

    PageView<OperationLogRecord> page(OperationLogQuery query);

    /** 按关键字检索已有业务模块（去重），供下拉远程检索 */
    List<String> listModules(String keyword);
}
