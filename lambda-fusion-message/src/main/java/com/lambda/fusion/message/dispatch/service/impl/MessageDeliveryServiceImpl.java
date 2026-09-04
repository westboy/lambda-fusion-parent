package com.lambda.fusion.message.dispatch.service.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lambda.fusion.message.dispatch.mapper.MessageDeliveryMapper;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import java.util.Collection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 外部渠道投递记录服务实现
 *
 * @author jin
 */
@Service
public class MessageDeliveryServiceImpl extends ServiceImpl<MessageDeliveryMapper, MessageDeliveryEntity>
        implements MessageDeliveryService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordAll(Collection<MessageDeliveryEntity> deliveries) {
        if (CollectionUtils.isEmpty(deliveries)) {
            return;
        }
        saveBatch(deliveries);
    }
}
