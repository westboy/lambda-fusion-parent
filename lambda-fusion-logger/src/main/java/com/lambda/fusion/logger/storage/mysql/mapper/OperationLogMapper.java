package com.lambda.fusion.logger.storage.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.fusion.logger.storage.mysql.entity.OperationLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper（MySQL 存储实现专属）
 *
 * @author zx
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogEntity> {}
