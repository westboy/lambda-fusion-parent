package com.lambda.fusion.message.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.fusion.message.template.model.entity.MessageTemplateEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息模板 Mapper
 *
 * @author jin
 */
@Mapper
public interface MessageTemplateMapper extends BaseMapper<MessageTemplateEntity> {}
