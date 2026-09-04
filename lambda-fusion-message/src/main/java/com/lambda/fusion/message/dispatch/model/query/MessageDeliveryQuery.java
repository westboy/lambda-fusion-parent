package com.lambda.fusion.message.dispatch.model.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageQuery;
import com.lambda.fusion.message.dispatch.model.entity.MessageDeliveryEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.StringUtils;

/**
 * 投递记录分页查询（管理端）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "投递记录分页查询参数")
public class MessageDeliveryQuery extends PageQuery<MessageDeliveryEntity> {

    @Schema(description = "消息ID")
    private String messageId;

    @Schema(description = "渠道：email/sms")
    private String channel;

    @Schema(description = "投递状态：0待发送，1成功，2失败")
    private Integer status;

    @Override
    public LambdaQueryWrapper<MessageDeliveryEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<MessageDeliveryEntity> wrapper = super.getLambdaQueryWrapper();
        wrapper.eq(StringUtils.isNotBlank(getMessageId()), MessageDeliveryEntity::getMessageId, getMessageId());
        wrapper.eq(StringUtils.isNotBlank(getChannel()), MessageDeliveryEntity::getChannel, getChannel());
        wrapper.eq(getStatus() != null, MessageDeliveryEntity::getStatus, getStatus());
        wrapper.orderByDesc(MessageDeliveryEntity::getCreatedAt);
        return wrapper;
    }
}
