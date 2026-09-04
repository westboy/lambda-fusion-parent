package com.lambda.fusion.message.announcement.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 我的公告 VO（用户侧，含已读态）
 *
 * @author jin
 */
@Data
@Schema(description = "我的公告")
public class MyAnnouncement {

    @Schema(description = "公告ID")
    private String id;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "正文")
    private String content;

    @Schema(description = "正文类型：text/html/markdown")
    private String contentType;

    @Schema(description = "是否置顶：0否，1是")
    private Integer isTop;

    @Schema(description = "排序号")
    private Integer orderNo;

    @Schema(description = "发布时间")
    private LocalDateTime publishTime;

    @Schema(description = "是否已读")
    private Boolean isRead;
}
