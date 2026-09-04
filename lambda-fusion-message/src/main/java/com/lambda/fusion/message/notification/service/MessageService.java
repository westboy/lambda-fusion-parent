package com.lambda.fusion.message.notification.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.notification.model.entity.MessageEntity;
import com.lambda.fusion.message.notification.model.query.MessageQuery;
import com.lambda.fusion.message.notification.model.vo.Message;

/**
 * 消息主表服务（管理端）
 *
 * @author jin
 */
public interface MessageService extends IService<MessageEntity> {

    /**
     * 消息管理分页
     *
     * @param query 查询参数
     * @return 消息分页
     */
    PageView<Message> page(MessageQuery query);
}
