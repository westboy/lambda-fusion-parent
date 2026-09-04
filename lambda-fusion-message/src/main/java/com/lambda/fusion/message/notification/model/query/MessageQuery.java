package com.lambda.fusion.message.notification.model.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageQuery;
import com.lambda.fusion.message.notification.model.entity.MessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.StringUtils;

/**
 * 消息分页查询（管理端）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "消息分页查询参数")
public class MessageQuery extends PageQuery<MessageEntity> {

    @Schema(description = "消息分类：system/user/biz")
    private String category;

    @Schema(description = "级别：info/important/urgent")
    private String level;

    @Schema(description = "标题关键字")
    private String keyword;

    @Override
    public LambdaQueryWrapper<MessageEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<MessageEntity> wrapper = super.getLambdaQueryWrapper();
        wrapper.eq(StringUtils.isNotBlank(getCategory()), MessageEntity::getCategory, getCategory());
        wrapper.eq(StringUtils.isNotBlank(getLevel()), MessageEntity::getLevel, getLevel());
        wrapper.like(StringUtils.isNotBlank(getKeyword()), MessageEntity::getTitle, getKeyword());
        wrapper.orderByDesc(MessageEntity::getCreatedAt);
        return wrapper;
    }
}
