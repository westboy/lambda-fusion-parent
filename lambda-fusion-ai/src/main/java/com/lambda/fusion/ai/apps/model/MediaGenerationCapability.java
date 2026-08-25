package com.lambda.fusion.ai.apps.model;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/**
 * 聊天端可见的媒体生成能力。仅包含安全展示名和参数快捷建议，不暴露模型 ID、实际模型名、
 * provider、服务地址或凭据。建议值不是平台自建的模型参数白名单，用户输入仍由 AgentScope
 * 官方工具和上游提供方作最终校验。
 *
 * @author Jin
 */
@Data
@Schema(description = "媒体生成能力")
public class MediaGenerationCapability {

    @Schema(description = "实际调用的创作操作")
    private MediaGenerationOperation operation;

    @Schema(description = "绑定模型的安全展示名")
    private String modelLabel;

    @Schema(description = "尺寸快捷建议；空尺寸表示使用模型默认值，不限制用户输入其他正整数宽高")
    private List<MediaGenerationSizeOption> sizes = List.of();

    @Schema(description = "生成数量快捷建议，不构成允许值白名单")
    private List<Integer> counts = List.of();

    @Schema(description = "视频时长快捷建议(秒)，不构成允许值白名单；非视频为空")
    private List<Integer> durations = List.of();

    @Schema(description = "图片质量快捷建议；空列表表示当前 AgentScope 工具不消费该参数")
    private List<String> qualities = List.of();

    @Schema(description = "语音角色推荐选项，仍允许输入模型支持的其他角色")
    private List<String> voices = List.of();

    @Schema(description = "语音语言推荐选项，仍允许输入模型支持的其他语言")
    private List<String> languages = List.of();

    @Schema(description = "音频采样率快捷建议，不构成允许值白名单")
    private List<Integer> sampleRates = List.of();

    @Schema(description = "自定义模式可提交的参数名；来源于当前官方工具操作和已绑定模型")
    private List<String> parameters = List.of();

    @Schema(description = "能力类型: image|video|audio")
    public String getKind() {
        return operation == null ? null : operation.getKind();
    }
}
