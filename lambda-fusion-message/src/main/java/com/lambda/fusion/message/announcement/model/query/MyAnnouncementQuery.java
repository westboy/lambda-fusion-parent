package com.lambda.fusion.message.announcement.model.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageQuery;
import com.lambda.fusion.message.announcement.model.entity.AnnouncementEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.StringUtils;

/**
 * 我的公告分页查询（用户侧；范围与已读态在 Service 层解析组装）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的公告分页查询参数")
public class MyAnnouncementQuery extends PageQuery<AnnouncementEntity> {

    @Schema(description = "标题关键字")
    private String keyword;

    @Override
    public LambdaQueryWrapper<AnnouncementEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<AnnouncementEntity> wrapper = super.getLambdaQueryWrapper();
        LocalDateTime now = LocalDateTime.now();
        wrapper.like(StringUtils.isNotBlank(getKeyword()), AnnouncementEntity::getTitle, getKeyword());
        wrapper.eq(AnnouncementEntity::getPublishStatus, 1);
        wrapper.and(
                w -> w.le(AnnouncementEntity::getEffectiveFrom, now).or().isNull(AnnouncementEntity::getEffectiveFrom));
        wrapper.and(w -> w.ge(AnnouncementEntity::getEffectiveTo, now).or().isNull(AnnouncementEntity::getEffectiveTo));
        wrapper.eq(AnnouncementEntity::getEnabled, 1);
        wrapper.orderByDesc(AnnouncementEntity::getIsTop);
        wrapper.orderByDesc(AnnouncementEntity::getPublishTime);
        return wrapper;
    }
}
