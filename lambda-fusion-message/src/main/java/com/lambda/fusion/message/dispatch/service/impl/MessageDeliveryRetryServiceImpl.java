package com.lambda.fusion.message.dispatch.service.impl;

import com.lambda.cloud.core.utils.Assert;
import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.channel.MessageChannelRegistry;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryRetryService;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import com.lambda.fusion.message.exception.MessageBusinessException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投递失败重试服务实现：按渠道分派重试并回写投递记录（状态、重试次数、失败原因）
 *
 * @author jin
 */
@Service
@RequiredArgsConstructor
public class MessageDeliveryRetryServiceImpl implements MessageDeliveryRetryService {

    private final MessageDeliveryService deliveryService;
    private final MessageChannelRegistry channelRegistry;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageDeliveryEntity retry(String deliveryId) {
        Assert.notNull(deliveryId, "投递记录ID不能为空");
        MessageDeliveryEntity delivery = deliveryService.getById(deliveryId);
        if (delivery == null) {
            throw MessageBusinessException.invalidParameter("投递记录不存在: " + deliveryId);
        }
        if (!Integer.valueOf(2).equals(delivery.getStatus())) {
            throw MessageBusinessException.invalidParameter("仅失败的投递记录可以重试");
        }
        if (MessageConstants.CHANNEL_INBOX.equals(delivery.getChannel())) {
            throw MessageBusinessException.invalidParameter("站内渠道无投递记录，不支持重试");
        }

        boolean success;
        try {
            success = channelRegistry.get(delivery.getChannel()).retry(delivery);
        } catch (MessageBusinessException e) {
            // 渠道未装配：记录原因，按重试失败处理
            success = false;
            delivery.setLastError(e.getMessage());
        }

        delivery.setRetryCount(delivery.getRetryCount() == null ? 1 : delivery.getRetryCount() + 1);
        if (success) {
            delivery.setStatus(1);
            delivery.setSentAt(LocalDateTime.now());
            delivery.setLastError(null);
        } else {
            delivery.setStatus(2);
        }
        deliveryService.updateById(delivery);
        return delivery;
    }
}
