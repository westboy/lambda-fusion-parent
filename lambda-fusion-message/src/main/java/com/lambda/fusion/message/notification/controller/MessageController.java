package com.lambda.fusion.message.notification.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.dispatch.model.MessageSendCommand;
import com.lambda.fusion.message.dispatch.service.MessageDispatchService;
import com.lambda.fusion.message.notification.model.query.MessageQuery;
import com.lambda.fusion.message.notification.model.vo.Message;
import com.lambda.fusion.message.notification.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息管理API（管理端）
 *
 * @author jin
 */
@RestController
@RequestMapping("/message")
@Tag(name = "消息管理")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final MessageDispatchService messageDispatchService;

    @SaCheckPermission(value = "message:list")
    @GetMapping
    @Operation(summary = "消息管理分页")
    public PageView<Message> page(MessageQuery query) {
        return messageService.page(query);
    }

    @SaCheckPermission(value = "message:send")
    @PostMapping("send")
    @Operation(summary = "发送系统消息")
    public Message send(@Valid @RequestBody MessageSendCommand command) {
        return messageDispatchService.send(command);
    }
}
