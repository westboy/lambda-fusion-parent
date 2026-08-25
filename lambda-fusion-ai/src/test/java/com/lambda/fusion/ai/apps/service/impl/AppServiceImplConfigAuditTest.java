package com.lambda.fusion.ai.apps.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.mapper.AppConfigAuditMapper;
import com.lambda.fusion.ai.apps.mapper.AppMapper;
import com.lambda.fusion.ai.apps.model.UpdateApp;
import com.lambda.fusion.ai.apps.model.entity.AppConfigAuditEntity;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.apps.service.MediaGenerationBindingResolver;
import com.lambda.fusion.ai.llm.service.LlmModelService;
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
 * 验证 {@link AppServiceImpl} 的配置审计织入：更新/删除在改库前写变更前快照（append-only），
 * 审计失败不阻断主流程。
 *
 * @author Jin
 */
class AppServiceImplConfigAuditTest {

    private AppMapper appMapper;
    private AppConfigAuditMapper auditMapper;
    private MediaGenerationBindingResolver mediaBindingResolver;
    private AppServiceImpl service;
    private UserDetails operator;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        auditMapper = mock(AppConfigAuditMapper.class);
        mediaBindingResolver = mock(MediaGenerationBindingResolver.class);
        operator = mock(UserDetails.class);
        when(operator.getUsername()).thenReturn("dev-1");
        service = new AppServiceImpl(
                appMapper,
                auditMapper,
                mock(LlmModelService.class),
                mediaBindingResolver,
                mock(ApplicationEventPublisher.class),
                mock(WorkspacePaths.class),
                new AiProperties());
    }

    @Test
    void shouldRecordBeforeUpdateWithSnapshotOfPriorConfig() {
        AppEntity existing = existingApp();
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        UpdateApp dto = new UpdateApp();
        dto.setSystemPrompt("新的提示词");

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(operator);
            service.update("app-1", dto);
        }

        ArgumentCaptor<AppConfigAuditEntity> captor = ArgumentCaptor.forClass(AppConfigAuditEntity.class);
        verify(auditMapper).insert(captor.capture());
        AppConfigAuditEntity audit = captor.getValue();
        assertThat(audit.getOperation()).isEqualTo("UPDATE");
        assertThat(audit.getAppId()).isEqualTo("app-1");
        assertThat(audit.getTenantId()).isEqualTo("tenant-1");
        assertThat(audit.getOperator()).isEqualTo("dev-1");
        // 快照必须是变更前内容（旧提示词），且不含非表字段 supportsVision。
        assertThat(audit.getConfigJson()).contains("旧提示词");
        assertThat(audit.getConfigJson()).doesNotContain("supportsVision");
        verify(appMapper).updateById(any(AppEntity.class));
    }

    @Test
    void shouldRecordBeforeDelete() {
        AppEntity existing = existingApp();
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(operator);
            service.delete("app-1");
        }

        ArgumentCaptor<AppConfigAuditEntity> captor = ArgumentCaptor.forClass(AppConfigAuditEntity.class);
        verify(auditMapper).insert(captor.capture());
        assertThat(captor.getValue().getOperation()).isEqualTo("DELETE");
        verify(appMapper).deleteById("app-1");
    }

    @Test
    void shouldNotBlockUpdateWhenAuditFails() {
        AppEntity existing = existingApp();
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        when(auditMapper.insert(any(AppConfigAuditEntity.class))).thenThrow(new RuntimeException("db down"));

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(operator);
            // 审计失败只告警，更新仍应完成。
            service.update("app-1", new UpdateApp());
        }

        verify(appMapper).updateById(any(AppEntity.class));
    }

    @Test
    void shouldSkipAuditWhenAppNotFound() {
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(operator);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.update("missing", new UpdateApp()))
                    .isInstanceOf(com.lambda.fusion.ai.exception.AiBusinessException.class);
        }
        verify(auditMapper, never()).insert(any(AppConfigAuditEntity.class));
    }

    @Test
    void shouldClearAllOptionalMediaBindings() {
        AppEntity existing = existingApp();
        existing.setImageModelId("image-1");
        existing.setVideoModelId("video-1");
        existing.setAudioModelId("audio-1");
        when(appMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        UpdateApp dto = new UpdateApp();
        dto.setImageModelId("");
        dto.setVideoModelId("");
        dto.setAudioModelId("");

        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(operator);
            service.update("app-1", dto);
        }

        ArgumentCaptor<AppEntity> captor = ArgumentCaptor.forClass(AppEntity.class);
        verify(appMapper).updateById(captor.capture());
        assertThat(captor.getValue().getImageModelId()).isNull();
        assertThat(captor.getValue().getVideoModelId()).isNull();
        assertThat(captor.getValue().getAudioModelId()).isNull();
        verify(mediaBindingResolver, never()).requireExecutable(any(), any());
    }

    private static AppEntity existingApp() {
        AppEntity app = new AppEntity();
        app.setId("app-1");
        app.setTenantId("tenant-1");
        app.setName("demo");
        app.setSystemPrompt("旧提示词");
        app.setAppType("CHAT");
        app.setEnabled(Boolean.TRUE);
        app.setSupportsVision(Boolean.TRUE);
        return app;
    }
}
