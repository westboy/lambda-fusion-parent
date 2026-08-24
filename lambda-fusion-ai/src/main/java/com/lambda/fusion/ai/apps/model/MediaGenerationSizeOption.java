package com.lambda.fusion.ai.apps.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 媒体生成尺寸预设。{@code size} 为空表示交给绑定模型使用默认尺寸；前端只提交本列表中的组合，
 * 不自行猜测不同提供方的尺寸约定。
 *
 * @author Jin
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "媒体生成尺寸预设")
public class MediaGenerationSizeOption {

    @Schema(description = "宽高比标识，例如 auto、1:1、16:9")
    private String aspectRatio;

    @Schema(description = "清晰度标识，例如 auto、1K、720P、1080P")
    private String resolution;

    @Schema(description = "传给官方工具的精确尺寸；空表示模型默认")
    private String size;
}
