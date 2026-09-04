package com.lambda.fusion.message.announcement.model.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.pagination.PageQuery;
import com.lambda.fusion.message.announcement.model.entity.AnnouncementEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.StringUtils;

/**
 * 公告分页查询（管理端）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "公告分页查询参数")
public class AnnouncementQuery extends PageQuery<AnnouncementEntity> {

    @Schema(description = "标题关键字")
    private String keyword;

    @Schema(description = "发布状态：0草稿，1已发布，2已撤回")
    private Integer publishStatus;

    @Override
    public LambdaQueryWrapper<AnnouncementEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<AnnouncementEntity> wrapper = super.getLambdaQueryWrapper();
        wrapper.like(StringUtils.isNotBlank(getKeyword()), AnnouncementEntity::getTitle, getKeyword());
        wrapper.eq(getPublishStatus() != null, AnnouncementEntity::getPublishStatus, getPublishStatus());
        wrapper.orderByDesc(AnnouncementEntity::getIsTop);
        wrapper.orderByDesc(AnnouncementEntity::getPublishTime);
        return wrapper;
    }
}
