package com.lambda.fusion.logger.service;

import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.logger.LoggerProperties;
import com.lambda.fusion.logger.api.OperationLogApi;
import com.lambda.fusion.logger.api.OperationLogQuery;
import com.lambda.fusion.logger.storage.LogStorageResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 操作日志落库服务：实现 starter 的 {@code OperationService} SPI（经 {@link OperationLogApi} 契约），
 * 顶掉其默认实现。
 *
 * <p>starter 切面在 AOP finally 中同步调用且不填 tenantId，故在请求线程内先捕获租户
 * （异步线程拿不到 ThreadLocal），再投递异步执行器，经 {@link LogStorageResolver} 路由到当前
 * 存储后端落库；任何异常不外抛，避免影响业务主流程。</p>
 *
 * @author zx
 */
@Slf4j
@Component
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "spring 注入的 configuration properties 为共享单例")
public class OperationLogService implements OperationLogApi {

    private final LogStorageResolver storageResolver;

    private final LoggerProperties properties;

    private final Executor executor;

    public OperationLogService(
            LogStorageResolver storageResolver,
            LoggerProperties properties,
            @Qualifier("loggerExecutor") Executor executor) {
        this.storageResolver = storageResolver;
        this.properties = properties;
        this.executor = executor;
    }

    public PageView<OperationLogRecord> page(OperationLogQuery query) {
        OperationLogQuery storageQuery = new OperationLogQuery();
        storageQuery.setPageNum(query.getPageNum());
        storageQuery.setPageSize(query.getPageSize());
        storageQuery.setModule(query.getModule());
        storageQuery.setOperator(query.getOperator());
        storageQuery.setStartTime(query.getStartTime());
        storageQuery.setEndTime(query.getEndTime());
        return storageResolver.resolve(properties.getStorageType()).pageOperationLogs(storageQuery);
    }

    @Override
    public List<String> listModules(String keyword) {
        return storageResolver.resolve(properties.getStorageType()).listModules(keyword);
    }

    @Override
    public void save(OperationLogRecord record) {
        if (record == null) {
            return;
        }
        // 请求线程内补租户：异步线程 ThreadLocal 拿不到租户上下文
        if (record.getTenantId() == null) {
            record.setTenantId(TenantContextHolder.getCurrentTenantId());
        }
        if (record.getOperator() == null) {
            record.setOperator(record.getOperatorId());
        }

        if (properties.isAsync()) {
            executor.execute(() -> persistSafely(record));
        } else {
            persistSafely(record);
        }
    }

    private void persistSafely(OperationLogRecord record) {
        try {
            storageResolver.resolve(properties.getStorageType()).saveOperation(record);
        } catch (Exception e) {
            log.warn("操作日志落库失败: module={}, method={}", record.getModule(), record.getMethod(), e);
        }
    }
}
