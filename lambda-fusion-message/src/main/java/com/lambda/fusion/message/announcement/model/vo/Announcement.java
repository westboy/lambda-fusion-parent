package com.lambda.fusion.message.announcement.model.vo;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.message.announcement.model.entity.AnnouncementEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公告 VO（管理端，同时作为创建/更新入参）
 *
 * @author jin
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoConverter(target = AnnouncementEntity.class, isReverse = true)
@Schema(description = "公告")
public class Announcement extends BaseVO<AnnouncementEntity> {

    @Schema(description = "公告ID")
    private String id;

    @Schema(description = "租户ID")
    private String tenantId;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "正文类型：text/html/markdown")
    private String contentType;

    @Schema(description = "可见范围：all/user", defaultValue = "all")
    private String scopeType;

    @Schema(description = "范围目标 JSON 数组（用户名列表），all 时为空")
    private String scopeTargets;

    @Schema(description = "发布状态：0草稿，1已发布，2已撤回")
    private Integer publishStatus;

    @Schema(description = "发布时间")
    private LocalDateTime publishTime;

    @Schema(description = "生效开始，空表示发布即生效")
    private LocalDateTime effectiveFrom;

    @Schema(description = "生效结束，空表示长期有效")
    private LocalDateTime effectiveTo;

    @Schema(description = "是否置顶：0否，1是", defaultValue = "0")
    private Integer isTop;

    @Schema(description = "排序号", defaultValue = "0")
    private Integer orderNo;

    @Schema(description = "备注")
    private String remarks;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新人")
    private String updatedBy;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
