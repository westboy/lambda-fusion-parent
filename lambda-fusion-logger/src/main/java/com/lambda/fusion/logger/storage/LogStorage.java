package com.lambda.fusion.logger.storage;

import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.logger.api.OperationLogQuery;
import java.util.List;

/**
 * 日志存储 SPI。操作日志（登录日志以模块「登录」并入操作日志）读写统一的扩展点，
 * 内置 MYSQL 实现，预留 KAFKA / ES；读写都经 {@link LogStorageResolver} 按 {@code storageType}
 * 路由到同一后端，保证切换后端时读写一致。
 *
 * <p>写失败不应抛出影响业务的异常，记录失败原因即可。</p>
 *
 * @author zx
 */
public interface LogStorage {

    /** 存储类型标识：MYSQL / KAFKA / ES。 */
    String type();

    /** 保存操作日志。 */
    void saveOperation(OperationLogRecord record);

    /**
     * 分页查询操作日志。
     *
     * @param query 通用查询条件（存储后端无关）
     * @return 操作日志分页
     */
    PageView<OperationLogRecord> pageOperationLogs(OperationLogQuery query);

    /**
     * 按关键字检索已有业务模块（去重），供前端下拉远程检索；keyword 为空时返回全量（限前 50）。
     */
    List<String> listModules(String keyword);
}
