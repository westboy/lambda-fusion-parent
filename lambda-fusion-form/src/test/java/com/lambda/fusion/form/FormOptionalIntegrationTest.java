package com.lambda.fusion.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.authority.api.RemoteResource;
import com.lambda.fusion.authority.api.RemoteResourceService;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.model.FormRouteCommand;
import com.lambda.fusion.form.design.model.FormRouteInfo;
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
        // authority 契约在场但无提供方（也无 Dubbo）→ 装配为空（NullBean），按网关缺席降级
        new ApplicationContextRunner()
                .withClassLoader(new FilteredClassLoader("org.apache.dubbo"))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(FormConfigure.AuthorityIntegration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // @Bean 返回 null → NullBean：可选注入（getIfAvailable）视为未装配
                    assertThat(context.getBeanProvider(FormRouteGateway.class).getIfAvailable())
                            .as("网关按资源服务缺席降级为未装配")
                            .isNull();
                    FormDefMapper forms = mock(FormDefMapper.class);
                    when(forms.selectById("f1")).thenReturn(new FormDefEntity());
                    var service = new FormRouteServiceImpl(
                            forms, context.getBeanFactory().getBeanProvider(FormRouteGateway.class));
                    assertThat(service.detail("f1").available()).isFalse();
                    assertThatThrownBy(() ->
                                    service.save("f1", new FormRouteCommand("parent", "测试", "/forms/test", null, 1)))
                            .hasMessageContaining("未提供菜单管理能力");
                });
    }

    @Test
    void installedRemoteResourceServiceEnablesTheAdapter() {
        // 契约提供方在场（单体中为 authority 适配器 Bean）：装配网关并跑通 read 的契约映射
        new ApplicationContextRunner()
                .withBean(RemoteResourceService.class, () -> mock(RemoteResourceService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(FormConfigure.AuthorityIntegration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(FormRouteGateway.class);
                    FormRouteGateway gateway = context.getBean(FormRouteGateway.class);
                    FormDefEntity form = new FormDefEntity();
                    form.setId("f1");
                    form.setFormKey("order_demo");
                    form.setRouteResourceId("menu-1");
                    RemoteResource menu = new RemoteResource();
                    menu.setId("menu-1");
                    menu.setParentId("root");
                    menu.setResName("订单测试");
                    menu.setResPath("/forms/order");
                    menu.setResType(1);
                    // 归属标记：网关校验菜单绑定归属（与本网关 marker() 的 JSON 结构一致）
                    menu.setBusinessExpand("{\"kind\":\"dynamic-form\",\"formId\":\"f1\",\"action\":\"menu\"}");
                    when(context.getBean(RemoteResourceService.class).getResourceById("menu-1"))
                            .thenReturn(menu);
                    FormRouteInfo info = gateway.read(form);
                    assertThat(info.available()).isTrue();
                    assertThat(info.mounted()).isTrue(); // 菜单资源存在即视为已挂载
                    assertThat(info.name()).isEqualTo("订单测试");
                });
    }
}
