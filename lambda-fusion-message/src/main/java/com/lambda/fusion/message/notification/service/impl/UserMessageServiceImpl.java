package com.lambda.fusion.message.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.core.utils.Assert;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.service.AbstractCrudService;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.message.dispatch.model.MessageEnvelope;
import com.lambda.fusion.message.notification.assembler.MyMessageAssembler;
import com.lambda.fusion.message.notification.mapper.UserMessageMapper;
import com.lambda.fusion.message.notification.model.entity.UserMessageEntity;
import com.lambda.fusion.message.notification.model.query.MyMessageQuery;
import com.lambda.fusion.message.notification.model.vo.MyMessage;
import com.lambda.fusion.message.notification.service.UserMessageService;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户消息实例服务实现
 *
 * @author jin
 */
@Service
@RequiredArgsConstructor
public class UserMessageServiceImpl extends AbstractCrudService<UserMessageEntity, MyMessage, UserMessageMapper>
        implements UserMessageService {

    private static final int READ_FLAG = 1;
    private static final int UNREAD_FLAG = 0;
    private static final int DELETED_FLAG = 1;
    private static final int NOT_DELETED_FLAG = 0;

    private final MyMessageAssembler myMessageAssembler;

    @Override
    public PageView<MyMessage> pageMy(MyMessageQuery query) {
        IPage<UserMessageEntity> entityPage = page(query.getPage(), query.getLambdaQueryWrapper());
        List<MyMessage> records = myMessageAssembler.assemble(entityPage.getRecords());
        Page<MyMessage> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(records);
        return PageView.of(voPage);
    }

    @Override
    public long countUnread() {
        return count(new LambdaQueryWrapper<UserMessageEntity>()
                .eq(UserMessageEntity::getReceiver, AuthUtils.getUsername())
                .eq(UserMessageEntity::getIsRead, UNREAD_FLAG)
                .eq(UserMessageEntity::getIsDeleted, NOT_DELETED_FLAG));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Collection<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        update(Wrappers.<UserMessageEntity>lambdaUpdate()
                .eq(UserMessageEntity::getReceiver, AuthUtils.getUsername())
                .eq(UserMessageEntity::getIsRead, UNREAD_FLAG)
                .in(UserMessageEntity::getId, ids)
                .set(UserMessageEntity::getIsRead, READ_FLAG)
                .set(UserMessageEntity::getReadTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllRead() {
        update(Wrappers.<UserMessageEntity>lambdaUpdate()
                .eq(UserMessageEntity::getReceiver, AuthUtils.getUsername())
                .eq(UserMessageEntity::getIsRead, UNREAD_FLAG)
                .eq(UserMessageEntity::getIsDeleted, NOT_DELETED_FLAG)
                .set(UserMessageEntity::getIsRead, READ_FLAG)
                .set(UserMessageEntity::getReadTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void hide(String id) {
        Assert.notNull(id, "实例ID不能为空");
        update(Wrappers.<UserMessageEntity>lambdaUpdate()
                .eq(UserMessageEntity::getId, id)
                .eq(UserMessageEntity::getReceiver, AuthUtils.getUsername())
                .set(UserMessageEntity::getIsDeleted, DELETED_FLAG));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deliver(MessageEnvelope envelope, Collection<String> receivers) {
        List<UserMessageEntity> entities = receivers.stream()
                .map(receiver -> {
                    UserMessageEntity entity = new UserMessageEntity();
                    entity.setMessageId(envelope.getMessageId());
                    entity.setReceiver(receiver);
                    entity.setIsRead(UNREAD_FLAG);
                    entity.setIsDeleted(NOT_DELETED_FLAG);
                    return entity;
                })
                .toList();
        saveBatch(entities);
    }
}
