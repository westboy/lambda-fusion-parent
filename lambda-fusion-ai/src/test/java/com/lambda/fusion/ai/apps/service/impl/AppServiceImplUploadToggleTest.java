package com.lambda.fusion.ai.apps.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.AiConstants.ModelType;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.mapper.AppConfigAuditMapper;
import com.lambda.fusion.ai.apps.mapper.AppMapper;
import com.lambda.fusion.ai.apps.model.AvailableApp;
import com.lambda.fusion.ai.apps.model.CreateApp;
import com.lambda.fusion.ai.apps.model.UpdateApp;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.llm.model.entity.LlmModelEntity;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.llm.service.LlmProviderService;
import com.lambda.fusion.ai.runtime.workspace.WorkspacePaths;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 验证终端用户上传开关：创建默认关闭、显式开启可读回；更新按 null 不覆盖语义仅改写显式传入的开关；
 * 聊天/发布安全视图 {@code toAvailableView} 携带开关供前端显隐入口。
 *
 * @author Jin
 */
class AppServiceImplUploadToggleTest {

    private AppMapper appMapper;
    private AppServiceImpl service;
    private UserDetails user;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        LlmModelService llmModelService = mock(LlmModelService.class);
        LlmProviderService llmProviderService = mock(LlmProviderService.class);
        LlmModelEntity chatModel = new LlmModelEntity();
        chatModel.setModelType(ModelType.CHAT);
        when(llmModelService.loadById(anyString())).thenReturn(chatModel);
        user = mock(UserDetails.class);
        when(user.getUsername()).thenReturn("user-1");
        service = new AppServiceImpl(
                appMapper,
                mock(AppConfigAuditMapper.class),
                llmModelService,
                new MediaGenerationBindingResolver(llmModelService, llmProviderService),
                mock(ApplicationEventPublisher.class),
                mock(WorkspacePaths.class),
                new AiProperties());
    }

    @Test
    void shouldDefaultUploadTogglesOffWhenCreateOmitsThem() {
        CreateApp dto = new CreateApp();
        dto.setModelId("m-1");
        dto.setName("demo");

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            service.create(dto);
        }

        AppEntity inserted = captureInsert();
        assertThat(inserted.getEnableImageUpload()).isFalse();
        assertThat(inserted.getEnableFileUpload()).isFalse();
    }

    @Test
    void shouldPersistExplicitUploadTogglesOnCreate() {
        CreateApp dto = new CreateApp();
        dto.setModelId("m-1");
        dto.setName("demo");
        dto.setEnableImageUpload(true);
        dto.setEnableFileUpload(true);

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            service.create(dto);
        }

        AppEntity inserted = captureInsert();
        assertThat(inserted.getEnableImageUpload()).isTrue();
        assertThat(inserted.getEnableFileUpload()).isTrue();
    }

    @Test
    void shouldNotOverwriteUntouchedUploadTogglesOnUpdate() {
        AppEntity stored = app("a1");
        stored.setEnableImageUpload(true);
        stored.setEnableFileUpload(false);
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stored);

        UpdateApp dto = new UpdateApp();
        // 仅显式开启附件上传，图片开关不传入（null）应保持原值 true
        dto.setEnableFileUpload(true);

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            service.update("a1", dto);
        }

        ArgumentCaptor<AppEntity> captor = ArgumentCaptor.forClass(AppEntity.class);
        verify(appMapper).updateById(captor.capture());
        AppEntity updated = captor.getValue();
        assertThat(updated.getEnableImageUpload()).isTrue();
        assertThat(updated.getEnableFileUpload()).isTrue();
    }

    @Test
    void shouldExposeUploadTogglesInAvailableView() {
        AppEntity stored = app("a1");
        stored.setEnableImageUpload(true);
        stored.setEnableFileUpload(true);
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(stored);

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            AvailableApp view = service.loadAvailableView("a1");

            assertThat(view.getEnableImageUpload()).isTrue();
            assertThat(view.getEnableFileUpload()).isTrue();
        }
    }

    private AppEntity captureInsert() {
        ArgumentCaptor<AppEntity> captor = ArgumentCaptor.forClass(AppEntity.class);
        verify(appMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static AppEntity app(String id) {
        AppEntity app = new AppEntity();
        app.setId(id);
        app.setTenantId("tenant-1");
        app.setName(id);
        app.setAudience("ALL");
        app.setAppType("CHAT");
        app.setModelId("m-1");
        app.setEnabled(Boolean.TRUE);
        return app;
    }
}
