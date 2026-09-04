package com.lambda.fusion.message.dispatch.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import com.lambda.fusion.message.dispatch.model.query.MessageDeliveryQuery;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryRetryService;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投递记录管理API（管理端：查询 + 失败重试）
 *
 * @author jin
 */
@RestController
@RequestMapping("/message/delivery")
@Tag(name = "投递记录管理")
@RequiredArgsConstructor
public class MessageDeliveryController {

    private final MessageDeliveryService messageDeliveryService;
    private final MessageDeliveryRetryService messageDeliveryRetryService;

    @SaCheckPermission(value = "message:delivery:list")
    @GetMapping
    @Operation(summary = "投递记录分页")
    public PageView<MessageDeliveryEntity> page(MessageDeliveryQuery query) {
        return PageView.of(messageDeliveryService.page(query.getPage(), query.getLambdaQueryWrapper()));
    }

    @SaCheckPermission(value = "message:delivery:retry")
    @PostMapping("/{id}/retry")
    @Operation(summary = "重试失败的投递记录")
    public MessageDeliveryEntity retry(@PathVariable String id) {
        return messageDeliveryRetryService.retry(id);
    }
}
