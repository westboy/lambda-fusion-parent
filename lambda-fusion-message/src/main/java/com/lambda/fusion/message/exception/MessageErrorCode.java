package com.lambda.fusion.message.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Message 模块错误码枚举
 * <p>
 * 错误码规则：Message 模块使用 14101-14199 范围（config 模块占用 14001-14005）
 *
 * @author jin
 */
@Getter
@AllArgsConstructor
public enum MessageErrorCode implements ErrorCode {

    // ========== 消息相关错误 (14101-14149) ==========
    /**
     * 消息不存在
     */
    MESSAGE_NOT_FOUND(14101, "消息不存在"),

    /**
     * 消息收件人不能为空
     */
    MESSAGE_RECIPIENTS_EMPTY(14102, "消息收件人不能为空"),

    /**
     * 消息渠道不存在
     */
    MESSAGE_CHANNEL_NOT_FOUND(14103, "消息渠道不存在"),

    /**
     * 消息模板不存在
     */
    MESSAGE_TEMPLATE_NOT_FOUND(14104, "消息模板不存在"),

    /**
     * 消息模板编码已存在
     */
    MESSAGE_TEMPLATE_CODE_EXISTS(14105, "消息模板编码已存在"),

    // ========== 公告相关错误 (14150-14189) ==========
    /**
     * 公告不存在
     */
    ANNOUNCEMENT_NOT_FOUND(14151, "公告不存在"),

    /**
     * 公告状态不允许该操作
     */
    ANNOUNCEMENT_STATE_INVALID(14152, "公告状态不允许该操作"),

    /**
     * 公告可见范围不支持
     */
    ANNOUNCEMENT_SCOPE_NOT_SUPPORTED(14153, "公告可见范围不支持"),

    // ========== 系统错误 (14190-14199) ==========
    /**
     * 系统内部错误
     */
    SYSTEM_ERROR(14190, "系统内部错误"),

    /**
     * 参数无效
     */
    INVALID_PARAMETER(14193, "参数无效");

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 错误消息
     */
    private final String message;
}
