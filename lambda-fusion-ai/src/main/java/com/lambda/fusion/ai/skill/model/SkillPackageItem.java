package com.lambda.fusion.ai.skill.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "技能包内的技能")
public class SkillPackageItem {

    @Schema(description = "技能名")
    private String name;

    @Schema(description = "技能描述")
    private String description;

    @Schema(description = "技能根目录（相对于压缩包）")
    private String rootPath;

    @Schema(description = "许可证声明")
    private String license;

    @Schema(description = "资源文件数量")
    private int resourceCount;

    @Schema(description = "使用 AgentScope base64 约定保存的二进制资源数量")
    private int binaryResourceCount;

    @Schema(description = "资源文件相对路径")
    private List<String> resourcePaths;

    @Schema(description = "技能目录解压后总字节数")
    private long uncompressedSize;

    @Schema(description = "技能仓库中是否已有同名技能")
    private boolean existing;
}
