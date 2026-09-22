package com.lambda.fusion.workflow.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import com.lambda.cloud.mvc.execption.BusinessException;

/**
 * Workflow 模块业务异常
 * <p>
 * 继承自统一的 BusinessException，用于 Workflow 模块的业务异常处理
 *
 * @author jin
 */
public class WorkflowBusinessException extends BusinessException {

    /**
     * 使用错误码构造异常
     *
     * @param errorCode 错误码枚举
     */
    public WorkflowBusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 使用错误码与消息参数构造异常
     *
     * @param errorCode 错误码枚举
     * @param args 消息占位符参数
     */
    public WorkflowBusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    /**
     * 使用错误码与根因构造异常
     *
     * @param errorCode 错误码枚举
     * @param cause 根因
     */
    public WorkflowBusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getCode(), errorCode.getMessage(), null, cause);
    }
}
