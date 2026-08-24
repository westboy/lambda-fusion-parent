package com.lambda.fusion.ai.apps.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/**
 * 聊天端可见的媒体生成能力。仅包含安全的展示名和可提交参数，不暴露模型 ID、实际模型名、
 * provider、服务地址或凭据。
 *
 * @author Jin
 */
@Data
@Schema(description = "媒体生成能力")
public class MediaGenerationCapability {

    @Schema(description = "能力类型: image|video|audio")
    private String kind;

    @Schema(description = "绑定模型的安全展示名")
    private String modelLabel;

    @Schema(description = "聊天 Agent 中挂载的工具名")
    private String toolName;

    @Schema(description = "服务端批准的尺寸组合")
    private List<MediaGenerationSizeOption> sizes = List.of();

    @Schema(description = "允许的生成数量；视频通常仅为 1")
    private List<Integer> counts = List.of();

    @Schema(description = "允许的视频时长(秒)；非视频为空")
    private List<Integer> durations = List.of();
}
