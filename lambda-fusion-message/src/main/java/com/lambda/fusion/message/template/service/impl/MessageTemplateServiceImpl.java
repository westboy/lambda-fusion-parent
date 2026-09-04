package com.lambda.fusion.message.template.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.cloud.core.utils.Assert;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.service.AbstractCrudService;
import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.exception.MessageBusinessException;
import com.lambda.fusion.message.template.mapper.MessageTemplateMapper;
import com.lambda.fusion.message.template.model.entity.MessageTemplateEntity;
import com.lambda.fusion.message.template.model.query.MessageTemplateQuery;
import com.lambda.fusion.message.template.model.vo.MessageTemplate;
import com.lambda.fusion.message.template.service.MessageTemplateService;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息模板服务实现
 *
 * @author jin
 */
@Service
public class MessageTemplateServiceImpl
        extends AbstractCrudService<MessageTemplateEntity, MessageTemplate, MessageTemplateMapper>
        implements MessageTemplateService {

    /** ${var} 占位符 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    @Override
    public PageView<MessageTemplate> page(MessageTemplateQuery query) {
        return pageView(query.getPage(), query.getLambdaQueryWrapper());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageTemplate create(MessageTemplate template) {
        Assert.hasText(template.getCode(), "模板编码不能为空");
        Assert.hasText(template.getName(), "模板名称不能为空");
        if (codeExists(template.getCode(), null)) {
            throw MessageBusinessException.templateCodeExists(template.getCode());
        }
        MessageTemplateEntity entity = toEntity(template);
        entity.setStatus(template.getStatus() != null ? template.getStatus() : 1);
        save(entity);
        return getByIdForVO(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageTemplate update(String id, MessageTemplate template) {
        MessageTemplateEntity existing = getById(id);
        if (existing == null) {
            throw MessageBusinessException.templateNotFound(id);
        }
        if (codeExists(template.getCode(), id)) {
            throw MessageBusinessException.templateCodeExists(template.getCode());
        }
        MessageTemplateEntity entity = toEntity(template);
        entity.setId(id);
        updateById(entity);
        return getByIdForVO(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        Assert.notNull(id, "模板ID不能为空");
        removeById(id);
    }

    @Override
    public MessageTemplateEntity getEnabledByCode(String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        MessageTemplateEntity entity = getOne(new LambdaQueryWrapper<MessageTemplateEntity>()
                .eq(MessageTemplateEntity::getCode, code)
                .orderByDesc(MessageTemplateEntity::getCreatedAt)
                .last("LIMIT 1"));
        return entity != null && Integer.valueOf(1).equals(entity.getStatus()) ? entity : null;
    }

    @Override
    public String render(String template, Map<String, Object> params) {
        if (StringUtils.isBlank(template) || params == null || params.isEmpty()) {
            return template;
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = params.get(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private boolean codeExists(String code, String excludeId) {
        MessageTemplateEntity existing = getOne(new LambdaQueryWrapper<MessageTemplateEntity>()
                .eq(MessageTemplateEntity::getCode, code)
                .last("LIMIT 1"));
        return existing != null && !existing.getId().equals(excludeId);
    }

    private MessageTemplateEntity toEntity(MessageTemplate template) {
        MessageTemplateEntity entity = new MessageTemplateEntity();
        entity.setCode(template.getCode());
        entity.setName(template.getName());
        entity.setTitle(template.getTitle());
        entity.setContent(template.getContent());
        entity.setContentType(
                StringUtils.isNotBlank(template.getContentType())
                        ? template.getContentType()
                        : MessageConstants.CONTENT_TYPE_TEXT);
        entity.setChannelParams(template.getChannelParams());
        entity.setStatus(template.getStatus());
        entity.setRemarks(template.getRemarks());
        return entity;
    }
}
