package com.lambda.fusion.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.mcp.service.McpServerService;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.security.KeyEncryptionService;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

/**
 * 文生图工具装配条件测试：仅在应用绑定了启用中的 {@code IMAGE_GEN} 模型、且所属 provider 启用了
 * 媒体生成开关时挂载；provider 关闭开关、模型类型不符、未绑定均跳过，不阻断工具集构建。
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
                llmModelService,
                llmProviderService,
                keyEncryptionService);
    }

    @Test
    void shouldRegisterImageToolWhenModelBoundAndProviderEnabled() {
        stubImageModel(ModelType.IMAGE_GEN, Boolean.TRUE, "dashscope", Boolean.TRUE);

        Toolkit toolkit = assembler.build(app("img-1"));

        assertThat(toolkit.getToolNames()).contains(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenProviderMediaGenSwitchOff() {
        stubImageModel(ModelType.IMAGE_GEN, Boolean.TRUE, "dashscope", null);

        assertThat(assembler.build(app("img-1")).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenModelTypeMismatch() {
        // 绑定入口已强校验类型；此处防御配置后续变化（如模型类型被改）
        stubImageModel(ModelType.CHAT, Boolean.TRUE, "dashscope", Boolean.TRUE);

        assertThat(assembler.build(app("img-1")).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenModelDisabled() {
        stubImageModel(ModelType.IMAGE_GEN, Boolean.FALSE, "dashscope", Boolean.TRUE);

        assertThat(assembler.build(app("img-1")).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenProviderTypeUnsupported() {
        stubImageModel(ModelType.IMAGE_GEN, Boolean.TRUE, "ollama", Boolean.TRUE);

        assertThat(assembler.build(app("img-1")).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    @Test
    void shouldSkipImageToolWhenAppHasNoBinding() {
        assertThat(assembler.build(app(null)).getToolNames()).doesNotContain(ImageGenerationTool.TOOL_NAME);
    }

    private void stubImageModel(ModelType modelType, Boolean modelEnabled, String providerType, Boolean mediaGen) {
        LlmModelEntity model = new LlmModelEntity();
        model.setId("img-1");
        model.setModelType(modelType);
        model.setEnabled(modelEnabled);
        model.setModelName("wanx-v1");
        model.setProviderId("p-1");
        when(llmModelService.loadById("img-1")).thenReturn(model);

        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId("p-1");
        provider.setProviderType(providerType);
        provider.setEnabled(Boolean.TRUE);
        provider.setEnableMediaGen(mediaGen);
        provider.setApiKeyEncrypted("enc");
        when(llmProviderService.loadById("p-1")).thenReturn(provider);
        when(keyEncryptionService.decrypt("enc")).thenReturn("sk-test");
    }

    private static AppEntity app(String imageModelId) {
        AppEntity app = new AppEntity();
        app.setId("app-1");
        app.setImageModelId(imageModelId);
        return app;
    }
}
