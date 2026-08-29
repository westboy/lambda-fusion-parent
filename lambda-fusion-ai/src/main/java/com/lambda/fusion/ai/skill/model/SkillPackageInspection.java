package com.lambda.fusion.ai.skill.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "技能包预检结果")
public class SkillPackageInspection {

    @Schema(description = "上传文件名")
    private String fileName;

    @Schema(description = "压缩包字节数")
    private long compressedSize;

    @Schema(description = "技能包类型")
    private SkillPackageType packageType;

    @Schema(description = "技能列表")
    private List<SkillPackageItem> skills;
}
