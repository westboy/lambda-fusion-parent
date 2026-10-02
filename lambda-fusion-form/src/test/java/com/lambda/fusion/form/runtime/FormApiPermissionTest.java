package com.lambda.fusion.form.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.runtime.controller.FormDataController;
import com.lambda.fusion.form.runtime.provider.FormApiPermissionProvider;
import com.lambda.fusion.form.runtime.service.FormRuntimeService;
import com.lambda.fusion.permission.PermissionProperties;
import com.lambda.fusion.permission.loader.LocalPermissionLoader;
import com.lambda.fusion.permission.model.ApiPermissionMetadata;
import com.lambda.fusion.permission.model.PermissionFileMetadata;
import com.lambda.fusion.permission.service.ApiPermissionMatcher;
import com.lambda.fusion.permission.service.ApiPermissionRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

class FormApiPermissionTest {
    @Test
    void processorOutputIsLoadedAndMatchedByTheExistingPermissionApi() {
        var loader = new LocalPermissionLoader(new PermissionProperties(), new ObjectMapper());
        var registry = new ApiPermissionRegistry(List.of());
        registry.replaceLocal(loader.load(), "form-app");
        var matcher = new ApiPermissionMatcher();
        var draft = matcher.match(registry.getLocalApis(), "GET", "/v1/form-design/f1/preview")
                .orElseThrow();
        var unsaved = matcher.match(registry.getLocalApis(), "POST", "/v1/form-design/preview")
                .orElseThrow();
        var historical = matcher.match(registry.getLocalApis(), "GET", "/v1/form-design/f1/versions/1/preview")
                .orElseThrow();
        assertThat(draft.getPermissions()).containsExactly("form:design:preview");
        assertThat(unsaved.getPermissions()).containsExactly("form:design:preview");
        assertThat(historical.getPermissions()).containsExactly("form:design:preview");
        for (var endpoint : List.of(
                List.of("POST", "/v1/form-design/preview/data/page"),
                List.of("POST", "/v1/form-design/preview/data/r1/detail"),
                List.of("POST", "/v1/form-design/preview/data"),
                List.of("PUT", "/v1/form-design/preview/data/r1"),
                List.of("DELETE", "/v1/form-design/preview/data/r1"))) {
            assertThat(matcher.match(registry.getLocalApis(), endpoint.get(0), endpoint.get(1))
                            .orElseThrow()
                            .getPermissions())
                    .containsExactly("form:design:preview");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void catalogDerivesAllSevenMvcEndpointsAndSurvivesStaticMetadataReplacement(boolean tenantEnabled) {
        FormDefMapper forms = mock(FormDefMapper.class);
        FormDefEntity definition = new FormDefEntity();
        definition.setFormKey("leave_demo");
        definition.setFormName("请假申请");
        definition.setPublishedVersion(1L);
        when(forms.selectList(any())).thenReturn(List.of(definition));
        try (var context = new StaticWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.getBeanFactory()
                    .registerSingleton("controller", new FormDataController(mock(FormRuntimeService.class)));
            context.refresh();
            var mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
            var factory = new StaticListableBeanFactory();
            factory.addBean("mapping", mapping);
            var mybatisProperties = new MybatisPlusExtendProperties();
            mybatisProperties.getTenant().setEnabled(tenantEnabled);
            var provider = new FormApiPermissionProvider(
                    forms, factory.getBeanProvider(RequestMappingHandlerMapping.class), "form-app", mybatisProperties);
            if (tenantEnabled) {
                assertThat(provider.getApis()).isEmpty();
                verifyNoInteractions(forms);
            } else {
                assertThat(provider.getApis()).hasSize(7);
            }
            try (var tenant = TenantContextHolder.getInstance()) {
                tenant.setTenantId("tenant-1");
                var registry = new ApiPermissionRegistry(List.of(provider));
                registry.replaceLocal(List.of(), "form-app");
                assertThat(registry.getLocalApis()).hasSize(7);
                assertThat(new ApiPermissionMatcher()
                                .match(registry.getLocalApis(), "POST", "/v1/form/leave_demo")
                                .orElseThrow()
                                .getPermissions())
                        .containsExactly("form:leave_demo:add");
                assertThat(registry.getLocalApis())
                        .extracting(ApiPermissionMetadata::getPath)
                        .contains("/v1/form/leave_demo/schema", "/v1/form/leave_demo/{id}");
                assertThat(registry.getLocalApis().stream()
                                .flatMap(api -> api.getPermissions().stream())
                                .distinct())
                        .containsExactlyInAnyOrder(
                                "form:leave_demo:page",
                                "form:leave_demo:add",
                                "form:leave_demo:edit",
                                "form:leave_demo:delete");
                registry.replaceLocal(List.of(new PermissionFileMetadata()), "form-app");
                assertThat(registry.getReportedApis("form-app")).hasSize(7);
                assertThat(registry.getApplications()).contains("form-app");
                definition.setPublishedVersion(2L);
                assertThat(registry.getReportedApis("form-app")).hasSize(7);
                when(forms.selectList(any())).thenReturn(List.of());
                assertThat(registry.getReportedApis("form-app")).isEmpty();
            }
        }
    }
}
