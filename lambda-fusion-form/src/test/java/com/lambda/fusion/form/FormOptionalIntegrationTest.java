package com.lambda.fusion.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.authority.resource.service.PermissionService;
import com.lambda.fusion.authority.resource.service.ResourceService;
import com.lambda.fusion.form.design.adapter.FormPathRegistry;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.model.FormRouteCommand;
import com.lambda.fusion.form.design.service.impl.FormRouteServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class FormOptionalIntegrationTest {
    @Test
    void absentAuthorityClassesDoNotLoadTheRouteAdapter() {
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("com.lambda.fusion.authority"))
                .withUserConfiguration(FormConfigure.AuthorityIntegration.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("formRouteGateway"));
    }

    @Test
    void missingMenuServicesExposeUnavailableStateAndRejectWrites() {
        new ApplicationContextRunner()
                .withUserConfiguration(FormConfigure.AuthorityIntegration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(FormRouteGateway.class);
                    FormDefMapper forms = mock(FormDefMapper.class);
                    when(forms.selectById("f1")).thenReturn(new FormDefEntity());
                    var service = new FormRouteServiceImpl(
                            forms,
                            context.getBeanFactory().getBeanProvider(FormRouteGateway.class),
                            context.getBeanFactory().getBeanProvider(FormPathRegistry.class));
                    assertThat(service.detail("f1").available()).isFalse();
                    assertThatThrownBy(() ->
                                    service.save("f1", new FormRouteCommand("parent", "测试", "/forms/test", null, 1)))
                            .hasMessageContaining("未提供菜单管理能力");
                });
    }

    @Test
    void installedAuthorityServicesEnableTheAdapter() {
        new ApplicationContextRunner()
                .withBean(ResourceService.class, () -> mock(ResourceService.class))
                .withBean(PermissionService.class, () -> mock(PermissionService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(FormConfigure.AuthorityIntegration.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(FormRouteGateway.class));
    }
}
