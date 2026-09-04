package com.lambda.fusion.message.announcement.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lambda.fusion.message.announcement.model.entity.AnnouncementReadEntity;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 公告已读记录服务
 *
 * @author jin
 */
public interface AnnouncementReadService extends IService<AnnouncementReadEntity> {

    /**
     * 查询当前用户对指定公告的已读集合
     *
     * @param announcementIds 公告ID集合
     * @return 已读的公告ID集合
     */
    Set<String> readAnnouncementIds(Collection<String> announcementIds);

    /**
     * 当前用户标记公告已读（幂等）
     *
     * @param announcementId 公告ID
     */
    void markRead(String announcementId);

    /**
     * 统计当前用户在指定公告集合内的已读映射（announcementId → 是否已读）
     *
     * @param announcementIds 公告ID集合
     * @return 公告ID → 已读记录（未读的不在结果中）
     */
    Map<String, AnnouncementReadEntity> readMap(Collection<String> announcementIds);
}
