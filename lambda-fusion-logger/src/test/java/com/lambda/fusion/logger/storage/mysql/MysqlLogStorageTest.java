package com.lambda.fusion.logger.storage.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.logger.api.OperationLogQuery;
import com.lambda.fusion.logger.storage.mysql.entity.OperationLogEntity;
import com.lambda.fusion.logger.storage.mysql.entity.OperationLogEntityConverterImpl;
import com.lambda.fusion.logger.storage.mysql.mapper.OperationLogMapper;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link MysqlLogStorage} 读写一体测试：写入映射、分页查询映射与失败吞噬。
 */
class MysqlLogStorageTest {

    private OperationLogMapper mapper;
    private MysqlLogStorage storage;

    @BeforeEach
    void setUp() {
        mapper = mock(OperationLogMapper.class);
        storage = new MysqlLogStorage(mapper, new OperationLogEntityConverterImpl());
    }

    @Test
    void shouldMapRecordFieldsToEntityOnSave() {
        OperationLogRecord record = new OperationLogRecord();
        record.setModule("用户管理");
        record.setMethod("UserController.create");
        record.setDuration(120L);
        record.setIpAddress("10.0.0.1");
        record.setOperator("zhangsan");
        record.setOperatorId("u-1");
        record.setTenantId("tenant-1");
        record.setTime(new Date());

        storage.saveOperation(record);

        ArgumentCaptor<OperationLogEntity> captor = ArgumentCaptor.forClass(OperationLogEntity.class);
        verify(mapper).insert(captor.capture());
        OperationLogEntity entity = captor.getValue();
        assertThat(entity.getModule()).isEqualTo("用户管理");
        assertThat(entity.getMethod()).isEqualTo("UserController.create");
        assertThat(entity.getCost()).isEqualTo(120L);
        assertThat(entity.getIp()).isEqualTo("10.0.0.1");
        assertThat(entity.getOperator()).isEqualTo("zhangsan");
        assertThat(entity.getTenantId()).isEqualTo("tenant-1");
        assertThat(entity.getTime()).isNotNull();
    }

    @Test
    void shouldSwallowSaveFailure() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(OperationLogEntity.class));

        // 不应抛出
        storage.saveOperation(new OperationLogRecord());
    }

    @Test
    void shouldPageAndMapEntitiesBackToRecords() {
        OperationLogEntity entity = new OperationLogEntity();
        entity.setId("log-1");
        entity.setModule("登录");
        entity.setMethod("login:form");
        entity.setOperator("lisi");
        entity.setCost(30L);
        entity.setIp("10.0.0.2");
        entity.setTime(LocalDateTime.now());
        Page<OperationLogEntity> page = new Page<>(2, 10, 21);
        page.setRecords(List.of(entity));
        doReturn(page).when(mapper).selectPage(any(), any(Wrapper.class));

        OperationLogQuery query = new OperationLogQuery();
        query.setPageNum(2);
        query.setModule("登录");

        PageView<OperationLogRecord> view = storage.pageOperationLogs(query);

        assertThat(view.getTotal()).isEqualTo(21);
        assertThat(view.getPageNum()).isEqualTo(2);
        assertThat(view.getPageSize()).isEqualTo(10);
        assertThat(view.getRecords()).hasSize(1);
        OperationLogRecord record = view.getRecords().get(0);
        assertThat(record.getModule()).isEqualTo("登录");
        assertThat(record.getDuration()).isEqualTo(30L);
        assertThat(record.getIpAddress()).isEqualTo("10.0.0.2");
        assertThat(record.getOperator()).isEqualTo("lisi");
        assertThat(record.getTime()).isNotNull();
    }
}
