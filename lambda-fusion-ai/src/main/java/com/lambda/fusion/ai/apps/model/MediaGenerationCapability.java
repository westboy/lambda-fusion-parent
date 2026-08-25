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

    @Schema(description = "推荐尺寸预设；空尺寸表示使用模型默认值")
    private List<MediaGenerationSizeOption> sizes = List.of();

    @Schema(description = "模型声明的生成数量；空列表表示官方工具允许透传，由上游提供方校验")
    private List<Integer> counts = List.of();

    @Schema(description = "模型允许的视频时长(秒)；前端可快捷选择或手动输入，非视频为空")
    private List<Integer> durations = List.of();

    @Schema(description = "是否允许在推荐预设之外输入自定义宽高")
    private boolean customSizeAllowed;

    @Schema(description = "自定义宽高单边最小像素；不支持自定义时为空")
    private Integer minDimension;

    @Schema(description = "自定义宽高单边最大像素；不支持自定义时为空")
    private Integer maxDimension;

    @Schema(description = "图片质量选项")
    private List<String> qualities = List.of();

    @Schema(description = "语音角色推荐选项，仍允许输入模型支持的其他角色")
    private List<String> voices = List.of();

    @Schema(description = "语音语言推荐选项，仍允许输入模型支持的其他语言")
    private List<String> languages = List.of();

    @Schema(description = "模型允许的音频采样率；前端可快捷选择或手动输入")
    private List<Integer> sampleRates = List.of();
}
