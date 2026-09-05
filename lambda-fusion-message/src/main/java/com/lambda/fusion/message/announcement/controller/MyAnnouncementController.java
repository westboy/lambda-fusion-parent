package com.lambda.fusion.message.announcement.controller;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.announcement.model.query.MyAnnouncementQuery;
import com.lambda.fusion.message.announcement.model.vo.MyAnnouncement;
import com.lambda.fusion.message.announcement.service.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的公告API（用户侧，登录即可访问，数据范围收敛到当前用户）
 *
 * @author jin
 */
@RestController
@RequestMapping("/announcement/my")
@Tag(name = "我的公告")
@RequiredArgsConstructor
public class MyAnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping
    @Operation(summary = "我的公告分页（生效中且范围命中）")
    public PageView<MyAnnouncement> page(MyAnnouncementQuery query) {
        return announcementService.pageMy(query);
    }

    @GetMapping("unread-count")
    @Operation(summary = "我的公告未读数")
    public Long unreadCount() {
        return announcementService.countMyUnread();
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "标记公告已读")
    public void markRead(@PathVariable String id) {
        announcementService.markMyRead(id);
    }

    @PostMapping("read-all")
    @Operation(summary = "全部公告标记已读")
    public void markAllRead() {
        announcementService.markAllMyRead();
    }
}
