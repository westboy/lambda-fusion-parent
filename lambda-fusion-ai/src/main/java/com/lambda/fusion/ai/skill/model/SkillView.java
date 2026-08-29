package com.lambda.fusion.ai.skill.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
@Schema(description = "技能视图")
public class SkillView {

    @Schema(description = "技能名")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "SKILL.md 内容")
    private String markdown;

    @Schema(description = "资源 map(name->content)")
    private Map<String, String> resources;

    @Schema(description = "资源文件数量")
    private int resourceCount;

    @Schema(description = "资源文件索引（不包含正文）")
    private List<SkillResourceView> resourceFiles;

    @Schema(description = "SKILL.md YAML Front Matter 元数据")
    private Map<String, Object> metadata;
}
