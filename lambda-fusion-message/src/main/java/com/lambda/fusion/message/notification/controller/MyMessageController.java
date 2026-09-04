package com.lambda.fusion.message.notification.controller;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.notification.model.query.MyMessageQuery;
import com.lambda.fusion.message.notification.model.vo.MyMessage;
import com.lambda.fusion.message.notification.service.UserMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的消息API（用户侧，登录即可访问，数据范围收敛到当前用户）
 *
 * @author jin
 */
@RestController
@RequestMapping("/message/my")
@Tag(name = "我的消息")
@RequiredArgsConstructor
public class MyMessageController {

    private final UserMessageService userMessageService;

    @GetMapping
    @Operation(summary = "我的消息分页")
    public PageView<MyMessage> page(MyMessageQuery query) {
        return userMessageService.pageMy(query);
    }

    @GetMapping("unread-count")
    @Operation(summary = "未读数")
    public Long unreadCount() {
        return userMessageService.countUnread();
    }

    @PostMapping("read")
    @Operation(summary = "批量标记已读")
    public void markRead(@RequestBody List<String> ids) {
        userMessageService.markRead(ids);
    }

    @PostMapping("read-all")
    @Operation(summary = "全部标记已读")
    public void markAllRead() {
        userMessageService.markAllRead();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除我的消息")
    public void delete(@PathVariable String id) {
        userMessageService.hide(id);
    }
}
