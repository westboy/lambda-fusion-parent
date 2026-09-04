package com.lambda.fusion.message.announcement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lambda.cloud.core.utils.Assert;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.message.announcement.mapper.AnnouncementReadMapper;
import com.lambda.fusion.message.announcement.model.entity.AnnouncementReadEntity;
import com.lambda.fusion.message.announcement.service.AnnouncementReadService;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公告已读记录服务实现
 *
 * @author jin
 */
@Service
public class AnnouncementReadServiceImpl extends ServiceImpl<AnnouncementReadMapper, AnnouncementReadEntity>
        implements AnnouncementReadService {

    @Override
    public Set<String> readAnnouncementIds(Collection<String> announcementIds) {
        if (CollectionUtils.isEmpty(announcementIds)) {
            return Set.of();
        }
        return list(new LambdaQueryWrapper<AnnouncementReadEntity>()
                        .eq(AnnouncementReadEntity::getReader, AuthUtils.getUsername())
                        .in(AnnouncementReadEntity::getAnnouncementId, announcementIds))
                .stream()
                .map(AnnouncementReadEntity::getAnnouncementId)
                .collect(Collectors.toSet());
    }

    @Override
    public Map<String, AnnouncementReadEntity> readMap(Collection<String> announcementIds) {
        if (CollectionUtils.isEmpty(announcementIds)) {
            return Map.of();
        }
        return list(new LambdaQueryWrapper<AnnouncementReadEntity>()
                        .eq(AnnouncementReadEntity::getReader, AuthUtils.getUsername())
                        .in(AnnouncementReadEntity::getAnnouncementId, announcementIds))
                .stream()
                .collect(Collectors.toMap(AnnouncementReadEntity::getAnnouncementId, Function.identity(), (a, b) -> a));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(String announcementId) {
        Assert.notNull(announcementId, "公告ID不能为空");
        String reader = AuthUtils.getUsername();
        boolean exists = count(new LambdaQueryWrapper<AnnouncementReadEntity>()
                        .eq(AnnouncementReadEntity::getAnnouncementId, announcementId)
                        .eq(AnnouncementReadEntity::getReader, reader))
                > 0;
        if (exists) {
            return;
        }
        AnnouncementReadEntity entity = new AnnouncementReadEntity();
        entity.setAnnouncementId(announcementId);
        entity.setReader(reader);
        entity.setReadTime(LocalDateTime.now());
        save(entity);
    }
}
