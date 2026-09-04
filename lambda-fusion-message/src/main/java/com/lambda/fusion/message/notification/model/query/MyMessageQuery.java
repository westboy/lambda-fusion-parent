package com.lambda.fusion.message.notification.model.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageQuery;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.message.notification.model.entity.UserMessageEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 我的消息分页查询（用户侧）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的消息分页查询参数")
public class MyMessageQuery extends PageQuery<UserMessageEntity> {

    @Schema(description = "是否已读：0未读，1已读，空查全部")
    private Integer isRead;

    @Override
    public LambdaQueryWrapper<UserMessageEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<UserMessageEntity> wrapper = super.getLambdaQueryWrapper();
        wrapper.eq(UserMessageEntity::getReceiver, AuthUtils.getUsername());
        wrapper.eq(UserMessageEntity::getIsDeleted, 0);
        wrapper.eq(getIsRead() != null, UserMessageEntity::getIsRead, getIsRead());
        wrapper.orderByDesc(UserMessageEntity::getCreatedAt);
        return wrapper;
    }
}
