package com.lambda.fusion.ai.skill.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "技能包导入结果")
public class SkillPackageImportResult {

    @Schema(description = "导入技能数量")
    private int importedCount;

    @Schema(description = "已导入技能名称")
    private List<String> skillNames;
}
