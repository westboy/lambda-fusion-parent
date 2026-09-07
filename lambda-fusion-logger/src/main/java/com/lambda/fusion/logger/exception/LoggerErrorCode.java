package com.lambda.fusion.logger.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Logger 模块错误码枚举，错误码占用 15000-15999 段
 *
 * @author zx
 */
@Getter
@AllArgsConstructor
public enum LoggerErrorCode implements ErrorCode {
    SYSTEM_ERROR(15900, "系统内部错误"),

    INVALID_PARAMETER(15901, "参数无效"),

    LOG_STORAGE_NOT_SUPPORTED(15902, "不支持的日志存储类型");

    private final Integer code;

    private final String message;
}
