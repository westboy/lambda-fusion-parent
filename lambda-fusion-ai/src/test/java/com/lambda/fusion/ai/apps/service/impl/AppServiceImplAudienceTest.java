package com.lambda.fusion.ai.apps.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.AiConstants.AppAudience;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.mapper.AppConfigAuditMapper;
import com.lambda.fusion.ai.apps.mapper.AppMapper;
import com.lambda.fusion.ai.apps.model.AvailableApp;
import com.lambda.fusion.ai.apps.model.CreateApp;
import com.lambda.fusion.ai.apps.model.UpdateApp;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.model.entity.LlmProviderEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.runtime.workspace.WorkspacePaths;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 验证应用受众收敛：创建/更新对非法 audience 硬校验拒绝；可见性经公有 {@code listAvailable}
 * 按 {@code B/C/ALL} 显式分支过滤，历史脏数据（未知受众）不落入任何分支、显式不可见。
 *
 * @author Jin
 */
class AppServiceImplAudienceTest {

    private AppMapper appMapper;
    private LlmModelService llmModelService;
    private LlmProviderService llmProviderService;
    private AiProperties properties;
    private AppServiceImpl service;
    private UserDetails user;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        properties = new AiProperties();
        properties.getAudience().getBRoles().add("ROLE_B");
        properties.getAudience().getCRoles().add("ROLE_C");
        llmModelService = mock(LlmModelService.class);
        llmProviderService = mock(LlmProviderService.class);
        // 绑定校验：应用绑定/回填视图都会按 modelId 查模型，统一返回 CHAT 型
        LlmModelEntity chatModel = new LlmModelEntity();
        chatModel.setModelType(ModelType.CHAT);
        when(llmModelService.loadById(anyString())).thenReturn(chatModel);
        user = mock(UserDetails.class);
        when(user.getUsername()).thenReturn("user-1");
        service = new AppServiceImpl(
                appMapper,
                mock(AppConfigAuditMapper.class),
                llmModelService,
                llmProviderService,
                mock(ApplicationEventPublisher.class),
                mock(WorkspacePaths.class),
                properties);
    }

    @Test
    void shouldRejectInvalidAudienceOnCreate() {
        CreateApp dto = new CreateApp();
        dto.setModelId("m-1");
        dto.setName("demo");
        dto.setAudience("VIP");

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(AiBusinessException.class)
                .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                        .isEqualTo(AiErrorCode.APP_AUDIENCE_INVALID.getCode()));
    }

    @Test
    void shouldRejectInvalidAudienceOnUpdate() {
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(app("a1", "ALL"));
        UpdateApp dto = new UpdateApp();
        dto.setAudience("GUEST");

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            assertThatThrownBy(() -> service.update("a1", dto))
                    .isInstanceOf(AiBusinessException.class)
                    .satisfies(e -> assertThat(((AiBusinessException) e).getCode())
                            .isEqualTo(AiErrorCode.APP_AUDIENCE_INVALID.getCode()));
        }
    }

    @Test
    void shouldFilterVisibleAppsByAudienceBranches() {
        // 平台应用：ALL / B / C / 未知脏数据各一；listAvailable 经公有路径按角色过滤。
        List<AppEntity> stored =
                List.of(app("all-app", "ALL"), app("b-app", "B"), app("c-app", "C"), app("dirty-app", "VIP"));
        when(appMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(stored);
        when(user.getRoles()).thenReturn(Set.of("ROLE_C"));

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            List<AppEntity> visible = service.listAvailable();
            assertThat(visible).extracting(AppEntity::getId).containsExactlyInAnyOrder("all-app", "c-app");
        }
    }

    @Test
    void shouldLoadAvailableViewWithVisionAndMediaCapabilities() {
        AppEntity stored = app("a1", "ALL");
        stored.setModelId("m-1");
        stored.setImageModelId("image-1");
        stored.setVideoModelId("video-1");
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stored);
        LlmModelEntity chatModel = new LlmModelEntity();
        chatModel.setSupportsVision(Boolean.TRUE);
        when(llmModelService.loadById("m-1")).thenReturn(chatModel);
        LlmModelEntity imageModel = mediaModel("image-1", ModelType.IMAGE_GEN, "海报模型", "wanx-v1");
        LlmModelEntity videoModel = mediaModel("video-1", ModelType.VIDEO_GEN, "视频模型", "wan2.6-t2v");
        when(llmModelService.loadById("image-1")).thenReturn(imageModel);
        when(llmModelService.loadById("video-1")).thenReturn(videoModel);
        LlmProviderEntity provider = new LlmProviderEntity();
        provider.setId("p-1");
        provider.setProviderType("dashscope");
        provider.setEnabled(Boolean.TRUE);
        provider.setEnableMediaGen(Boolean.TRUE);
        when(llmProviderService.loadById("p-1")).thenReturn(provider);

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            AvailableApp view = service.loadAvailableView("a1");

            assertThat(view.getId()).isEqualTo("a1");
            assertThat(view.getSupportsVision()).isTrue();
            assertThat(view.getSupportsImageGeneration()).isTrue();
            assertThat(view.getSupportsVideoGeneration()).isTrue();
            assertThat(view.getMediaGenerationCapabilities())
                    .extracting(capability -> capability.getKind() + ":" + capability.getModelLabel())
                    .containsExactly("image:海报模型", "video:视频模型");
        }
    }

    @Test
    void shouldParseAudienceCaseInsensitivelyAndRejectUnknown() {
        assertThat(AppAudience.of("all")).isEqualTo(AppAudience.ALL);
        assertThat(AppAudience.of("b")).isEqualTo(AppAudience.B);
        assertThat(AppAudience.of("C")).isEqualTo(AppAudience.C);
        assertThat(AppAudience.of("VIP")).isNull();
        assertThat(AppAudience.of(null)).isNull();
    }

    private static AppEntity app(String id, String audience) {
        AppEntity app = new AppEntity();
        app.setId(id);
        app.setTenantId("tenant-1");
        app.setName(id);
        app.setAudience(audience);
        app.setAppType("CHAT");
        app.setEnabled(Boolean.TRUE);
        return app;
    }

    private static LlmModelEntity mediaModel(String id, ModelType type, String name, String modelName) {
        LlmModelEntity model = new LlmModelEntity();
        model.setId(id);
        model.setModelType(type);
        model.setName(name);
        model.setModelName(modelName);
        model.setProviderId("p-1");
        model.setEnabled(Boolean.TRUE);
        return model;
    }
}
