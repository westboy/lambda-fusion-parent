package com.lambda.fusion.ai.skill.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
@Schema(description = "更新技能(字段可选)")
public class UpdateSkill {

    @Schema(description = "描述")
    private String description;

    @Schema(description = "SKILL.md 内容")
    private String markdown;

    @Schema(description = "资源 map(name->content;传则覆盖)")
    private Map<String, String> resources;

    @Schema(description = "增量新增或更新的资源 map(name->content)")
    private Map<String, String> resourceUpserts;

    @Schema(description = "增量删除的资源路径")
    private List<String> resourceDeletes;
}
