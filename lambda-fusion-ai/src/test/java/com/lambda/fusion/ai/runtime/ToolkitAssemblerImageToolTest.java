package com.lambda.fusion.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
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
import com.lambda.fusion.ai.console.MetadataQueryTools;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.mcp.service.McpServerService;
import com.lambda.fusion.ai.rag.runtime.KnowledgeRetrievalTool;
import com.lambda.fusion.ai.rag.runtime.KnowledgeRetriever;
import com.lambda.fusion.ai.runtime.tools.QwenImageGenerationTool;
import com.lambda.fusion.ai.security.KeyEncryptionService;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
    private ApplicationContext applicationContext;
    private ObjectProvider<KnowledgeRetriever> knowledgeRetrieverProvider;
    private ObjectProvider<QwenImageGenerationTool> qwenImageGenerationToolProvider;
    private ObjectProvider<MetadataQueryTools> metadataQueryToolsProvider;
    private ToolkitAssembler assembler;

    @BeforeEach
    void setUp() {
        llmModelService = mock(LlmModelService.class);
        llmProviderService = mock(LlmProviderService.class);
        keyEncryptionService = mock(KeyEncryptionService.class);
        applicationContext = mock(ApplicationContext.class);
        knowledgeRetrieverProvider = mock(ObjectProvider.class);
        qwenImageGenerationToolProvider = mock(ObjectProvider.class);
        metadataQueryToolsProvider = mock(ObjectProvider.class);
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[0]);
        // 不触发 @PostConstruct 扫描：本测试只关心生图工具的条件挂载
        assembler = new ToolkitAssembler(
                applicationContext,
                mock(McpServerService.class),
                new MediaGenerationBindingResolver(llmModelService, llmProviderService),
                keyEncryptionService,
                mock(ToolResultMediaMaterializer.class),
                new AiProperties(),
                mock(ChatAttachmentContentTool.class),
                mock(WorkspaceArtifactPublishTool.class),
                knowledgeRetrieverProvider,
                qwenImageGenerationToolProvider,
                metadataQueryToolsProvider);
    }

    @Test
    void shouldRegisterImageToolWhenModelBoundAndProviderEnabled() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE, "wanx-v1");

        Toolkit toolkit = assembler.buildForChatRun(app("img-1", null));

        assertThat(toolkit.getToolNames()).contains(MediaGenerationOperation.TEXT_TO_IMAGE.getToolName());
    }

    @Test
    void shouldSkipImageToolWhenProviderDisabled() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "dashscope", null, "wanx-v1");

        assertThat(assembler.buildForChatRun(app("img-1", null)).getToolNames())
                .doesNotContain(MediaGenerationOperation.TEXT_TO_IMAGE.getToolName());
    }

    @Test
    void shouldSkipImageToolWhenModelTypeMismatch() {
        // 绑定入口已强校验类型；此处防御配置后续变化（如模型类型被改）
        stubMediaModel("img-1", ModelType.CHAT, Boolean.TRUE, "dashscope", Boolean.TRUE, "qwen-plus");

        assertThat(assembler.buildForChatRun(app("img-1", null)).getToolNames())
                .doesNotContain(MediaGenerationOperation.TEXT_TO_IMAGE.getToolName());
    }

    @Test
    void shouldSkipImageToolWhenModelDisabled() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.FALSE, "dashscope", Boolean.TRUE, "wanx-v1");

        assertThat(assembler.buildForChatRun(app("img-1", null)).getToolNames())
                .doesNotContain(MediaGenerationOperation.TEXT_TO_IMAGE.getToolName());
    }

    @Test
    void shouldSkipImageToolWhenProviderTypeUnsupported() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "ollama", Boolean.TRUE, "local-image");

        assertThat(assembler.buildForChatRun(app("img-1", null)).getToolNames())
                .doesNotContain(MediaGenerationOperation.TEXT_TO_IMAGE.getToolName());
    }

    @Test
    void shouldRegisterOpenAiImageModelViaNativeTool() {
        // 原生 OpenAI 生图模型走官方 SDK 薄适配器，具体模型可用性由端点决定。
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "openai", Boolean.TRUE, "dall-e-3");

        assertThat(assembler.buildForChatRun(app("img-1", null)).getToolNames())
                .contains(MediaGenerationOperation.TEXT_TO_IMAGE.getToolName());
    }

    @Test
    void shouldSkipImageToolWhenAppHasNoBinding() {
        assertThat(assembler.buildForChatRun(app(null, null)).getToolNames())
                .doesNotContain(MediaGenerationOperation.TEXT_TO_IMAGE.getToolName());
    }

    @Test
    void shouldRegisterConfiguredQwenOnlyInChatToolkit() {
        QwenImageGenerationTool qwenTool = configuredQwenTool();
        when(qwenImageGenerationToolProvider.getIfAvailable()).thenReturn(qwenTool);
        when(applicationContext.getBeanDefinitionNames()).thenReturn(new String[] {"qwenImageGenerationTool"});
        doReturn(QwenImageGenerationTool.class).when(applicationContext).getType("qwenImageGenerationTool");
        when(applicationContext.getBean("qwenImageGenerationTool")).thenReturn(qwenTool);
        assembler.init();

        ToolSchema schema = mediaSchema(assembler.buildForChatRun(app(null, null)), QwenImageGenerationTool.TOOL_NAME);

        assertThat(schema.getParameters().toString())
                .contains("prompt", "size", "negative_prompt")
                .doesNotContain("model");
        assertThat(assembler.getLocalToolBeans()).doesNotContain(qwenTool);
    }

    @Test
    void shouldExcludeRunBoundMediaToolsFromChannelToolkit() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE, "wanx-v1");
        when(qwenImageGenerationToolProvider.getIfAvailable()).thenReturn(configuredQwenTool());

        assertThat(assembler.buildForChannel(app("img-1", null)).getToolNames())
                .doesNotContain(
                        QwenImageGenerationTool.TOOL_NAME,
                        MediaGenerationOperation.TEXT_TO_IMAGE.getToolName(),
                        WorkspaceArtifactPublishTool.TOOL_NAME);
    }

    @Test
    void shouldApplyToolAllowListAfterIndependentAndBoundMediaToolsAreRegistered() {
        stubMediaModel("img-1", ModelType.IMAGE_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE, "wanx-v1");
        when(qwenImageGenerationToolProvider.getIfAvailable()).thenReturn(configuredQwenTool());
        AppEntity app = app("img-1", null);
        app.setToolsAllow(List.of("  " + QwenImageGenerationTool.TOOL_NAME + "  "));

        assertThat(assembler.buildForChatRun(app).getToolNames())
                .contains(QwenImageGenerationTool.TOOL_NAME)
                .doesNotContain(MediaGenerationOperation.TEXT_TO_IMAGE.getToolName());
    }

    @Test
    void shouldLetToolDenyListOverrideAllowList() {
        when(qwenImageGenerationToolProvider.getIfAvailable()).thenReturn(configuredQwenTool());
        AppEntity app = app(null, null);
        app.setToolsAllow(List.of(QwenImageGenerationTool.TOOL_NAME));
        app.setToolsDeny(List.of(QwenImageGenerationTool.TOOL_NAME));

        assertThat(assembler.buildForChatRun(app).getToolNames()).doesNotContain(QwenImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldApplyToolPolicyAfterKnowledgeToolRegistration() {
        when(knowledgeRetrieverProvider.getIfAvailable()).thenReturn(mock(KnowledgeRetriever.class));
        AppEntity app = app(null, null);
        app.setKnowledgeBaseIds(List.of("kb-1"));
        app.setRagMode("AGENTIC");
        app.setToolsAllow(List.of(KnowledgeRetrievalTool.TOOL_NAME));

        assertThat(assembler.buildForChatRun(app).getToolNames()).contains(KnowledgeRetrievalTool.TOOL_NAME);

        app.setToolsAllow(List.of("another_tool"));
        assertThat(assembler.buildForChatRun(app).getToolNames()).doesNotContain(KnowledgeRetrievalTool.TOOL_NAME);

        app.setToolsAllow(List.of(KnowledgeRetrievalTool.TOOL_NAME));
        app.setToolsDeny(List.of(KnowledgeRetrievalTool.TOOL_NAME));
        assertThat(assembler.buildForChatRun(app).getToolNames()).doesNotContain(KnowledgeRetrievalTool.TOOL_NAME);
    }

    @Test
    void shouldRegisterVideoToolForDashscopeBinding() {
        stubMediaModel("video-1", ModelType.VIDEO_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE, "wan2.6-t2v");

        Toolkit toolkit = assembler.buildForChatRun(app(null, "video-1"));

        assertThat(toolkit.getToolNames()).contains(MediaGenerationOperation.TEXT_TO_VIDEO.getToolName());
    }

    @Test
    void shouldSkipVideoToolForOpenaiBecauseOfficialExtensionDoesNotProvideIt() {
        stubMediaModel("video-1", ModelType.VIDEO_GEN, Boolean.TRUE, "openai", Boolean.TRUE, "video-compatible");

        assertThat(assembler.buildForChatRun(app(null, "video-1")).getToolNames())
                .doesNotContain(MediaGenerationOperation.TEXT_TO_VIDEO.getToolName());
    }

    @Test
    void shouldRegisterAudioToolForSupportedDashscopeTtsBinding() {
        stubMediaModel("audio-1", ModelType.AUDIO_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE, "qwen3-tts-flash");
        AppEntity app = app(null, null);
        app.setAudioModelId("audio-1");

        assertThat(assembler.buildForChatRun(app).getToolNames())
                .contains(MediaGenerationOperation.TEXT_TO_SPEECH.getToolName());
    }

    @Test
    void shouldRegisterArtifactPublisherForEveryWorkspaceBackend() {
        AppEntity workspace = app(null, null);
        workspace.setAppType("WORKSPACE");
        workspace.setSandboxBackend("DOCKER");
        assertThat(assembler.buildForChatRun(workspace).getToolNames())
                .contains(WorkspaceArtifactPublishTool.TOOL_NAME);

        workspace.setSandboxBackend("HOST");
        assertThat(assembler.buildForChatRun(workspace).getToolNames())
                .contains(WorkspaceArtifactPublishTool.TOOL_NAME);

        AppEntity chat = app(null, null);
        chat.setAppType("CHAT");
        chat.setSandboxBackend("DOCKER");
        assertThat(assembler.buildForChatRun(chat).getToolNames())
                .doesNotContain(WorkspaceArtifactPublishTool.TOOL_NAME);
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

    private static MediaGenerationOperation operation(ModelType modelType) {
        return switch (modelType) {
            case IMAGE_GEN -> MediaGenerationOperation.TEXT_TO_IMAGE;
            case VIDEO_GEN -> MediaGenerationOperation.TEXT_TO_VIDEO;
            case AUDIO_GEN -> MediaGenerationOperation.TEXT_TO_SPEECH;
            default -> null;
        };
    }

    private static ToolSchema mediaSchema(Toolkit toolkit, String toolName) {
        return toolkit.getToolSchemas().stream()
                .filter(schema -> schema.getName().equals(toolName))
                .findFirst()
                .orElseThrow();
    }

    private static QwenImageGenerationTool configuredQwenTool() {
        return QwenImageGenerationTool.create(
                "sk-test",
                "https://dashscope.test/api/v1",
                "qwen-image-plus",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
    }

    private static AppEntity app(String imageModelId, String videoModelId) {
        AppEntity app = new AppEntity();
        app.setId("app-1");
        app.setImageModelId(imageModelId);
        app.setVideoModelId(videoModelId);
        return app;
    }
}
