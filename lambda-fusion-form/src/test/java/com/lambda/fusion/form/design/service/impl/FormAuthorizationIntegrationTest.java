package com.lambda.fusion.form.design.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.annotation.handler.SaAnnotationHandlerInterface;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaTokenContextRegister;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import com.lambda.cloud.core.Constants;
import com.lambda.cloud.core.utils.StpLogicUtils;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.form.design.controller.FormDesignController;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormFieldItem;
import com.lambda.fusion.form.design.model.FormPreviewDataCommand;
import com.lambda.fusion.form.design.model.FormPreviewSource;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.service.FormPreviewService;
import com.lambda.fusion.form.design.service.FormPublishService;
import com.lambda.fusion.form.design.service.FormRouteService;
import com.lambda.fusion.form.runtime.assembler.FormSchemaAssembler;
import com.lambda.fusion.form.runtime.controller.FormDataController;
import com.lambda.fusion.form.runtime.integration.FormDataInterceptors;
import com.lambda.fusion.form.runtime.interceptor.FormPermission;
import com.lambda.fusion.form.runtime.interceptor.FormPermissionHandler;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.service.impl.FormRuntimeServiceImpl;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.security.inteceptor.SaTokenInterceptor;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/** 真实 Cloud 拦截器、Sa-Token 注解扩展、Service 与 Mapper；只替换登录身份和授权数据源。 */
class FormAuthorizationIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private MockedStatic<StpLogicUtils> activeLogin;
    private StpLogic originalLogic;
    private SaAnnotationHandlerInterface<?> originalHandler;
    private List<String> roles = List.of(FusionConstants.ROLE_DEV);
    private List<String> permissions = List.of();
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private String formId;
    private ObjectMapper json;
    private FormRuntimeServiceImpl runtime;

    @BeforeEach
    void setUp() {
        originalLogic = StpUtil.getStpLogic();
        originalHandler = SaAnnotationStrategy.instance.annotationHandlerMap.get(FormPermission.class);
        StpLogic logic = spy(new StpLogic(originalLogic.getLoginType()));
        doReturn("designer").when(logic).getLoginId();
        doAnswer(invocation -> roles).when(logic).getRoleList("designer");
        doAnswer(invocation -> permissions).when(logic).getPermissionList("designer");
        StpUtil.setStpLogic(logic);
        activeLogin = mockStatic(StpLogicUtils.class);
        activeLogin.when(StpLogicUtils::getActiveStpLogic).thenReturn(logic);
        context = new AnnotationConfigApplicationContext();
        context.register(
                FormLifecycleIntegrationTest.Config.class,
                FormPermissionHandler.class,
                SaBeanInject.class,
                SaTokenContextRegister.class);
        context.refresh();
        assertThat(SaAnnotationStrategy.instance.annotationHandlerMap.get(FormPermission.class))
                .isSameAs(context.getBean(FormPermissionHandler.class));
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        json = context.getBean(ObjectMapper.class);
        var design = context.getBean(FormDesignService.class);
        var publishing = context.getBean(FormPublishService.class);
        formId = design.save(command("test_form"));
        publishing.publish(formId);
        runtime = new FormRuntimeServiceImpl(
                context.getBean(FormModelProvider.class),
                context.getBean(DynamicDataService.class),
                context.getBean(FormSchemaAssembler.class));
        UserDetails operator = new UserDetails();
        operator.setUsername("designer");
        mvc = MockMvcBuilders.standaloneSetup(
                        new FormDesignController(
                                design,
                                publishing,
                                context.getBean(FormPreviewService.class),
                                mock(FormRouteService.class),
                                context.getBean(FormDataInterceptors.class)),
                        new FormDataController(runtime))
                .defaultRequest(get("/").requestAttr(Constants.LOGIN_USER, operator))
                .addFilters(new SaTokenContextFilterForJakartaServlet())
                .addInterceptors(
                        new SaInterceptor(new SaTokenInterceptor((handler, active, user) -> active.checkLogin()))
                                .isAnnotation(false))
                .build();
    }

    @AfterEach
    void tearDown() {
        context.close();
        StpUtil.setStpLogic(originalLogic);
        activeLogin.close();
        if (originalHandler == null) {
            SaAnnotationStrategy.instance.removeAnnotationHandler(FormPermission.class);
        } else {
            SaAnnotationStrategy.instance.registerAnnotationHandler(originalHandler);
        }
    }

    @Test
    void developerWithoutIndividualPermissionsCanPreviewAllSourcesAndUseRuntime() throws Exception {
        mvc.perform(get("/v1/form-design/{id}/preview", formId)).andExpect(status().isOk());
        mvc.perform(get("/v1/form-design/{id}/versions/1/preview", formId)).andExpect(status().isOk());
        mvc.perform(post("/v1/form-design/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new FormPreviewSource(null, null, null, command("unsaved")))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_BUSINESS", Integer.class))
                .isZero();
        mvc.perform(post("/v1/form/test_form")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personName\":\"Developer\"}"))
                .andExpect(status().isOk());
        assertThat(runtime.schema("test_form").getPermissions())
                .containsEntry("page", true)
                .containsEntry("add", true)
                .containsEntry("edit", true)
                .containsEntry("delete", true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_BUSINESS", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void detailOrPagePermissionDoesNotReplacePreviewPermission() {
        roles = List.of("ROLE_ADMIN");
        permissions = List.of("form:design:page", "form:design:detail");
        assertThatThrownBy(() -> mvc.perform(get("/v1/form-design/{id}/preview", formId)))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        assertThatThrownBy(() -> mvc.perform(post("/v1/form/test_form")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .hasRootCauseInstanceOf(NotPermissionException.class);
    }

    @Test
    void grantedPreviewWorksAndRevocationTakesEffectWithoutGrantingDataOrPublish() throws Exception {
        roles = List.of();
        permissions = List.of("form:design:preview");
        mvc.perform(get("/v1/form-design/{id}/preview", formId)).andExpect(status().isOk());
        assertThatThrownBy(() -> mvc.perform(post("/v1/form-design/{id}/publish", formId)))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        assertThatThrownBy(() -> mvc.perform(get("/v1/form/test_form/schema")))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        permissions = List.of();
        assertThatThrownBy(() -> mvc.perform(get("/v1/form-design/{id}/preview", formId)))
                .hasRootCauseInstanceOf(NotPermissionException.class);
    }

    @Test
    void dynamicPermissionIsScopedToCurrentFormAndAction() throws Exception {
        roles = List.of();
        permissions = List.of("form:test_form:add");
        mvc.perform(post("/v1/form/test_form")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"personName\":\"User\"}"))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> mvc.perform(get("/v1/form/test_form/schema")))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        assertThatThrownBy(() -> mvc.perform(post("/v1/form/other_form")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        assertThat(runtime.schema("test_form").getPermissions())
                .containsEntry("add", true)
                .containsEntry("edit", false);
    }

    @Test
    void previewCrudUsesRealRowsAndDoesNotSaveOrPublishTheDraft() throws Exception {
        var preview = context.getBean(FormPreviewService.class);
        var source =
                new FormPreviewSource(formId, null, preview.previewDraft(formId).getDraftVersion(), null);
        String id = mvc.perform(post("/v1/form-design/preview/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new FormPreviewDataCommand(source, null, Map.of("personName", "Created")))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(jdbc.queryForObject("SELECT PERSON_NAME FROM LA_TEST_BUSINESS WHERE ID=?", String.class, id))
                .isEqualTo("Created");
        mvc.perform(post("/v1/form-design/preview/data/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new FormPreviewDataCommand(source, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].personName").value("Created"));
        mvc.perform(put("/v1/form-design/preview/data/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new FormPreviewDataCommand(source, null, Map.of("personName", "Updated")))))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/form-design/preview/data/{id}/detail", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(source)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personName").value("Updated"));
        // 关闭/重新打开仅重新读取元数据，已提交业务数据仍在同一张真实表里。
        assertThat(preview.previewDraft(formId).getDraftVersion()).isEqualTo(source.draftVersion());
        assertThat(runtime.getById("test_form", id)).containsEntry("personName", "Updated");
        mvc.perform(delete("/v1/form-design/preview/data/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(source)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_BUSINESS", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM la_form_def_version", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void previewDataRequiresBothPreviewAndFormActionAndChecksDesignOverrides() throws Exception {
        var source = new FormPreviewSource(formId, 1L, null, null);
        String body =
                json.writeValueAsString(new FormPreviewDataCommand(source, null, Map.of("personName", "Allowed")));
        roles = List.of();
        permissions = List.of("form:design:preview");
        mvc.perform(get("/v1/form-design/{id}/preview", formId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.add").value(false));
        assertThatThrownBy(() -> mvc.perform(post("/v1/form-design/preview/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        permissions = List.of("form:test_form:add");
        assertThatThrownBy(() -> mvc.perform(post("/v1/form-design/preview/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        permissions = List.of("form:design:preview", "form:other_form:add");
        assertThatThrownBy(() -> mvc.perform(post("/v1/form-design/preview/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        permissions = List.of("form:design:preview", "form:test_form:add");
        mvc.perform(post("/v1/form-design/preview/data")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        assertThatThrownBy(() -> mvc.perform(post("/v1/form-design/preview/data/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        var override = command("test_form");
        override.setVersion(
                context.getBean(FormDesignService.class).detail(formId).getVersion());
        assertThatThrownBy(() -> mvc.perform(post("/v1/form-design/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new FormPreviewSource(formId, null, null, override)))))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        permissions = List.of("form:design:preview", "form:design:edit", "form:test_form:page");
        mvc.perform(post("/v1/form-design/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new FormPreviewSource(formId, null, null, override))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.page").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LA_TEST_BUSINESS", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void automaticPublishRetainsItsPermissionBoundaryAndDeveloperExemption() throws Exception {
        var command = command("automatic");
        command.setPublishOnSave(true);
        roles = List.of();
        permissions = List.of("form:design:add");
        assertThatThrownBy(() -> mvc.perform(post("/v1/form-design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(command))))
                .hasRootCauseInstanceOf(NotPermissionException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM la_form_def", Integer.class))
                .isEqualTo(1);
        roles = List.of(FusionConstants.ROLE_DEV);
        permissions = List.of();
        mvc.perform(post("/v1/form-design")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(command)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM la_form_def_version", Integer.class))
                .isEqualTo(2);
    }

    private FormDefSaveCommand command(String key) {
        var command = new FormDefSaveCommand();
        command.setFormKey(key);
        command.setFormName("Test");
        command.setFormType("SINGLE");
        command.setTableName("LA_TEST_BUSINESS");
        var field = new FormFieldItem();
        field.setFieldName("personName");
        field.setColumnName("PERSON_NAME");
        field.setFieldType("STRING");
        command.setFields(List.of(field));
        return command;
    }
}
