package com.lambda.fusion.message.dispatch.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import java.util.Collection;

/**
 * 外部渠道投递记录服务
 *
 * @author jin
 */
public interface MessageDeliveryService extends IService<MessageDeliveryEntity> {

    /**
     * 批量保存投递记录（渠道投递完成后按收件人逐条写入）
     *
     * @param deliveries 投递记录集合
     */
    void recordAll(Collection<MessageDeliveryEntity> deliveries);
}
