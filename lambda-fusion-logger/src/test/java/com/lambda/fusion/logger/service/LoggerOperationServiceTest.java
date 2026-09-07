package com.lambda.fusion.logger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.fusion.logger.LoggerProperties;
import com.lambda.fusion.logger.storage.LogStorage;
import com.lambda.fusion.logger.storage.LogStorageResolver;
import java.util.Date;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link OperationLogService} 落库链路测试：同步执行器下验证记录透传与存储路由。
 */
class LoggerOperationServiceTest {

    private LogStorageResolver storageResolver;
    private LogStorage storage;
    private LoggerProperties properties;
    private OperationLogService service;

    /** 同步执行器：立即在当前线程运行，便于断言。 */
    private final Executor directExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        storageResolver = mock(LogStorageResolver.class);
        storage = mock(LogStorage.class);
        properties = new LoggerProperties();
        properties.setAsync(false); // 同步路径，无需线程
        when(storageResolver.resolve("MYSQL")).thenReturn(storage);
        service = new OperationLogService(storageResolver, properties, directExecutor);
    }

    @Test
    void shouldPassRecordThroughToResolvedStorage() {
        OperationLogRecord record = new OperationLogRecord();
        record.setModule("用户管理");
        record.setMethod("UserController.create");
        record.setDescription("新增用户");
        record.setHttpMethod("POST");
        record.setDetail("{\"username\":\"zhangsan\"}");
        record.setDuration(120L);
        record.setIpAddress("10.0.0.1");
        record.setOperator("zhangsan");
        record.setOperatorId("u-1");
        record.setTenantId("tenant-1");
        record.setTime(new Date());

        service.save(record);

        ArgumentCaptor<OperationLogRecord> captor = ArgumentCaptor.forClass(OperationLogRecord.class);
        verify(storage).saveOperation(captor.capture());
        OperationLogRecord persisted = captor.getValue();
        assertThat(persisted).isSameAs(record);
        assertThat(persisted.getModule()).isEqualTo("用户管理");
        assertThat(persisted.getDuration()).isEqualTo(120L);
        assertThat(persisted.getTenantId()).isEqualTo("tenant-1");
    }

    @Test
    void shouldIgnoreNullRecord() {
        service.save(null);
        verify(storage, org.mockito.Mockito.never()).saveOperation(any());
    }

    @Test
    void shouldSwallowStorageFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(storage)
                .saveOperation(any());

        OperationLogRecord record = new OperationLogRecord();
        record.setModule("m");
        // 不应抛出
        service.save(record);
        verify(storage).saveOperation(any());
    }

    @Test
    void shouldPersistViaExecutorWhenAsyncEnabled() {
        properties.setAsync(true);
        OperationLogRecord record = new OperationLogRecord();
        record.setModule("m");

        service.save(record);

        // directExecutor 立即执行，仍能验证落库
        verify(storage).saveOperation(any(OperationLogRecord.class));
    }
}
