package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.FormProperties;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.model.AstCondition;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedCondition;
import com.lambda.fusion.form.runtime.support.QueryAstValidator.ResolvedSort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 查询 AST 三层白名单校验测试（字段越权 / 算子越权 / 排序越权 / 条件超限）
 *
 * <p>本类是契约 §16 安全红线的守门测试：客户端只能提交字段标识与算子，
 * 物理列名一律由服务端模型解析。
 *
 * @author Zx
 */
class QueryAstValidatorTest {

    private FormProperties properties;

    private QueryAstValidator validator;

    @BeforeEach
    void setUp() {
        properties = new FormProperties();
        validator = new QueryAstValidator(properties);
    }

    @Test
    void rejectsConditionOnFieldOutsideQueryWhitelist() {
        FormDataPageQuery query = query(condition("remark", "like", "报销"));

        assertErrorCode(() -> validator.validate(model(), query), FormErrorCode.FORM_QUERY_FIELD_FORBIDDEN);
    }

    @Test
    void rejectsConditionOnUnknownField() {
        FormDataPageQuery query = query(condition("TENANT_ID", "eq", "t1"));

        assertErrorCode(() -> validator.validate(model(), query), FormErrorCode.FORM_QUERY_FIELD_FORBIDDEN);
    }

    @Test
    void rejectsOperatorOutsideWhitelist() {
        FormDataPageQuery query = query(condition("applicant", "regexp", ".*"));

        assertErrorCode(() -> validator.validate(model(), query), FormErrorCode.FORM_QUERY_OPERATOR_UNSUPPORTED);
    }

    @Test
    void rejectsBlankOperator() {
        FormDataPageQuery query = query(condition("applicant", null, "张"));

        assertErrorCode(() -> validator.validate(model(), query), FormErrorCode.FORM_QUERY_OPERATOR_UNSUPPORTED);
    }

    @Test
    void rejectsConditionCountOverLimit() {
        properties.setMaxConditionCount(2);
        FormDataPageQuery query =
                query(condition("applicant", "eq", "张"), condition("days", "eq", 1), condition("applicant", "eq", "李"));

        assertErrorCode(() -> validator.validate(model(), query), FormErrorCode.FORM_QUERY_CONDITION_LIMIT);
    }

    @Test
    void rejectsSortOnFieldOutsideListShow() {
        FormDataPageQuery query = new FormDataPageQuery();
        query.setSorts(List.of(new FormDataPageQuery.SortItem("days", false)));

        assertErrorCode(() -> validator.validate(model(), query), FormErrorCode.FORM_QUERY_FIELD_FORBIDDEN);
    }

    @Test
    void acceptsQueryableConditionsAndSortableFields() {
        FormDataPageQuery query =
                query(condition("applicant", "like", "张"), condition("days", "between", List.of(1, 3)));
        query.setSorts(List.of(new FormDataPageQuery.SortItem("applicant", true)));

        assertThatCode(() -> validator.validate(model(), query)).doesNotThrowAnyException();
    }

    @Test
    void treatsMissingConditionsAndSortsAsEmpty() {
        FormDataPageQuery query = new FormDataPageQuery();
        query.setConditions(null);
        query.setSorts(null);

        assertThatCode(() -> validator.validate(model(), query)).doesNotThrowAnyException();
        assertThat(validator.resolve(model(), null)).isEmpty();
        assertThat(validator.resolveSorts(model(), null)).isEmpty();
    }

    @Test
    void resolvesConditionToPhysicalColumnKeepingOperatorAndValue() {
        List<ResolvedCondition> resolved =
                validator.resolve(model(), List.of(condition("applicant", "like", "张"), condition("days", "ge", 2)));

        assertThat(resolved)
                .containsExactly(
                        new ResolvedCondition("APPLICANT", "like", "张"), new ResolvedCondition("DAYS", "ge", 2));
    }

    @Test
    void resolvesSortToPhysicalColumnAndDirection() {
        List<ResolvedSort> resolved = validator.resolveSorts(
                model(),
                List.of(
                        new FormDataPageQuery.SortItem("applicant", false),
                        new FormDataPageQuery.SortItem("days", true)));

        assertThat(resolved).containsExactly(new ResolvedSort("APPLICANT", false), new ResolvedSort("DAYS", true));
    }

    private static void assertErrorCode(ThrowingCallable callable, FormErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOf(FormBusinessException.class).satisfies(e -> assertThat(
                        ((FormBusinessException) e).getCode())
                .isEqualTo(expected.getCode()));
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

    /**
     * 测试模型：applicant 可查可排；days 可查不可排；remark 可排不可查
     */
    private static FormModel model() {
        return new FormModel(
                "leave_demo",
                "请假演示",
                3L,
                FormConstants.FORM_TYPE_SINGLE,
                "la_demo_leave",
                null,
                new FormModel.ListConfig(List.of(), true, List.of()),
                FormModel.DetailConfig.DEFAULT,
                null,
                List.of(
                        field("applicant", "APPLICANT", "STRING", true, true, "like", 1),
                        field("days", "DAYS", "DECIMAL", false, true, "eq", 2),
                        field("remark", "REMARK", "TEXT", true, false, "like", 3)),
                List.of(),
                null,
                null);
    }

    private static FormModel.FormField field(
            String name, String column, String type, boolean listShow, boolean queryShow, String queryOp, int sort) {
        return new FormModel.FormField(
                name,
                name,
                column,
                type,
                true,
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
