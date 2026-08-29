package com.lambda.fusion.ai.skill.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "技能资源文件")
public class SkillResourceView {

    @Schema(description = "资源相对路径")
    private String path;

    @Schema(description = "资源正文；技能详情索引中不返回")
    private String content;

    @Schema(description = "资源正文字符数")
    private long contentLength;

    @Schema(description = "是否为 AgentScope base64 编码资源")
    private boolean binary;
}
