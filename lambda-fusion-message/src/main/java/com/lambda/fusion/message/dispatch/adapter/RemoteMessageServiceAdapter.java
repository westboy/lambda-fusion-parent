package com.lambda.fusion.message.dispatch.adapter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.message.api.RemoteMessageCommand;
import com.lambda.fusion.message.api.RemoteMessageService;
import com.lambda.fusion.message.dispatch.model.MessageSendCommand;
import com.lambda.fusion.message.dispatch.service.MessageDispatchService;
import com.lambda.fusion.message.notification.mapper.MessageMapper;
import com.lambda.fusion.message.notification.model.entity.MessageEntity;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

/**
 * {@link RemoteMessageService} 实现：把远程命令转为内部 {@link MessageSendCommand} 发送，
 * 经 Dubbo 暴露给其他服务（如 ai）。
 *
 * <p>远程调用方（如定时任务）运行在无登录上下文的后台线程，收件人、发送人均以命令显式携带为准，
 * 不依赖安全上下文。命令携带租户时按其建立租户上下文后发送、结束清理；租户为空则按无租户/平台级消息
 * 直接投递。任何异常只记录日志并返回 {@code false}，不向调用方抛出，避免影响其主流程（如任务执行）。
 *
 * @author zx
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoteMessageServiceAdapter implements RemoteMessageService {

    private final MessageDispatchService messageDispatchService;
    private final MessageMapper messageMapper;

    @Override
    public boolean existsRecent(String bizType, String bizId, int withinDays) {
        if (StringUtils.isBlank(bizType) || StringUtils.isBlank(bizId) || withinDays <= 0) {
            return false;
        }
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(withinDays);
            Long count = messageMapper.selectCount(new LambdaQueryWrapper<MessageEntity>()
                    .eq(MessageEntity::getBizType, bizType)
                    .eq(MessageEntity::getBizId, bizId)
                    .ge(MessageEntity::getCreatedAt, since));
            return count != null && count > 0;
        } catch (Exception e) {
            // 判重失败宁可重发也不漏判，返回 false 让调用方继续发送
            log.warn("远程消息判重查询失败: bizType={}, bizId={}", bizType, bizId, e);
            return false;
        }
    }

    @Override
    public boolean send(RemoteMessageCommand command) {
        if (command == null
                || StringUtils.isBlank(command.getTitle())
                || CollectionUtils.isEmpty(command.getReceivers())) {
            log.warn("远程消息发送参数非法,已忽略: {}", command);
            return false;
        }
        // tenantId 允许为空：后台任务（如定时任务）可能运行在无租户上下文的环境，此时按无租户/平台级消息投递。
        boolean tenantSet = false;
        try {
            if (StringUtils.isNotBlank(command.getTenantId())) {
                TenantContextHolder.getInstance().setTenantId(command.getTenantId());
                tenantSet = true;
            }

            MessageSendCommand internal = new MessageSendCommand();
            internal.setCategory(command.getCategory());
            internal.setLevel(command.getLevel());
            internal.setTitle(command.getTitle());
            internal.setContent(command.getContent());
            internal.setReceivers(command.getReceivers());
            internal.setChannels(command.getChannels());
            internal.setBizType(command.getBizType());
            internal.setBizId(command.getBizId());
            internal.setRemarks("来自远程服务: " + StringUtils.defaultIfBlank(command.getSender(), "unknown"));
            messageDispatchService.send(internal);
            return true;
        } catch (Exception e) {
            log.error("远程消息发送失败: tenant={}, title={}", command.getTenantId(), command.getTitle(), e);
            return false;
        } finally {
            if (tenantSet) {
                TenantContextHolder.getInstance().close();
            }
        }
    }
}
