package com.fusion.startup.leave.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import com.lambda.cloud.mvc.execption.BusinessException;

/**
 * 请假演示业务异常
 * <p>
 * 继承自统一的 BusinessException，用于请假演示业务的业务异常处理
 *
 * @author jin
 */
public class LeaveBusinessException extends BusinessException {

    /**
     * 使用错误码构造异常
     *
     * @param errorCode 错误码枚举
     */
    public LeaveBusinessException(ErrorCode errorCode) {
        super(errorCode);
    }
}
