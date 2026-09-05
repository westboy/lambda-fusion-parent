package com.lambda.fusion.message.announcement.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.core.utils.Assert;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.service.AbstractCrudService;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.announcement.mapper.AnnouncementMapper;
import com.lambda.fusion.message.announcement.model.entity.AnnouncementEntity;
import com.lambda.fusion.message.announcement.model.query.AnnouncementQuery;
import com.lambda.fusion.message.announcement.model.query.MyAnnouncementQuery;
import com.lambda.fusion.message.announcement.model.vo.Announcement;
import com.lambda.fusion.message.announcement.model.vo.MyAnnouncement;
import com.lambda.fusion.message.announcement.service.AnnouncementReadService;
import com.lambda.fusion.message.announcement.service.AnnouncementService;
import com.lambda.fusion.message.dispatch.resolver.MessageRecipientResolver;
import com.lambda.fusion.message.exception.MessageBusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知公告服务实现
 * <p>
 * 读扩散模型：可见性判定在读取时经 MessageRecipientResolver 解析，不预写投递表。
 *
 * @author jin
 */
@Service
public class AnnouncementServiceImpl extends AbstractCrudService<AnnouncementEntity, Announcement, AnnouncementMapper>
        implements AnnouncementService {

    private final AnnouncementReadService announcementReadService;
    private final Map<String, MessageRecipientResolver> recipientResolvers;
    private final org.springframework.beans.factory.ObjectProvider<com.lambda.fusion.message.channel.inbox.SseNotifier>
            sseNotifier;

    public AnnouncementServiceImpl(
            AnnouncementReadService announcementReadService,
            List<MessageRecipientResolver> resolverList,
            org.springframework.beans.factory.ObjectProvider<com.lambda.fusion.message.channel.inbox.SseNotifier>
                    sseNotifier) {
        this.announcementReadService = announcementReadService;
        this.recipientResolvers = resolverList.stream()
                .collect(Collectors.toUnmodifiableMap(MessageRecipientResolver::scopeType, Function.identity()));
        this.sseNotifier = sseNotifier;
    }

    /** SSE 通知器（SSE starter 缺失时为 null，静默跳过信号推送） */
    private com.lambda.fusion.message.channel.inbox.SseNotifier notifier() {
        return sseNotifier.getIfAvailable();
    }

    @Override
    public PageView<Announcement> page(AnnouncementQuery query) {
        return pageView(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Announcement create(Announcement announcement) {
        validateScope(announcement.getScopeType(), announcement.getScopeTargets());
        AnnouncementEntity entity = toEntity(announcement);
        entity.setPublishStatus(MessageConstants.PUBLISH_STATUS_DRAFT);
        entity.setEnabled(1);
        save(entity);
        return getByIdForVO(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Announcement update(String id, Announcement announcement) {
        AnnouncementEntity existing = getById(id);
        if (existing == null) {
            throw MessageBusinessException.announcementNotFound(id);
        }
        if (existing.getPublishStatus() != null
                && existing.getPublishStatus() == MessageConstants.PUBLISH_STATUS_PUBLISHED) {
            throw MessageBusinessException.announcementStateInvalid();
        }
        validateScope(announcement.getScopeType(), announcement.getScopeTargets());
        AnnouncementEntity entity = toEntity(announcement);
        entity.setId(id);
        updateById(entity);
        return getByIdForVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Assert.notNull(id, "公告ID不能为空");
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(String id) {
        AnnouncementEntity entity = getById(id);
        if (entity == null) {
            throw MessageBusinessException.announcementNotFound(id);
        }
        Integer status = entity.getPublishStatus();
        if (status != null
                && status != MessageConstants.PUBLISH_STATUS_DRAFT
                && status != MessageConstants.PUBLISH_STATUS_REVOKED) {
            throw MessageBusinessException.announcementStateInvalid();
        }
        entity.setPublishStatus(MessageConstants.PUBLISH_STATUS_PUBLISHED);
        entity.setPublishTime(LocalDateTime.now());
        updateById(entity);
        com.lambda.fusion.message.channel.inbox.SseNotifier notifier = notifier();
        if (notifier != null) {
            notifier.broadcast(
                    MessageConstants.SSE_EVENT_ANNOUNCEMENT,
                    Map.of(
                            "type",
                            "publish",
                            "announcementId",
                            String.valueOf(entity.getId()),
                            "title",
                            entity.getTitle() == null ? "" : entity.getTitle()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String id) {
        AnnouncementEntity entity = getById(id);
        if (entity == null) {
            throw MessageBusinessException.announcementNotFound(id);
        }
        if (entity.getPublishStatus() == null
                || entity.getPublishStatus() != MessageConstants.PUBLISH_STATUS_PUBLISHED) {
            throw MessageBusinessException.announcementStateInvalid();
        }
        entity.setPublishStatus(MessageConstants.PUBLISH_STATUS_REVOKED);
        updateById(entity);
        com.lambda.fusion.message.channel.inbox.SseNotifier notifier = notifier();
        if (notifier != null) {
            notifier.broadcast(
                    MessageConstants.SSE_EVENT_ANNOUNCEMENT,
                    Map.of("type", "revoke", "announcementId", String.valueOf(entity.getId())));
        }
    }

    @Override
    public PageView<MyAnnouncement> pageMy(MyAnnouncementQuery query) {
        String username = AuthUtils.getUsername();
        // 读扩散：全量生效公告经范围解析过滤后内存分页（公告为低频数据，量级可控；org/role 范围接入后如需下推 SQL 再优化）
        List<AnnouncementEntity> visible = visibleAnnouncements(username);

        long total = visible.size();
        long current = query.getPage().getCurrent();
        long size = query.getPage().getSize();
        int fromIndex = (int) Math.min((current - 1) * size, total);
        int toIndex = (int) Math.min(fromIndex + size, total);

        List<String> pageIds = visible.subList(fromIndex, toIndex).stream()
                .map(AnnouncementEntity::getId)
                .toList();
        Map<String, Boolean> readFlags = pageIds.isEmpty()
                ? Map.of()
                : announcementReadService.readAnnouncementIds(pageIds).stream()
                        .collect(Collectors.toMap(Function.identity(), ignored -> true));

        List<MyAnnouncement> records = visible.subList(fromIndex, toIndex).stream()
                .map(entity -> toMyAnnouncement(entity, readFlags.get(entity.getId()) != null))
                .toList();

        Page<MyAnnouncement> voPage = new Page<>(current, size, total);
        voPage.setRecords(records);
        return PageView.of(voPage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markMyRead(String id) {
        AnnouncementEntity entity = getById(id);
        if (entity == null) {
            throw MessageBusinessException.announcementNotFound(id);
        }
        announcementReadService.markRead(id);
        notifyReadSignal();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllMyRead() {
        String username = AuthUtils.getUsername();
        List<String> visibleIds = visibleAnnouncements(username).stream()
                .map(AnnouncementEntity::getId)
                .toList();
        if (visibleIds.isEmpty()) {
            return;
        }
        java.util.Set<String> readIds = announcementReadService.readAnnouncementIds(visibleIds);
        java.time.LocalDateTime now = LocalDateTime.now();
        List<com.lambda.fusion.message.announcement.model.entity.AnnouncementReadEntity> toInsert = visibleIds.stream()
                .filter(id -> !readIds.contains(id))
                .map(id -> {
                    com.lambda.fusion.message.announcement.model.entity.AnnouncementReadEntity entity =
                            new com.lambda.fusion.message.announcement.model.entity.AnnouncementReadEntity();
                    entity.setAnnouncementId(id);
                    entity.setReader(username);
                    entity.setReadTime(now);
                    return entity;
                })
                .toList();
        if (!toInsert.isEmpty()) {
            announcementReadService.saveBatch(toInsert);
        }
        notifyReadSignal();
    }

    /** 已读动作后向当前用户定向推送刷新信号（铃铛即时同步，多标签页一致） */
    private void notifyReadSignal() {
        com.lambda.fusion.message.channel.inbox.SseNotifier notifier = notifier();
        if (notifier != null) {
            notifier.notify(List.of(AuthUtils.getUsername()), Map.of("reason", "read"));
        }
    }

    @Override
    public long countMyUnread() {
        String username = AuthUtils.getUsername();
        List<String> visibleIds = visibleAnnouncements(username).stream()
                .map(AnnouncementEntity::getId)
                .toList();
        if (visibleIds.isEmpty()) {
            return 0;
        }
        long readCount = announcementReadService.readAnnouncementIds(visibleIds).size();
        return visibleIds.size() - readCount;
    }

    /** 生效中且当前用户范围命中的公告（pageMy 与 countMyUnread 共用） */
    private List<AnnouncementEntity> visibleAnnouncements(String username) {
        MyAnnouncementQuery query = new MyAnnouncementQuery();
        return list(query.getLambdaQueryWrapper()).stream()
                .filter(entity -> containsScope(entity, username))
                .toList();
    }

    private boolean containsScope(AnnouncementEntity entity, String username) {
        MessageRecipientResolver resolver = recipientResolvers.get(entity.getScopeType());
        return resolver != null && resolver.contains(entity.getScopeTargets(), username);
    }

    private void validateScope(String scopeType, String scopeTargets) {
        if (!recipientResolvers.containsKey(scopeType)) {
            throw MessageBusinessException.scopeNotSupported(scopeType);
        }
        if (MessageConstants.SCOPE_USER.equals(scopeType) && StringUtils.isBlank(scopeTargets)) {
            throw MessageBusinessException.invalidParameter("指定用户范围时 scopeTargets 不能为空");
        }
    }

    private AnnouncementEntity toEntity(Announcement announcement) {
        AnnouncementEntity entity = new AnnouncementEntity();
        entity.setTitle(announcement.getTitle());
        entity.setContent(announcement.getContent());
        entity.setContentType(
                StringUtils.isNotBlank(announcement.getContentType())
                        ? announcement.getContentType()
                        : MessageConstants.CONTENT_TYPE_TEXT);
        entity.setScopeType(announcement.getScopeType());
        entity.setScopeTargets(announcement.getScopeTargets());
        entity.setEffectiveFrom(announcement.getEffectiveFrom());
        entity.setEffectiveTo(announcement.getEffectiveTo());
        entity.setIsTop(announcement.getIsTop() != null ? announcement.getIsTop() : 0);
        entity.setOrderNo(announcement.getOrderNo() != null ? announcement.getOrderNo() : 0);
        entity.setRemarks(announcement.getRemarks());
        return entity;
    }

    private MyAnnouncement toMyAnnouncement(AnnouncementEntity entity, boolean isRead) {
        MyAnnouncement vo = new MyAnnouncement();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setContentType(entity.getContentType());
        vo.setIsTop(entity.getIsTop());
        vo.setOrderNo(entity.getOrderNo());
        vo.setPublishTime(entity.getPublishTime());
        vo.setIsRead(isRead);
        return vo;
    }
}
