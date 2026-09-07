package com.lambda.fusion.logger.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import com.lambda.cloud.mvc.execption.BusinessException;

/**
 * Logger 模块业务异常，继承 BusinessException，基于错误码与参数化消息构造
 *
 * @author zx
 */
public class LoggerBusinessException extends BusinessException {

    public LoggerBusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public LoggerBusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public LoggerBusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getCode(), errorCode.getMessage(), null, cause);
    }

    public LoggerBusinessException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode.getCode(), errorCode.getMessage(), args, cause);
    }
}
