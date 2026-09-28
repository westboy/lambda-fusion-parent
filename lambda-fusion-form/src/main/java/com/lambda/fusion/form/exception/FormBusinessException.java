package com.lambda.fusion.form.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import com.lambda.cloud.mvc.execption.BusinessException;

/**
 * Form 模块业务异常
 *
 * @author Zx
 */
public class FormBusinessException extends BusinessException {

    /**
     * 使用错误码构造异常
     *
     * @param errorCode 错误码枚举
     */
    public FormBusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 使用错误码与消息参数构造异常
     *
     * @param errorCode 错误码枚举
     * @param args 消息占位符参数
     */
    public FormBusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }
}
