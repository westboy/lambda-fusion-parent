package com.fusion.startup.leave.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 请假演示业务错误码枚举
 * <p>
 * 错误码规则：startup 演示业务使用 17000-17009 范围（workflow 占 161xx 之后的首个空闲段）
 *
 * @author jin
 */
@Getter
@AllArgsConstructor
public enum LeaveErrorCode implements ErrorCode {

    // ========== 请假单 (17000-17009) ==========
    /**
     * 请假单不存在
     */
    LEAVE_NOT_FOUND(17000, "请假单不存在"),

    /**
     * 状态机校验失败：仅草稿可编辑/提交/删除（幂等入口）
     */
    LEAVE_STATUS_INVALID(17001, "请假单状态不允许该操作");

    private final Integer code;

    private final String message;
}
