package com.lambda.fusion.message.notification.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.dispatch.model.MessageEnvelope;
import com.lambda.fusion.message.notification.model.entity.UserMessageEntity;
import com.lambda.fusion.message.notification.model.query.MyMessageQuery;
import com.lambda.fusion.message.notification.model.vo.MyMessage;
import java.util.Collection;

/**
 * 用户消息实例服务（投递态：投递、已读、未读数、用户侧删除）
 *
 * @author jin
 */
public interface UserMessageService extends IService<UserMessageEntity> {

    /**
     * 我的消息分页（跨表组装为用户侧视图）
     *
     * @param query 查询参数
     * @return 我的消息分页
     */
    PageView<MyMessage> pageMy(MyMessageQuery query);

    /**
     * 当前用户未读数
     *
     * @return 未读数
     */
    long countUnread();

    /**
     * 批量标记已读（仅限本人消息）
     *
     * @param ids 实例ID集合
     */
    void markRead(Collection<String> ids);

    /**
     * 当前用户全部消息标记已读
     */
    void markAllRead();

    /**
     * 用户侧删除（软删，仅限本人消息）
     *
     * @param id 实例ID
     */
    void hide(String id);

    /**
     * 投递：按消息信封为每个收件人写一条投递实例（inbox 渠道调用）
     *
     * @param envelope  消息信封
     * @param receivers 收件人集合
     */
    void deliver(MessageEnvelope envelope, Collection<String> receivers);
}
