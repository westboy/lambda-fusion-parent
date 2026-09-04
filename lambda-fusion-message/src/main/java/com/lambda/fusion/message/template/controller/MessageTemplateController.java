package com.lambda.fusion.message.template.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.message.template.model.query.MessageTemplateQuery;
import com.lambda.fusion.message.template.model.vo.MessageTemplate;
import com.lambda.fusion.message.template.service.MessageTemplateService;
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
 * 消息模板管理API（管理端）
 *
 * @author jin
 */
@RestController
@RequestMapping("/message/template")
@Tag(name = "消息模板管理")
@RequiredArgsConstructor
public class MessageTemplateController {

    private final MessageTemplateService messageTemplateService;

    @SaCheckPermission(value = "message:template:list")
    @GetMapping
    @Operation(summary = "模板管理分页")
    public PageView<MessageTemplate> page(MessageTemplateQuery query) {
        return messageTemplateService.page(query);
    }

    @SaCheckPermission(value = "message:template:add")
    @PostMapping
    @Operation(summary = "创建模板")
    public MessageTemplate create(@Valid @RequestBody MessageTemplate template) {
        return messageTemplateService.create(template);
    }

    @SaCheckPermission(value = "message:template:update")
    @PutMapping("/{id}")
    @Operation(summary = "更新模板")
    public MessageTemplate update(@PathVariable String id, @Valid @RequestBody MessageTemplate template) {
        return messageTemplateService.update(id, template);
    }

    @SaCheckPermission(value = "message:template:delete")
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模板")
    public void delete(@PathVariable String id) {
        messageTemplateService.delete(id);
    }
}
