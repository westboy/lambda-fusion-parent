package com.lambda.fusion.message.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.fusion.message.notification.model.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息主表 Mapper
 *
 * @author jin
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {}
