package com.lambda.fusion.message.announcement.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.announcement.model.query.AnnouncementQuery;
import com.lambda.fusion.message.announcement.model.vo.Announcement;
import com.lambda.fusion.message.announcement.service.AnnouncementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公告管理API（管理端）
 *
 * @author jin
 */
@RestController
@RequestMapping("/announcement")
@Tag(name = "公告管理")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @SaCheckPermission(value = "announcement:list")
    @GetMapping
    @Operation(summary = "公告管理分页")
    public PageView<Announcement> page(AnnouncementQuery query) {
        return announcementService.page(query);
    }

    @SaCheckPermission(value = "announcement:add")
    @PostMapping
    @Operation(summary = "创建公告")
    public Announcement create(@Valid @RequestBody Announcement announcement) {
        return announcementService.create(announcement);
    }

    @SaCheckPermission(value = "announcement:update")
    @PutMapping("/{id}")
    @Operation(summary = "更新公告")
    public Announcement update(@PathVariable String id, @Valid @RequestBody Announcement announcement) {
        return announcementService.update(id, announcement);
    }

    @SaCheckPermission(value = "announcement:delete")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除公告")
    public void delete(@PathVariable String id) {
        announcementService.delete(id);
    }

    @SaCheckPermission(value = "announcement:publish")
    @PostMapping("/{id}/publish")
    @Operation(summary = "发布公告")
    public void publish(@PathVariable String id) {
        announcementService.publish(id);
    }

    @SaCheckPermission(value = "announcement:revoke")
    @PostMapping("/{id}/revoke")
    @Operation(summary = "撤回公告")
    public void revoke(@PathVariable String id) {
        announcementService.revoke(id);
    }
}
