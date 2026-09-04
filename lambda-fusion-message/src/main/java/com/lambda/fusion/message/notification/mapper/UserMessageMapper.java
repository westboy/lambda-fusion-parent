package com.lambda.fusion.message.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.fusion.message.notification.model.entity.UserMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户消息实例 Mapper
 *
 * @author jin
 */
@Mapper
public interface UserMessageMapper extends BaseMapper<UserMessageEntity> {}
