package com.lambda.fusion.form.design.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.lambda.autoconfig.MybatisPlusExtendProperties;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormFieldItem;
import com.lambda.fusion.form.design.model.FormPreviewSource;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.service.FormPreviewService;
import com.lambda.fusion.form.design.service.FormPublishService;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.model.AstCondition;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.service.DynamicDataService;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 真实事务、Mapper、目标表；授权边界由 FormAuthorizationIntegrationTest 独立覆盖。 */
class FormPreviewDataIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private MockedStatic<AuthUtils> auth;
    private FormDesignService design;
    private FormPreviewService preview;
    private FormPublishService publish;
    private JdbcTemplate jdbc;
    private String tenant = "tenant-a";

    @BeforeEach
    void setUp() {
        auth = mockStatic(AuthUtils.class);
        auth.when(() -> AuthUtils.hasPermission(anyString())).thenReturn(true);
        context = new AnnotationConfigApplicationContext(FormLifecycleIntegrationTest.Config.class);
        design = context.getBean(FormDesignService.class);
        preview = context.getBean(FormPreviewService.class);
        publish = context.getBean(FormPublishService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
        auth.close();
    }

    @Test
    void unpublishedDraftQueriesSortsAndPagesRealDataWithoutPublishing() {
        String formId = design.save(command("sample"));
        var source = draft(formId);
        preview.create(source, Map.of("name", "Beta"));
        String alpha = preview.create(source, Map.of("name", "Alpha"));
        preview.create(source, Map.of("name", "Other"));
        var query = new FormDataPageQuery();
        query.setPageNum(2);
        query.setPageSize(1);
        query.setSorts(List.of(new FormDataPageQuery.SortItem("name", true)));
        AstCondition condition = new AstCondition();
        condition.setField("name");
        condition.setOp("like");
        condition.setValue("a");
        query.setConditions(List.of(condition));
        var page = preview.page(source, query);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("name", "Beta"));
        preview.update(source, alpha, Map.of("name", "Changed"));
        assertThat(preview.detail(draft(formId), alpha)).containsEntry("name", "Changed");
        preview.delete(source, alpha);
        assertThat(count("LA_TEST_BUSINESS")).isEqualTo(2);
        assertThat(count("la_form_def_version")).isZero();
        assertThat(design.detail(formId).getStatus()).isEqualTo("DRAFT");
        assertThat(design.detail(formId).getVersion()).isEqualTo(source.draftVersion());
    }

    @Test
    void currentUnsavedDesignReadsAndWritesWithoutSavingMetadataAndValidatesDatabaseDefaults() {
        var command = command("unsaved");
        command.getFields().getFirst().setNullable(false);
        command.getFields().getFirst().setFormConfig("{\"rules\":{\"pattern\":\"^OK$\"}}");
        var source = new FormPreviewSource(null, null, null, command);
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ALTER COLUMN PERSON_NAME SET DEFAULT 'bad'");
        assertError(() -> preview.create(source, Map.of()), FormErrorCode.FORM_DATA_INVALID);
        assertThat(count("LA_TEST_BUSINESS")).isZero();
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ALTER COLUMN PERSON_NAME SET DEFAULT 'OK'");
        String id = preview.create(source, Map.of());
        assertThat(preview.detail(source, id)).containsEntry("name", "OK");
        assertError(() -> preview.update(source, id, Map.of("name", "invalid")), FormErrorCode.FORM_DATA_INVALID);
        assertThat(jdbc.queryForObject("SELECT PERSON_NAME FROM LA_TEST_BUSINESS WHERE ID=?", String.class, id))
                .isEqualTo("OK");
        assertThat(count("la_form_def")).isZero();
        assertThat(count("la_form_def_version")).isZero();
    }

    @Test
    void historicalAndCurrentModelsUseTheirOwnMappingOnTheSameBusinessRows() {
        String formId = design.save(command("sample"));
        publish.publish(formId);
        var history = new FormPreviewSource(formId, 1L, null, null);
        String id = preview.create(history, Map.of("name", "Version one"));
        var changed = command("sample");
        changed.setVersion(design.detail(formId).getVersion());
        changed.getFields().getFirst().setFieldName("displayName");
        var unsaved = new FormPreviewSource(formId, null, null, changed);
        assertThat(preview.detail(unsaved, id))
                .containsEntry("displayName", "Version one")
                .doesNotContainKey("name");
        preview.update(unsaved, id, Map.of("displayName", "Edited in preview"));
        assertThat(design.detail(formId).getFields().getFirst().getFieldName()).isEqualTo("name");
        design.update(formId, changed);
        assertThat(preview.detail(history, id)).containsEntry("name", "Edited in preview");
        assertThat(preview.detail(draft(formId), id)).containsEntry("displayName", "Edited in preview");
        assertThat(count("la_form_def_version")).isEqualTo(1);
    }

    @Test
    void missingOrStaleRevisionRejectsSavedDraftAndEditorOperations() {
        String id = design.save(command("sample"));
        var source = draft(id);
        var changed = command("sample");
        changed.setVersion(source.draftVersion());
        var editor = new FormPreviewSource(id, null, null, changed);
        design.update(id, changed);
        assertError(() -> preview.create(source, Map.of("name", "Stale")), FormErrorCode.FORM_DRAFT_CONFLICT);
        assertError(() -> preview.page(source, null), FormErrorCode.FORM_DRAFT_CONFLICT);
        assertError(() -> preview.create(editor, Map.of()), FormErrorCode.FORM_DRAFT_CONFLICT);
        assertError(
                () -> preview.create(new FormPreviewSource(id, null, null, null), Map.of()),
                FormErrorCode.FORM_DRAFT_CONFLICT);
        assertThat(count("LA_TEST_BUSINESS")).isZero();
    }

    @Test
    void sourceCannotSpoofIdentityOrCombineDraftWithHistory() {
        String id = design.save(command("sample"));
        assertError(
                () -> preview.preview(new FormPreviewSource(null, null, null, command("sample"))),
                FormErrorCode.FORM_KEY_DUPLICATED);
        var changed = command("different_key");
        changed.setVersion(design.detail(id).getVersion());
        assertError(
                () -> preview.create(new FormPreviewSource(id, null, null, changed), Map.of()),
                FormErrorCode.INVALID_PARAMETER);
        assertError(() -> preview.page(new FormPreviewSource(id, 1L, 0L, null), null), FormErrorCode.INVALID_PARAMETER);
        assertError(
                () -> preview.page(new FormPreviewSource(id, 1L, null, changed), null),
                FormErrorCode.INVALID_PARAMETER);
        assertError(
                () -> preview.page(new FormPreviewSource(null, null, null, null), null),
                FormErrorCode.INVALID_PARAMETER);
        assertThat(count("LA_TEST_BUSINESS")).isZero();
    }

    @Test
    void disabledFormHasNoDataPermissionsAndRejectsDataOperations() {
        String id = design.save(command("sample"));
        publish.publish(id);
        var source = new FormPreviewSource(id, 1L, null, null);
        String row = preview.create(source, Map.of("name", "Retained"));
        publish.disable(id);
        assertThat(preview.preview(source).getPermissions().values()).containsOnly(false);
        assertError(() -> preview.page(source, null), FormErrorCode.FORM_DISABLED);
        assertError(() -> preview.detail(source, row), FormErrorCode.FORM_DISABLED);
        assertError(() -> preview.create(source, Map.of()), FormErrorCode.FORM_DISABLED);
        assertError(() -> preview.update(source, row, Map.of()), FormErrorCode.FORM_DISABLED);
        assertError(() -> preview.delete(source, row), FormErrorCode.FORM_DISABLED);
        assertThat(count("LA_TEST_BUSINESS")).isEqualTo(1);
    }

    @Test
    void physicalContractAndQueryAllowListsAreEnforcedForPreviewReads() {
        var command = command("sample");
        var source = new FormPreviewSource(null, null, null, command);
        var query = new FormDataPageQuery();
        query.setSorts(List.of(new FormDataPageQuery.SortItem("PERSON_NAME", true)));
        assertThatThrownBy(() -> preview.page(source, query)).isInstanceOf(FormBusinessException.class);
        command.getFields().getFirst().setColumnName("MISSING");
        assertError(() -> preview.page(source, null), FormErrorCode.FORM_TABLE_INVALID);
        command.setTableName("la_form_def");
        assertError(() -> preview.page(source, null), FormErrorCode.FORM_TABLE_INVALID);
    }

    @Test
    void actualTenantPluginIsolatesDefinitionsVersionsAndAllBusinessOperations() {
        context.getBean(MybatisPlusExtendProperties.class).getTenant().setEnabled(true);
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ADD TENANT_ID VARCHAR(64)");
        var interceptor = context.getBean(SqlSessionFactory.class).getConfiguration().getInterceptors().stream()
                .filter(MybatisPlusInterceptor.class::isInstance)
                .map(MybatisPlusInterceptor.class::cast)
                .findFirst()
                .orElseThrow();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue(tenant);
            }
        }));
        String formA = design.save(command("sample"));
        publish.publish(formA);
        String rowA = preview.create(draft(formA), Map.of("name", "Tenant A"));
        assertThat(jdbc.queryForObject("SELECT TENANT_ID FROM LA_TEST_BUSINESS WHERE ID=?", String.class, rowA))
                .isEqualTo("tenant-a");
        tenant = "tenant-b";
        assertError(() -> preview.previewDraft(formA), FormErrorCode.FORM_NOT_FOUND);
        assertError(() -> preview.previewVersion(formA, 1), FormErrorCode.FORM_NOT_FOUND);
        String formB = design.save(command("sample"));
        var sourceB = draft(formB);
        String rowB = preview.create(sourceB, Map.of("name", "Tenant B"));
        assertThat(preview.page(sourceB, null).getRecords()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("id", rowB));
        assertThatThrownBy(() -> preview.detail(sourceB, rowA)).isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> preview.update(sourceB, rowA, Map.of("name", "Intrusion")))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> preview.delete(sourceB, rowA)).isInstanceOf(FormBusinessException.class);
        preview.delete(sourceB, rowB);
        assertThat(jdbc.queryForObject("SELECT PERSON_NAME FROM LA_TEST_BUSINESS WHERE ID=?", String.class, rowA))
                .isEqualTo("Tenant A");
        assertThat(count("LA_TEST_BUSINESS")).isEqualTo(1);
    }

    @Test
    void singleTenantModePublishesAndReadsWritesThroughPreviewAndRuntimeWithoutTenantContext() {
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ADD TENANT_ID VARCHAR(64)");
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ADD CREATED_BY VARCHAR(64)");
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ADD UPDATED_BY VARCHAR(64)");
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ADD CREATED_AT TIMESTAMP");
        jdbc.execute("ALTER TABLE LA_TEST_BUSINESS ADD UPDATED_AT TIMESTAMP");
        auth.when(AuthUtils::getSafeUsername).thenReturn("single-user");
        var command = command("sample");
        String form = design.save(command);
        publish.publish(form);
        var source = draft(form);
        String id = preview.create(source, Map.of("name", "Single tenant"));
        assertThat(jdbc.queryForObject("SELECT TENANT_ID FROM LA_TEST_BUSINESS WHERE ID=?", String.class, id))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT CREATED_BY FROM LA_TEST_BUSINESS WHERE ID=?", String.class, id))
                .isEqualTo("single-user");
        var runtime = context.getBean(DynamicDataService.class);
        var model = context.getBean(FormModelProvider.class).require("sample");
        assertThat(runtime.getById(model, id)).containsEntry("name", "Single tenant");
        runtime.update(model, id, Map.of("name", "Updated"));
        assertThat(preview.detail(source, id)).containsEntry("name", "Updated");
        assertThat(preview.page(source, null).getTotal()).isEqualTo(1);
        preview.delete(source, id);
        assertThat(runtime.page(model, new FormDataPageQuery()).getTotal()).isZero();
    }

    private FormPreviewSource draft(String id) {
        return new FormPreviewSource(id, null, preview.previewDraft(id).getDraftVersion(), null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertError(Runnable action, FormErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error ->
                        assertThat(((FormBusinessException) error).getCode()).isEqualTo(code.getCode()));
    }

    static FormDefSaveCommand command(String key) {
        var command = new FormDefSaveCommand();
        command.setFormKey(key);
        command.setFormName("Preview");
        command.setFormType("SINGLE");
        command.setTableName("LA_TEST_BUSINESS");
        var field = new FormFieldItem();
        field.setFieldName("name");
        field.setColumnName("PERSON_NAME");
        field.setFieldType("STRING");
        field.setQueryConfig("{\"show\":true,\"op\":\"like\"}");
        command.setFields(List.of(field));
        return command;
    }
}
