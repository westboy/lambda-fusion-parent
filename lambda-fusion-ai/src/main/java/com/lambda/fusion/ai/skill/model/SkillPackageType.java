package com.lambda.fusion.ai.skill.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "技能包类型")
public enum SkillPackageType {
    SINGLE,
    COLLECTION
}
