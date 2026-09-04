package com.lambda.fusion.message.dispatch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 外部渠道投递记录 Mapper
 *
 * @author jin
 */
@Mapper
public interface MessageDeliveryMapper extends BaseMapper<MessageDeliveryEntity> {}
