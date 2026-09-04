package com.lambda.fusion.message.template.model.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageQuery;
import com.lambda.fusion.message.template.model.entity.MessageTemplateEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.StringUtils;

/**
 * 消息模板分页查询（管理端）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "消息模板分页查询参数")
public class MessageTemplateQuery extends PageQuery<MessageTemplateEntity> {

    @Schema(description = "模板编码")
    private String code;

    @Schema(description = "模板名称关键字")
    private String keyword;

    @Schema(description = "状态：0停用，1启用")
    private Integer status;

    @Override
    public LambdaQueryWrapper<MessageTemplateEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<MessageTemplateEntity> wrapper = super.getLambdaQueryWrapper();
        wrapper.eq(StringUtils.isNotBlank(getCode()), MessageTemplateEntity::getCode, getCode());
        wrapper.like(StringUtils.isNotBlank(getKeyword()), MessageTemplateEntity::getName, getKeyword());
        wrapper.eq(getStatus() != null, MessageTemplateEntity::getStatus, getStatus());
        wrapper.orderByDesc(MessageTemplateEntity::getCreatedAt);
        return wrapper;
    }
}
