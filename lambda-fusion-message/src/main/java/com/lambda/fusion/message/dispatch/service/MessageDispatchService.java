package com.lambda.fusion.message.dispatch.service;

import com.lambda.fusion.message.dispatch.model.MessageSendCommand;
import com.lambda.fusion.message.notification.model.vo.Message;

/**
 * 消息发送编排服务
 *
 * @author jin
 */
public interface MessageDispatchService {

    /**
     * 发送消息：落库消息主表 → 按渠道路由投递（当前仅 inbox 渠道）
     *
     * @param command 发送命令
     * @return 消息 VO
     */
    Message send(MessageSendCommand command);
}
