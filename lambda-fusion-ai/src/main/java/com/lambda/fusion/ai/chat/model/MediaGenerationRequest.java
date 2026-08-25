package com.lambda.fusion.ai.chat.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 用户显式选择的媒体生成参数；提示文本复用 {@link SendMessage#content}，不保存第二份事实。 */
@Data
@Schema(description = "结构化媒体生成请求")
public class MediaGenerationRequest {

    @NotBlank(message = "媒体生成类型不能为空")
    @Pattern(regexp = "image|video|audio", message = "媒体生成类型不合法")
    @Schema(description = "生成类型: image|video|audio")
    private String kind;

    @Min(value = 64, message = "媒体宽度不能小于64")
    @Max(value = 8192, message = "媒体宽度不能大于8192")
    private Integer width;

    @Min(value = 64, message = "媒体高度不能小于64")
    @Max(value = 8192, message = "媒体高度不能大于8192")
    private Integer height;

    @Min(value = 1, message = "生成数量不能小于1")
    @Max(value = 10, message = "生成数量不能大于10")
    private Integer count;

    @Min(value = 1, message = "视频时长不能小于1秒")
    @Max(value = 60, message = "视频时长不能大于60秒")
    private Integer duration;

    @Size(max = 32, message = "图片质量参数过长")
    private String quality;

    @Size(max = 4000, message = "反向提示词不能超过4000字符")
    private String negativePrompt;

    @Pattern(regexp = "single|multi", message = "镜头类型只能是single或multi")
    private String shotType;

    private Boolean promptExtend;

    private Boolean watermark;

    @PositiveOrZero(message = "随机种子不能为负数")
    private Integer seed;

    @Size(max = 64, message = "音色名称不能超过64字符")
    private String voice;

    @Size(max = 64, message = "语言名称不能超过64字符")
    private String language;

    @Min(value = 8000, message = "采样率不能小于8000")
    @Max(value = 48000, message = "采样率不能大于48000")
    private Integer sampleRate;

    @AssertTrue(message = "媒体宽度和高度必须同时填写")
    @Schema(hidden = true)
    public boolean isDimensionPairComplete() {
        return (width == null) == (height == null);
    }
}
