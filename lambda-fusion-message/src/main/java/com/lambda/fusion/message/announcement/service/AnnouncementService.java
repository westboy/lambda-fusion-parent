package com.lambda.fusion.message.announcement.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.announcement.model.entity.AnnouncementEntity;
import com.lambda.fusion.message.announcement.model.query.AnnouncementQuery;
import com.lambda.fusion.message.announcement.model.query.MyAnnouncementQuery;
import com.lambda.fusion.message.announcement.model.vo.Announcement;
import com.lambda.fusion.message.announcement.model.vo.MyAnnouncement;

/**
 * 通知公告服务
 *
 * @author jin
 */
public interface AnnouncementService extends IService<AnnouncementEntity> {

    /**
     * 公告管理分页
     *
     * @param query 查询参数
     * @return 公告分页
     */
    PageView<Announcement> page(AnnouncementQuery query);

    /**
     * 创建公告（草稿态）
     *
     * @param announcement 公告参数
     * @return 公告 VO
     */
    Announcement create(Announcement announcement);

    /**
     * 更新公告（仅草稿/已撤回可更新）
     *
     * @param id           公告ID
     * @param announcement 公告参数
     * @return 公告 VO
     */
    Announcement update(String id, Announcement announcement);

    /**
     * 删除公告
     *
     * @param id 公告ID
     */
    void delete(String id);

    /**
     * 发布公告（草稿/已撤回 → 已发布）
     *
     * @param id 公告ID
     */
    void publish(String id);

    /**
     * 撤回公告（已发布 → 已撤回）
     *
     * @param id 公告ID
     */
    void revoke(String id);

    /**
     * 我的公告分页：生效中 + 范围命中（MessageRecipientResolver 解析）+ 已读态
     *
     * @param query 查询参数
     * @return 我的公告分页
     */
    PageView<MyAnnouncement> pageMy(MyAnnouncementQuery query);

    /**
     * 当前用户标记公告已读（幂等）
     *
     * @param id 公告ID
     */
    void markMyRead(String id);

    /**
     * 当前用户公告未读数：生效中且范围命中 - 已读
     *
     * @return 未读数
     */
    long countMyUnread();
}
