package com.lambda.fusion.message.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import com.lambda.cloud.mvc.execption.BusinessException;

/**
 * Message 模块业务异常
 * <p>
 * 继承自统一的 BusinessException，用于 Message 模块的业务异常处理
 *
 * @author jin
 */
public class MessageBusinessException extends BusinessException {

    /**
     * 使用错误码构造异常
     *
     * @param errorCode 错误码枚举
     */
    public MessageBusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    /**
     * 使用错误码和参数构造异常
     *
     * @param errorCode 错误码枚举
     * @param args      消息参数
     */
    public MessageBusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    /**
     * 使用错误码和原始异常构造异常
     *
     * @param errorCode 错误码枚举
     * @param cause     原始异常
     */
    public MessageBusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getCode(), errorCode.getMessage(), null, cause);
    }

    // ========== 消息相关便捷方法 ==========

    /**
     * 便捷方法：消息不存在异常
     *
     * @param messageId 消息ID
     * @return MessageBusinessException
     */
    public static MessageBusinessException messageNotFound(String messageId) {
        return new MessageBusinessException(MessageErrorCode.MESSAGE_NOT_FOUND, messageId);
    }

    /**
     * 便捷方法：消息收件人不能为空异常
     *
     * @return MessageBusinessException
     */
    public static MessageBusinessException recipientsEmpty() {
        return new MessageBusinessException(MessageErrorCode.MESSAGE_RECIPIENTS_EMPTY);
    }

    /**
     * 便捷方法：消息渠道不存在异常
     *
     * @param channel 渠道标识
     * @return MessageBusinessException
     */
    public static MessageBusinessException channelNotFound(String channel) {
        return new MessageBusinessException(MessageErrorCode.MESSAGE_CHANNEL_NOT_FOUND, channel);
    }

    // ========== 公告相关便捷方法 ==========

    /**
     * 便捷方法：公告不存在异常
     *
     * @param announcementId 公告ID
     * @return MessageBusinessException
     */
    public static MessageBusinessException announcementNotFound(String announcementId) {
        return new MessageBusinessException(MessageErrorCode.ANNOUNCEMENT_NOT_FOUND, announcementId);
    }

    /**
     * 便捷方法：公告状态不允许该操作异常
     *
     * @return MessageBusinessException
     */
    public static MessageBusinessException announcementStateInvalid() {
        return new MessageBusinessException(MessageErrorCode.ANNOUNCEMENT_STATE_INVALID);
    }

    /**
     * 便捷方法：公告可见范围不支持异常
     *
     * @param scopeType 范围类型
     * @return MessageBusinessException
     */
    public static MessageBusinessException scopeNotSupported(String scopeType) {
        return new MessageBusinessException(MessageErrorCode.ANNOUNCEMENT_SCOPE_NOT_SUPPORTED, scopeType);
    }

    // ========== 系统错误便捷方法 ==========

    /**
     * 便捷方法：参数无效异常
     *
     * @param message 错误消息
     * @return MessageBusinessException
     */
    public static MessageBusinessException invalidParameter(String message) {
        return new MessageBusinessException(MessageErrorCode.INVALID_PARAMETER, message);
    }
}
