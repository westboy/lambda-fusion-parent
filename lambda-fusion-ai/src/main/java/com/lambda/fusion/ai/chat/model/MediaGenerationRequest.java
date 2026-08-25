package com.lambda.fusion.ai.chat.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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

    @Positive(message = "媒体宽度必须大于0")
    @Max(value = 16384, message = "媒体宽度不能超过16384像素")
    private Integer width;

    @Positive(message = "媒体高度必须大于0")
    @Max(value = 16384, message = "媒体高度不能超过16384像素")
    private Integer height;

    @Positive(message = "生成数量必须大于0")
    @Max(value = 10, message = "单次生成数量不能超过10")
    private Integer count;

    @Positive(message = "视频时长必须大于0秒")
    @Max(value = 600, message = "视频时长不能超过600秒")
    private Integer duration;

    @Size(max = 32, message = "图片质量参数过长")
    private String quality;

    @Size(max = 4000, message = "反向提示词不能超过4000字符")
    private String negativePrompt;

    @Size(max = 2048, message = "源图片地址不能超过2048字符")
    private String sourceImageUrl;

    @Size(max = 2048, message = "首帧图片地址不能超过2048字符")
    private String firstFrameUrl;

    @Size(max = 2048, message = "尾帧图片地址不能超过2048字符")
    private String lastFrameUrl;

    @Size(max = 2048, message = "音频地址不能超过2048字符")
    private String audioUrl;

    @Size(max = 32, message = "视频清晰度参数过长")
    private String resolution;

    @Size(max = 64, message = "视频模板参数过长")
    private String template;

    @Pattern(regexp = "single|multi", message = "镜头类型只能是single或multi")
    private String shotType;

    private Boolean promptExtend;

    private Boolean watermark;

    private Boolean audio;

    @PositiveOrZero(message = "随机种子不能为负数")
    private Integer seed;

    @Size(max = 64, message = "音色名称不能超过64字符")
    private String voice;

    @Size(max = 64, message = "语言名称不能超过64字符")
    private String language;

    @Positive(message = "采样率必须大于0")
    @Max(value = 384000, message = "采样率不能超过384000")
    private Integer sampleRate;

    @AssertTrue(message = "媒体宽度和高度必须同时填写")
    @Schema(hidden = true)
    public boolean isDimensionPairComplete() {
        return (width == null) == (height == null);
    }
}
