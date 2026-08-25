package com.lambda.fusion.ai.runtime.tools;

import com.lambda.fusion.ai.runtime.media.MediaGenerationInvocation;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import reactor.core.publisher.Mono;

/**
 * 应用绑定的文字转语音薄适配器。模型名由应用配置锁定，其余参数与 AgentScope 2.0.1
 * 的 DashScope 官方 TTS 工具一致，结果原样保留为 {@code AudioBlock}。
 *
 * @author Jin
 */
public final class AudioGenerationTool {

    /** 工具名。 */
    public static final String TOOL_NAME = "text_to_audio";

    private final DashScopeMultiModalTool dashscopeTool;
    private final String modelName;

    AudioGenerationTool(DashScopeMultiModalTool dashscopeTool, String modelName) {
        this.dashscopeTool = dashscopeTool;
        this.modelName = modelName;
    }

    /** 创建绑定指定 DashScope TTS 模型的工具。 */
    public static AudioGenerationTool dashscope(String apiKey, String modelName) {
        return new AudioGenerationTool(new DashScopeMultiModalTool(apiKey), modelName);
    }

    /** 调用 AgentScope 官方文字转语音方法。结构化请求存在时以服务端校验参数覆盖模型参数。 */
    @Tool(name = TOOL_NAME, description = "Convert text to speech and return an official audio block.")
    public Mono<ToolResultBlock> generateAudio(
            @ToolParam(name = "text", description = "The text to convert to speech") String text,
            @ToolParam(name = "voice", description = "Voice name", required = false) String voice,
            @ToolParam(name = "language", description = "Language name", required = false) String language,
            @ToolParam(name = "sample_rate", description = "Audio sample rate", required = false) Integer sampleRate,
            MediaGenerationInvocation invocation) {
        if (invocation != null) {
            invocation.claimExecution(TOOL_NAME);
            text = invocation.getPrompt();
            voice = invocation.getVoice();
            language = invocation.getLanguage();
            sampleRate = invocation.getSampleRate();
        }
        return dashscopeTool.dashscopeTextToAudio(text, modelName, voice, language, sampleRate);
    }
}
