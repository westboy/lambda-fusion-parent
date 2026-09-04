package com.lambda.fusion.message.announcement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.fusion.message.announcement.model.entity.AnnouncementReadEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 公告已读记录 Mapper
 *
 * @author jin
 */
@Mapper
public interface AnnouncementReadMapper extends BaseMapper<AnnouncementReadEntity> {}
