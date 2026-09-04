package com.lambda.fusion.message.dispatch.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import com.lambda.fusion.message.dispatch.model.query.MessageDeliveryQuery;
import com.lambda.fusion.message.dispatch.service.MessageDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投递记录管理API（管理端，只读查询）
 *
 * @author jin
 */
@RestController
@RequestMapping("/message/delivery")
@Tag(name = "投递记录管理")
@RequiredArgsConstructor
public class MessageDeliveryController {

    private final MessageDeliveryService messageDeliveryService;

    @SaCheckPermission(value = "message:delivery:list")
    @GetMapping
    @Operation(summary = "投递记录分页")
    public PageView<MessageDeliveryEntity> page(MessageDeliveryQuery query) {
        return PageView.of(messageDeliveryService.page(query.getPage(), query.getLambdaQueryWrapper()));
    }
}
