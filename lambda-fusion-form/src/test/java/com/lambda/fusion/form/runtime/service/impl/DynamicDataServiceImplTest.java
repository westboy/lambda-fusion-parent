package com.lambda.fusion.form.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
                                "DECIMAL".equals(field.type()) ? "DECIMAL" : "VARCHAR",
                                field.type(),
                                false,
                                field.nullable(),
                                128,
                                "DECIMAL".equals(field.type()) ? Types.DECIMAL : Types.VARCHAR,
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
                new FormModel.FormConf(true, "Input", Map.of(), Map.of(), false));
    }
}
