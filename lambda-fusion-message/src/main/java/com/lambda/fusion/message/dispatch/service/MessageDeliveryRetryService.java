package com.lambda.fusion.message.dispatch.service;

import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;

/**
 * 投递失败重试服务
 *
 * @author jin
 */
public interface MessageDeliveryRetryService {

    /**
     * 重试一条失败的投递记录（仅外部渠道，status=失败）
     *
     * @param deliveryId 投递记录ID
     * @return 更新后的投递记录
     */
    MessageDeliveryEntity retry(String deliveryId);
}
