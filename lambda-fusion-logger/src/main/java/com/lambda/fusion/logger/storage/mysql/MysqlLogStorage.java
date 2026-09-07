package com.lambda.fusion.logger.storage.mysql;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.logger.api.OperationLogQuery;
import com.lambda.fusion.logger.storage.LogStorage;
import com.lambda.fusion.logger.storage.OperationLogConverter;
import com.lambda.fusion.logger.storage.mysql.entity.OperationLogEntity;
import com.lambda.fusion.logger.storage.mysql.mapper.OperationLogMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MySQL 日志存储实现（默认）：写落 la_operation_logs，读按条件分页查询同一张表，读写一体。
 * record↔entity 转换经 {@link OperationLogConverter} 的 MapStruct 实现完成（工程契约 §8.1，不手写转换）。
 *
 * <p>写失败仅记录日志，不向调用方抛出，避免影响业务主流程。</p>
 *
 * @author zx
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlLogStorage implements LogStorage {

    private final OperationLogMapper operationLogMapper;

    private final OperationLogConverter operationLogConverter;

    @Override
    public String type() {
        return "MYSQL";
    }

    @Override
    public void saveOperation(OperationLogRecord record) {
        try {
            operationLogMapper.insert(operationLogConverter.toEntity(record));
        } catch (Exception e) {
            log.error("操作日志落库失败: module={}, method={}", record.getModule(), record.getMethod(), e);
        }
    }

    @Override
    public PageView<OperationLogRecord> pageOperationLogs(OperationLogQuery query) {
        LambdaQueryWrapper<OperationLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StrUtil.isNotBlank(query.getModule()), OperationLogEntity::getModule, query.getModule());
        wrapper.like(
                StrUtil.isNotBlank(query.getOperator()),
                OperationLogEntity::getOperator,
                "%" + query.getOperator() + "%");
        wrapper.ge(query.getStartTime() != null, OperationLogEntity::getTime, query.getStartTime());
        wrapper.le(query.getEndTime() != null, OperationLogEntity::getTime, query.getEndTime());
        wrapper.orderByDesc(OperationLogEntity::getTime);
        Page<OperationLogEntity> page =
                operationLogMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageView.of(page, operationLogConverter::toRecord);
    }

    @Override
    public List<String> listModules(String keyword) {
        LambdaQueryWrapper<OperationLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(OperationLogEntity::getModule);
        wrapper.like(StrUtil.isNotBlank(keyword), OperationLogEntity::getModule, keyword);
        wrapper.groupBy(OperationLogEntity::getModule);
        wrapper.orderByDesc(OperationLogEntity::getModule);
        wrapper.last("limit 50");
        return operationLogMapper.selectList(wrapper).stream()
                .map(OperationLogEntity::getModule)
                .filter(StrUtil::isNotBlank)
                .toList();
    }
}
