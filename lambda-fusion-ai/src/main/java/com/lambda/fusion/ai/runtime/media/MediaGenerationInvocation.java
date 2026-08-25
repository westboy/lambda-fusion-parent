package com.lambda.fusion.ai.runtime.media;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Builder;
import lombok.Getter;

/**
 * 已由服务端解析并校验的单次媒体生成调用。对象通过 AgentScope {@code RuntimeContext} 按类型注入，
 * 不进入模型可见参数；模型只负责产生标准工具调用，工具最终使用这里的权威参数。
 *
 * @author Jin
 */
@Getter
public final class MediaGenerationInvocation {

    private final String toolName;
    private final String prompt;
    private final String size;
    private final Integer count;
    private final Integer duration;
    private final String quality;
    private final String negativePrompt;
    private final String shotType;
    private final Boolean promptExtend;
    private final Boolean watermark;
    private final Integer seed;
    private final String voice;
    private final String language;
    private final Integer sampleRate;

    @Getter(lombok.AccessLevel.NONE)
    private final AtomicBoolean initialModelCall = new AtomicBoolean(true);

    @Getter(lombok.AccessLevel.NONE)
    private final AtomicBoolean executionAvailable = new AtomicBoolean(true);

    @Builder
    private MediaGenerationInvocation(
            String toolName,
            String prompt,
            String size,
            Integer count,
            Integer duration,
            String quality,
            String negativePrompt,
            String shotType,
            Boolean promptExtend,
            Boolean watermark,
            Integer seed,
            String voice,
            String language,
            Integer sampleRate) {
        this.toolName = toolName;
        this.prompt = prompt;
        this.size = size;
        this.count = count;
        this.duration = duration;
        this.quality = quality;
        this.negativePrompt = negativePrompt;
        this.shotType = shotType;
        this.promptExtend = promptExtend;
        this.watermark = watermark;
        this.seed = seed;
        this.voice = voice;
        this.language = language;
        this.sampleRate = sampleRate;
    }

    /** 首次模型调用必须路由到指定工具；后续调用返回 {@code false}。 */
    public boolean claimInitialModelCall() {
        return initialModelCall.compareAndSet(true, false);
    }

    /**
     * 声明开始执行服务端选定的工具。工具名不匹配或同一请求重复执行时立即失败，避免模型绕过
     * 结构化请求边界扩大调用范围。
     */
    public void claimExecution(String actualToolName) {
        if (!toolName.equals(actualToolName)) {
            throw new IllegalStateException("媒体生成工具与服务端路由不一致");
        }
        if (!executionAvailable.compareAndSet(true, false)) {
            throw new IllegalStateException("同一媒体生成请求只能执行一次");
        }
    }
}
