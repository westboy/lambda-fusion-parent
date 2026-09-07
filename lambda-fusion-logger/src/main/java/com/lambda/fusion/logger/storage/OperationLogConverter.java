package com.lambda.fusion.logger.storage;

import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.fusion.logger.storage.mysql.entity.OperationLogEntity;
import org.mapstruct.Mapping;

/**
 * 操作日志 record↔entity 双向转换契约，由 {@code OperationLogEntity} 上的
 * {@code @AutoConverter(converter = ...)} 生成 Spring 托管的 MapStruct 实现
 * （record 来自 starter 无法标注 {@code @AutoConverter}，故方法声明在本接口）。
 *
 * @author zx
 */
public interface OperationLogConverter {

    /** record → entity（落库方向） */
    @Mapping(target = "cost", source = "duration")
    @Mapping(target = "ip", source = "ipAddress")
    OperationLogEntity toEntity(OperationLogRecord record);

    /** entity → record（查询出参方向） */
    @Mapping(target = "duration", source = "cost")
    @Mapping(target = "ipAddress", source = "ip")
    OperationLogRecord toRecord(OperationLogEntity entity);
}
