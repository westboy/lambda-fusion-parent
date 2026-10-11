package com.lambda.fusion.form.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.biz.BizDataProvider;
import com.lambda.fusion.form.runtime.integration.FormDataInterceptors;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.model.AstCondition;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormDataPageView;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.form.runtime.support.QueryAstValidator;
import com.lambda.fusion.form.runtime.support.TargetTableContract;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 动态数据读写引擎测试
 *
 * <p>重点验证契约 §16 红线：列名取自服务端模型、值全部参数化绑定、
 * 租户条件不手写（交由框架租户插件在解析层追加）。
 *
 * @author Zx
 */
@SuppressWarnings("unchecked")
class DynamicDataServiceImplTest {

    private static final String TABLE = "la_demo_leave";

    private DynamicTableMapper mapper;

    private FormModelProvider modelProvider;

    private DynamicDataServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(DynamicTableMapper.class);
        modelProvider = mock(FormModelProvider.class);
        TargetTableContract contract = mock(TargetTableContract.class);
        when(contract.validate(any())).thenAnswer(invocation -> columnsFor((FormModel) invocation.getArgument(0)));
        when(contract.validateAccess(any()))
                .thenAnswer(invocation -> columnsFor((FormModel) invocation.getArgument(0)));
        FormProperties formProperties = new FormProperties();
        // 默认排除正则保护 la_ 前缀：测试子表 LA_TEST_SUB 需显式放行（demo_/test_ 例外）
        formProperties.setTableExcludePattern("la_(?!demo_|test_).*|ai_.*|flow_.*|databasechangelog.*|qrtz_.*");
        service = new DynamicDataServiceImpl(
                mapper,
                new QueryAstValidator(new FormProperties()),
                modelProvider,
                contract,
                formProperties,
                new FormDataInterceptors(new GenericApplicationContext()),
                new ObjectMapper(),
                new StaticListableBeanFactory().getBeanProvider(BizDataProvider.class));
        when(mapper.updateDynamic(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(1);
        when(mapper.deleteById(anyString(), anyString(), anyMap())).thenReturn(1);
    }

    @Test
    void pageBindsConditionValuesAsParametersAndLeavesTenantToInterceptor() {
        when(mapper.countBy(eq(TABLE), anyString(), anyMap())).thenReturn(1L);
        when(mapper.selectList(eq(TABLE), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(List.of(Map.of("ID", "1")));

        FormDataPageView view = service.page(model(), query(condition("applicant", "like", "张")));

        ArgumentCaptor<String> whereSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(mapper).selectList(eq(TABLE), anyString(), whereSql.capture(), anyString(), params.capture());
        assertThat(whereSql.getValue()).isEqualTo(" AND APPLICANT LIKE #{params.p0} ESCAPE '\\'");
        assertThat(whereSql.getValue()).doesNotContain("张").doesNotContain(FormConstants.COLUMN_TENANT_ID);
        assertThat(params.getValue()).containsValue("%张%");
        assertThat(view.getTotal()).isEqualTo(1L);
        assertThat(view.getRecords()).hasSize(1);
    }

    @Test
    void pageDefaultsPageSizeAndOrderWhenNoConditions() {
        when(mapper.countBy(eq(TABLE), eq(""), anyMap())).thenReturn(1L);
        when(mapper.selectList(eq(TABLE), anyString(), eq(""), anyString(), anyMap()))
                .thenReturn(List.of());

        service.page(model(), new FormDataPageQuery());

        verify(mapper)
                .selectList(
                        eq(TABLE),
                        eq("ID, APPLICANT, DAYS, REMARK"),
                        eq(""),
                        eq(" ORDER BY CREATED_AT DESC, ID DESC LIMIT 20 OFFSET 0"),
                        anyMap());
    }

    @Test
    void pageClampsPageSizeToUpperBound() {
        when(mapper.countBy(eq(TABLE), eq(""), anyMap())).thenReturn(0L);
        FormDataPageQuery query = new FormDataPageQuery();
        query.setPageSize(5000);

        FormDataPageView view = service.page(model(), query);

        assertThat(view.getPageSize()).isEqualTo(500L);
        assertThat(view.getTotal()).isZero();
        verify(mapper, never()).selectList(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void pageRejectsInOperatorWithScalarValue() {
        assertErrorCode(
                () -> service.page(model(), query(condition("applicant", "in", "张三"))),
                FormErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(mapper);
    }

    @Test
    void pageRejectsBetweenOperatorWithWrongArity() {
        assertErrorCode(
                () -> service.page(model(), query(condition("days", "between", List.of(1)))),
                FormErrorCode.INVALID_PARAMETER);
        verifyNoInteractions(mapper);
    }

    @Test
    void getByIdThrowsWhenRowMissing() {
        when(mapper.selectById(eq(TABLE), anyString(), anyString(), anyMap())).thenReturn(null);

        assertErrorCode(() -> service.getById(model(), "404"), FormErrorCode.FORM_DATA_NOT_FOUND);
    }

    @Test
    void pageAppendsDataScopeMarkerWhenEnabled() {
        when(mapper.countBy(eq(TABLE), anyString(), anyMap())).thenReturn(0L);

        service.page(scopedModel(), new FormDataPageQuery());

        ArgumentCaptor<String> whereSql = ArgumentCaptor.forClass(String.class);
        verify(mapper).countBy(eq(TABLE), whereSql.capture(), anyMap());
        assertThat(whereSql.getValue()).contains("ORG_ID IN ('permissions|0')");
    }

    @Test
    void pageLeavesMarkerOutWhenScopeDisabled() {
        when(mapper.countBy(eq(TABLE), anyString(), anyMap())).thenReturn(0L);

        service.page(model(), new FormDataPageQuery());

        verify(mapper).countBy(eq(TABLE), eq(""), anyMap());
    }

    @Test
    void createBackfillsScopeColumnWithCurrentUserOrg() {
        UserDetails user = new UserDetails();
        user.setOrgId("org-1");
        try (MockedStatic<AuthUtils> auth = mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            auth.when(AuthUtils::getSafeUsername).thenReturn("creator");

            service.create(scopedModel(), new HashMap<>(Map.of("applicant", "张三")));
        }

        ArgumentCaptor<List<String>> columns = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertDynamic(eq(TABLE), columns.capture(), any(), params.capture());
        assertThat(columns.getValue()).contains("ORG_ID");
        assertThat(params.getValue()).containsValue("org-1");
    }

    @Test
    void createRejectsScopeBackfillWithoutOrg() {
        try (MockedStatic<AuthUtils> auth = mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(new UserDetails());
            auth.when(AuthUtils::getSafeUsername).thenReturn("creator");

            assertErrorCode(
                    () -> service.create(scopedModel(), new HashMap<>(Map.of("applicant", "张三"))),
                    FormErrorCode.FORM_DATA_INVALID);
        }
    }

    @Test
    void importCsvCreatesRowsAndConvertsBoolLabels() throws Exception {
        when(mapper.insertDynamic(anyString(), anyList(), anyList(), anyMap())).thenReturn(1);
        FormModel boolModel = boolModel();

        int count = service.importCsv(boolModel, new java.io.StringReader("applicant,remote\n张三,是\n李四,否\n"));

        assertThat(count).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> columns = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(mapper, times(2)).insertDynamic(eq(TABLE), columns.capture(), anyList(), params.capture());
        assertThat(columns.getAllValues().get(0)).contains("REMOTE");
        assertThat(params.getAllValues().get(0)).containsValue(1L);
        assertThat(params.getAllValues().get(1)).containsValue(0L);
    }

    @Test
    void importCsvRejectsUnknownHeaderAndWrapsRowErrors() {
        assertThatThrownBy(() -> service.importCsv(model(), new java.io.StringReader("nope\nx\n")))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> {
                    assertThat(((FormBusinessException) error).getCode())
                            .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode());
                    assertThat(((FormBusinessException) error).getArgs()[0].toString())
                            .contains("未知列：nope");
                });

        // 第二数据行（文件第 3 行）必填校验失败：错误携带文件行号
        assertThatThrownBy(() -> service.importCsv(model(), new java.io.StringReader("applicant,days\n张三,1\n,5\n")))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> {
                    assertThat(((FormBusinessException) error).getCode())
                            .isEqualTo(FormErrorCode.FORM_ROW_INVALID.getCode());
                    assertThat(((FormBusinessException) error).getArgs()).contains("3");
                    assertThat(((FormBusinessException) error).getArgs()[1].toString())
                            .contains("applicant 不能为空");
                });
    }

    @Test
    void importCsvRejectsAmbiguousLabelAndLimitOverflow() {
        // 两个字段同标签：表头用标签即歧义拒绝
        assertThatThrownBy(() -> service.importCsv(model(), new java.io.StringReader("标签,\n1,2\n")))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> {
                    assertThat(((FormBusinessException) error).getCode())
                            .isEqualTo(FormErrorCode.INVALID_PARAMETER.getCode());
                    assertThat(((FormBusinessException) error).getArgs()[0].toString())
                            .contains("标签");
                });

        // 行数超上限：独立装配一个小上限服务
        TargetTableContract limitedContract = mock(TargetTableContract.class);
        when(limitedContract.validate(any()))
                .thenAnswer(invocation -> columnsFor((FormModel) invocation.getArgument(0)));
        FormProperties properties = new FormProperties();
        properties.setMaxImportRows(2);
        DynamicDataServiceImpl limited = new DynamicDataServiceImpl(
                mapper,
                new QueryAstValidator(new FormProperties()),
                modelProvider,
                limitedContract,
                properties,
                new FormDataInterceptors(new GenericApplicationContext()),
                new ObjectMapper(),
                new StaticListableBeanFactory().getBeanProvider(BizDataProvider.class));
        StringBuilder csv = new StringBuilder("applicant\n");
        for (int i = 0; i < 3; i++) {
            csv.append('u').append(i).append('\n');
        }
        assertThatThrownBy(() -> limited.importCsv(model(), new java.io.StringReader(csv.toString())))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_IMPORT_LIMIT.getCode()));
    }

    @Test
    void createKeepsMappedFieldsOnlyAndFillsAudit() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("applicant", "张三");
        payload.put("id", "forged-id");
        payload.put("unknownColumn", "x");

        String id = service.create(model(), payload);

        ArgumentCaptor<List<String>> columns = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> values = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertDynamic(eq(TABLE), columns.capture(), values.capture(), params.capture());
        assertThat(columns.getValue())
                .containsExactly("ID", "APPLICANT", "CREATED_BY", "CREATED_AT", "UPDATED_BY", "UPDATED_AT");
        assertThat(values.getValue()).allSatisfy(value -> assertThat(value).startsWith("#{params."));
        assertThat(params.getValue()).containsValue("张三").containsValue(id);
        assertThat(params.getValue()).doesNotContainValue("forged-id").doesNotContainValue("x");
    }

    @Test
    void createRejectsBlankRequiredField() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("applicant", "  ");

        assertErrorCode(() -> service.create(model(), payload), FormErrorCode.FORM_DATA_INVALID);
        verifyNoInteractions(mapper);
    }

    @Test
    void updateBindsAssignmentsWithAudit() {
        when(mapper.selectById(eq(TABLE), anyString(), anyString(), anyMap())).thenReturn(Map.of("ID", "1"));

        service.update(model(), "1", Map.of("applicant", "张三", "days", 3));

        ArgumentCaptor<String> setSql = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateDynamic(eq(TABLE), setSql.capture(), anyString(), anyMap());
        assertThat(setSql.getValue())
                .isEqualTo(
                        "APPLICANT = #{params.p1}, DAYS = #{params.p2}, UPDATED_BY = #{params.p3}, UPDATED_AT = #{params.p4}");
    }

    @Test
    void updateLeavesUnsubmittedRequiredFieldUnchanged() {
        when(mapper.selectById(eq(TABLE), anyString(), anyString(), anyMap())).thenReturn(Map.of("ID", "1"));

        service.update(model(), "1", Map.of("days", 3));
        ArgumentCaptor<String> assignments = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateDynamic(eq(TABLE), assignments.capture(), anyString(), anyMap());
        assertThat(assignments.getValue()).doesNotContain("APPLICANT");
    }

    @Test
    void deleteRejectsWhenChildRowsExist() {
        when(mapper.selectById(eq(TABLE), anyString(), anyString(), anyMap())).thenReturn(Map.of("ID", "1"));
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        when(mapper.countBy(eq("LA_TEST_SUB"), eq(" AND LEAVE_ID = #{params.value}"), anyMap()))
                .thenReturn(2L);

        assertErrorCode(
                () -> service.delete(masterSubModel("FORBID_DELETE"), "1"), FormErrorCode.FORM_SUB_DELETE_FORBIDDEN);
        verify(mapper, never()).deleteById(anyString(), anyString(), anyMap());
    }

    @Test
    void deleteCascadesChildrenWhenStrategyAllows() {
        when(mapper.selectById(eq(TABLE), anyString(), anyString(), anyMap())).thenReturn(Map.of("ID", "1"));
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        when(mapper.countBy(eq("LA_TEST_SUB"), eq(" AND LEAVE_ID = #{params.value}"), anyMap()))
                .thenReturn(2L);

        service.delete(masterSubModel("CASCADE_DELETE"), "1");

        verify(mapper).deleteByFk(eq("LA_TEST_SUB"), eq("LEAVE_ID"), anyString(), anyMap());
        verify(mapper).deleteById(eq(TABLE), anyString(), anyMap());
    }

    @Test
    void deleteRemovesRowWhenNoChildRows() {
        when(mapper.selectById(eq(TABLE), anyString(), anyString(), anyMap())).thenReturn(Map.of("ID", "1"));
        when(modelProvider.require("leave_item")).thenReturn(subModel());
        when(mapper.countBy(eq("LA_TEST_SUB"), eq(" AND LEAVE_ID = #{params.value}"), anyMap()))
                .thenReturn(0L);

        service.delete(masterSubModel("CASCADE_DELETE"), "1");

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(mapper).deleteById(eq(TABLE), anyString(), params.capture());
        assertThat(params.getValue()).containsEntry("id", "1");
    }

    private static void assertErrorCode(ThrowingCallable callable, FormErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOf(FormBusinessException.class).satisfies(e -> assertThat(
                        ((FormBusinessException) e).getCode())
                .isEqualTo(expected.getCode()));
    }

    /** 模拟物理元数据：映射列 + 审计四列（引擎按物理存在自动填充） */
    private static Map<String, TableColumnView> columnsFor(FormModel model) {
        Map<String, TableColumnView> columns = model.fields().stream()
                .collect(Collectors.toMap(
                        FormModel.FormField::column,
                        field -> new TableColumnView(
                                field.column(),
                                null,
                                switch (field.type()) {
                                    case "DECIMAL" -> "DECIMAL";
                                    case "BOOL" -> "TINYINT";
                                    default -> "VARCHAR";
                                },
                                field.type(),
                                false,
                                field.nullable(),
                                128,
                                switch (field.type()) {
                                    case "DECIMAL" -> Types.DECIMAL;
                                    case "BOOL" -> Types.TINYINT;
                                    default -> Types.VARCHAR;
                                },
                                2,
                                null,
                                false,
                                false)));
        for (String audit : FormConstants.AUDIT_COLUMNS) {
            columns.put(
                    audit,
                    new TableColumnView(
                            audit,
                            null,
                            audit.endsWith("_AT") ? "DATETIME" : "VARCHAR",
                            audit.endsWith("_AT") ? "DATETIME" : "STRING",
                            false,
                            true,
                            64,
                            audit.endsWith("_AT") ? Types.TIMESTAMP : Types.VARCHAR,
                            0,
                            null,
                            false,
                            false));
        }
        return columns;
    }

    private static FormDataPageQuery query(AstCondition... conditions) {
        FormDataPageQuery query = new FormDataPageQuery();
        query.setConditions(new ArrayList<>(List.of(conditions)));
        return query;
    }

    private static AstCondition condition(String field, String op, Object value) {
        AstCondition condition = new AstCondition();
        condition.setField(field);
        condition.setOp(op);
        condition.setValue(value);
        return condition;
    }

    private static FormModel model() {
        return new FormModel(
                "leave_demo",
                "请假演示",
                1L,
                FormConstants.FORM_TYPE_SINGLE,
                TABLE,
                null,
                null,
                new FormModel.ListConfig(List.of(), true, List.of()),
                FormModel.DetailConfig.DEFAULT,
                null,
                List.of(
                        field("applicant", "APPLICANT", "STRING", false, true, true, "like", 1),
                        field("days", "DAYS", "DECIMAL", true, true, true, "eq", 2),
                        field("remark", "REMARK", "TEXT", true, true, false, "like", 3)),
                List.of(),
                null,
                null,
                null,
                null);
    }

    /** 启用行级数据权限的模型：ORG_ID 承载组织节点 id，type=0（组织架构） */
    private static FormModel scopedModel() {
        return new FormModel(
                "leave_demo",
                "请假演示",
                1L,
                FormConstants.FORM_TYPE_SINGLE,
                TABLE,
                null,
                null,
                new FormModel.ListConfig(List.of(), true, List.of()),
                FormModel.DetailConfig.DEFAULT,
                null,
                List.of(
                        field("applicant", "APPLICANT", "STRING", false, true, true, "like", 1),
                        field("orgId", "ORG_ID", "STRING", true, true, false, "eq", 2)),
                List.of(),
                null,
                null,
                0,
                "ORG_ID");
    }

    /** 含 BOOL 字段的模型：导入的 是/否 标签转换用 */
    private static FormModel boolModel() {
        return new FormModel(
                "leave_demo",
                "请假演示",
                1L,
                FormConstants.FORM_TYPE_SINGLE,
                TABLE,
                null,
                null,
                new FormModel.ListConfig(List.of(), true, List.of()),
                FormModel.DetailConfig.DEFAULT,
                null,
                List.of(
                        field("applicant", "APPLICANT", "STRING", false, true, true, "like", 1),
                        field("remote", "REMOTE", "BOOL", true, true, false, "eq", 2)),
                List.of(),
                null,
                null,
                null,
                null);
    }

    private static FormModel masterSubModel(String cascadeStrategy) {
        return new FormModel(
                "leave_demo",
                "请假演示",
                1L,
                FormConstants.FORM_TYPE_MASTER_SUB,
                TABLE,
                null,
                null,
                new FormModel.ListConfig(List.of(), true, List.of()),
                FormModel.DetailConfig.DEFAULT,
                null,
                model().fields(),
                List.of(new FormModel.FormRelation(
                        "leave_item", "LA_TEST_SUB", "LEAVE_ID", cascadeStrategy, null, null)),
                null,
                null,
                null,
                null);
    }

    private static FormModel subModel() {
        return new FormModel(
                "leave_item",
                "请假明细",
                1L,
                FormConstants.FORM_TYPE_SINGLE,
                "LA_TEST_SUB",
                null,
                null,
                new FormModel.ListConfig(List.of(), true, List.of()),
                FormModel.DetailConfig.DEFAULT,
                null,
                List.of(field("leaveId", "LEAVE_ID", "STRING", false, false, false, "eq", 0)),
                List.of(),
                null,
                null,
                null,
                null);
    }

    private static FormModel.FormField field(
            String name,
            String column,
            String type,
            boolean nullable,
            boolean listShow,
            boolean queryShow,
            String queryOp,
            int sort) {
        return new FormModel.FormField(
                name,
                name,
                column,
                type,
                nullable,
                null,
                null,
                sort,
                true,
                null,
                new FormModel.QueryConf(queryShow, queryOp, null),
                new FormModel.ListConf(listShow, null, null, null, null, false, null, 0),
                new FormModel.FormConf(true, "Input", Map.of(), Map.of(), false, null),
                null,
                null);
    }
}
