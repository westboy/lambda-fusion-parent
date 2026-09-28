package com.lambda.fusion.form.design.model;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseVO;
import com.lambda.fusion.form.design.entity.FormDefVersionEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单版本视图（版本历史）
 *
 * @author Zx
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoConverter(target = FormDefVersionEntity.class, isReverse = true)
@Schema(description = "表单版本")
public class FormVersionView extends BaseVO<FormDefVersionEntity> {

    @Schema(description = "快照 id")
    private String id;

    @Schema(description = "版本号")
    private Long version;

    @Schema(description = "内容指纹")
    private String contentMd5;

    @Schema(description = "发布人")
    private String publishedBy;

    @Schema(description = "发布时间")
    private LocalDateTime publishedAt;

    @Schema(description = "是否当前生效版本")
    private Boolean current;
}
