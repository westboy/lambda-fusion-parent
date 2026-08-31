package com.lambda.fusion.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.MediaGenerationOperation;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentContentTool;
import com.lambda.fusion.ai.chat.attachment.WorkspaceArtifactPublishTool;
import com.lambda.fusion.ai.chat.runtime.media.ToolResultMediaMaterializer;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.mcp.service.McpServerService;
import com.lambda.fusion.ai.runtime.tools.AudioGenerationTool;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
import com.lambda.fusion.ai.security.KeyEncryptionService;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

/**
 * 媒体生成工具装配条件测试：图片支持 DashScope 与 AgentScope 已实现的 OpenAI DALL-E 协议，视频按
 * AgentScope 2.0.1 的官方能力仅支持 DashScope；提供方或模型停用、模型类型不符、未绑定均跳过且不阻断工具集构建。
 *
 * @author Jin
 */
class ToolkitAssemblerImageToolTest {

    private LlmModelService llmModelService;
    private LlmProviderService llmProviderService;
    private KeyEncryptionService keyEncryptionService;
    private ToolkitAssembler assembler;

    @BeforeEach
    void setUp() {
        llmModelService = mock(LlmModelService.class);
        llmProviderService = mock(LlmProviderService.class);
        keyEncryptionService = mock(KeyEncryptionService.class);
        // 不触发 @PostConstruct 扫描：本测试只关心生图工具的条件挂载
        assembler = new ToolkitAssembler(
                mock(ApplicationContext.class),
                mock(McpServerService.class),
                new MediaGenerationBindingResolver(llmModelService, llmProviderService),
                keyEncryptionService,
                mock(ToolResultMediaMaterializer.class),
                new AiProperties(),
                mock(ChatAttachmentContentTool.class),
                mock(WorkspaceArtifactPublishTool.class));
    }

    @Test
    void shouldRegisterImageToolWhenModelBoundAndProviderEnabled() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE, "wanx-v1");

        Toolkit toolkit = assembler.build(app("img-1", null));

        assertThat(toolkit.getToolNames()).contains(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenProviderDisabled() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "dashscope", null, "wanx-v1");

        assertThat(assembler.build(app("img-1", null)).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenModelTypeMismatch() {
        // 绑定入口已强校验类型；此处防御配置后续变化（如模型类型被改）
        stubMediaModel("img-1", ModelType.CHAT, Boolean.TRUE, "dashscope", Boolean.TRUE, "qwen-plus");

        assertThat(assembler.build(app("img-1", null)).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenModelDisabled() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.FALSE, "dashscope", Boolean.TRUE, "wanx-v1");

        assertThat(assembler.build(app("img-1", null)).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenProviderTypeUnsupported() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "ollama", Boolean.TRUE, "local-image");

        assertThat(assembler.build(app("img-1", null)).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldRegisterOpenAiImageModelViaNativeTool() {
        // 原生 OpenAI 生图：非自定义标记的 openai 模型走官方 SDK 薄适配器（具体模型可用性由端点决定）
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "openai", Boolean.TRUE, "dall-e-3");

        assertThat(assembler.build(app("img-1", null)).getToolNames()).contains(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenAppHasNoBinding() {
        assertThat(assembler.build(app(null, null)).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldRegisterCustomHttpImageToolWhenModelMarkedCustom() {
        // 厂商运行时未接通文生图（minimax 目录声明但 SDK 未实现），模型标记 customMediaTool 后走自定义 HTTP 工具
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "minimax", Boolean.TRUE, "qwen-image-2.0");
        stubCustomMediaTool("img-1", Boolean.TRUE);

        assertThat(assembler.build(app("img-1", null)).getToolNames()).contains(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldRegisterVideoToolForDashscopeBinding() {
        stubMediaModel("video-1", ModelType.VIDEO_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE, "wan2.6-t2v");

        Toolkit toolkit = assembler.build(app(null, "video-1"));

        assertThat(toolkit.getToolNames()).contains(VideoGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipVideoToolForOpenaiBecauseOfficialExtensionDoesNotProvideIt() {
        stubMediaModel("video-1", ModelType.VIDEO_GEN, Boolean.TRUE, "openai", Boolean.TRUE, "video-compatible");

        assertThat(assembler.build(app(null, "video-1")).getToolNames()).doesNotContain(VideoGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldRegisterAudioToolForSupportedDashscopeTtsBinding() {
        stubMediaModel("audio-1", ModelType.AUDIO_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE, "qwen3-tts-flash");
        AppEntity app = app(null, null);
        app.setAudioModelId("audio-1");

        assertThat(assembler.build(app).getToolNames()).contains(AudioGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldRegisterArtifactPublisherForEveryWorkspaceBackend() {
        AppEntity workspace = app(null, null);
        workspace.setAppType("WORKSPACE");
        workspace.setSandboxBackend("DOCKER");
        assertThat(assembler.build(workspace).getToolNames()).contains(WorkspaceArtifactPublishTool.TOOL_NAME);

        workspace.setSandboxBackend("HOST");
        assertThat(assembler.build(workspace).getToolNames()).contains(WorkspaceArtifactPublishTool.TOOL_NAME);

        AppEntity chat = app(null, null);
        chat.setAppType("CHAT");
        chat.setSandboxBackend("DOCKER");
        assertThat(assembler.build(chat).getToolNames()).doesNotContain(WorkspaceArtifactPublishTool.TOOL_NAME);
    }

    private void stubMediaModel(
            String modelId,
            ModelType modelType,
            Boolean modelEnabled,
            String providerType,
            Boolean providerEnabled,
            String modelName) {
        LlmModelEntity model = new LlmModelEntity();
        model.setId(modelId);
        model.setModelType(modelType);
        model.setMediaOperation(operation(modelType));
        model.setEnabled(modelEnabled);
        model.setModelName(modelName);
        model.setProviderId("p-1");
        when(llmModelService.loadById(modelId)).thenReturn(model);

        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId("p-1");
        provider.setProviderType(providerType);
        provider.setEnabled(providerEnabled);
        provider.setApiKeyEncrypted("enc");
        when(llmProviderService.loadById("p-1")).thenReturn(provider);
        when(keyEncryptionService.decrypt("enc")).thenReturn("sk-test");
    }

    private void stubCustomMediaTool(String modelId, Boolean customMediaTool) {
        LlmModelEntity model = llmModelService.loadById(modelId);
        model.setCustomMediaTool(customMediaTool);
    }

    private static MediaGenerationOperation operation(ModelType modelType) {
        return switch (modelType) {
            case IMAGE_GEN -> MediaGenerationOperation.TEXT_TO_IMAGE;
            case VIDEO_GEN -> MediaGenerationOperation.TEXT_TO_VIDEO;
            case AUDIO_GEN -> MediaGenerationOperation.TEXT_TO_SPEECH;
            default -> null;
        };
    }

    private static AppEntity app(String imageModelId, String videoModelId) {
        AppEntity app = new AppEntity();
        app.setId("app-1");
        app.setImageModelId(imageModelId);
        app.setVideoModelId(videoModelId);
        return app;
    }
}
