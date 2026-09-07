package com.lambda.fusion.logger.storage.mysql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.fusion.core.entity.BaseEntity;
import com.lambda.fusion.logger.storage.OperationLogConverter;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 操作日志实体（la_operation_logs，MySQL 存储实现专属持久化模型）
 *
 * @author zx
 */
@AutoConverter(target = OperationLogRecord.class, converter = OperationLogConverter.class)
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("la_operation_logs")
public class OperationLogEntity extends BaseEntity {

    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("TENANT_ID")
    private String tenantId;

    /** 操作所属业务模块 */
    @TableField("MODULE")
    private String module;

    /** 执行的方法名（类名.方法名） */
    @TableField("METHOD")
    private String method;

    /** 操作描述 */
    @TableField("DESCRIPTION")
    private String description;

    /** HTTP 请求方法 */
    @TableField("HTTP_METHOD")
    private String httpMethod;

    /** 操作详情 */
    @TableField("DETAIL")
    private String detail;

    /** 执行耗时（毫秒） */
    @TableField("COST")
    private Long cost;

    /** 客户端 IP */
    @TableField("IP")
    private String ip;

    /** 操作人用户名 */
    @TableField("OPERATOR")
    private String operator;

    /** 操作人用户ID */
    @TableField("OPERATOR_ID")
    private String operatorId;

    /** 操作时间 */
    @TableField("TIME")
    private LocalDateTime time;
}
