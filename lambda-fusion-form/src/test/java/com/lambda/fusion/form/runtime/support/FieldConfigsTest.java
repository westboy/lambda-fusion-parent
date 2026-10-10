package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.form.exception.FormBusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 字段三段配置 JSON 文本解析校验测试（草稿保存期第一道）
 *
 * @author Zx
 */
class FieldConfigsTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void blankTextsFallBackToDefaults() {
        FormDefSnapshot.QueryConf query = FieldConfigs.toQueryConf(objectMapper, null);
        FormDefSnapshot.ListConf list = FieldConfigs.toListConf(objectMapper, " ");
        FormDefSnapshot.FormConf form = FieldConfigs.toFormConf(objectMapper, null);

        assertThat(query.getShow()).isFalse();
        assertThat(list.getShow()).isTrue();
        assertThat(form.getShow()).isTrue();
        assertThat(form.getReadonly()).isFalse();
    }

    @Test
    void parsesFullConfigs() {
        FormDefSnapshot.QueryConf query =
                FieldConfigs.toQueryConf(objectMapper, "{\"show\":true,\"op\":\"between\",\"defaultValue\":\"1\"}");
        FormDefSnapshot.ListConf list = FieldConfigs.toListConf(
                objectMapper, "{\"show\":true,\"width\":160,\"align\":\"center\",\"format\":\"dict\"}");
        FormDefSnapshot.FormConf form = FieldConfigs.toFormConf(
                objectMapper,
                "{\"show\":true,\"component\":\"InputNumber\",\"props\":{\"min\":0},\"rules\":{\"required\":true},\"readonly\":true}");

        assertThat(query.getShow()).isTrue();
        assertThat(query.getOp()).isEqualTo("between");
        assertThat(query.getDefaultValue()).isEqualTo("1");
        assertThat(list.getWidth()).isEqualTo(160);
        assertThat(list.getAlign()).isEqualTo("center");
        assertThat(list.getFormat()).isEqualTo("dict");
        assertThat(form.getComponent()).isEqualTo("InputNumber");
        assertThat(form.getProps()).containsEntry("min", 0);
        assertThat(form.getRules()).containsEntry("required", true);
        assertThat(form.getReadonly()).isTrue();
    }

    @Test
    void acceptsScalarQueryDefaultValue() {
        FormDefSnapshot.QueryConf text = FieldConfigs.toQueryConf(objectMapper, "{\"defaultValue\":\"abc\"}");
        FormDefSnapshot.QueryConf number = FieldConfigs.toQueryConf(objectMapper, "{\"defaultValue\":120}");

        assertThat(text.getDefaultValue()).isEqualTo("abc");
        assertThat(number.getDefaultValue()).isEqualTo("120");
    }

    @Test
    void rejectsNonObjectJson() {
        assertInvalid(() -> FieldConfigs.toQueryConf(objectMapper, "[1,2]"));
        assertInvalid(() -> FieldConfigs.toListConf(objectMapper, "\"x\""));
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "not-json"));
    }

    @Test
    void rejectsUnknownKeys() {
        assertInvalid(() -> FieldConfigs.toQueryConf(objectMapper, "{\"visible\":true}"));
        assertInvalid(() -> FieldConfigs.toListConf(objectMapper, "{\"sort\":1}"));
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "{\"disabled\":true}"));
    }

    @Test
    void rejectsWidthOutOfRangeAndFraction() {
        assertInvalid(() -> FieldConfigs.toListConf(objectMapper, "{\"width\":0}"));
        assertInvalid(() -> FieldConfigs.toListConf(objectMapper, "{\"width\":501}"));
        assertInvalid(() -> FieldConfigs.toListConf(objectMapper, "{\"width\":120.5}"));
    }

    @Test
    void rejectsIllegalAlignAndFormat() {
        assertInvalid(() -> FieldConfigs.toListConf(objectMapper, "{\"align\":\"middle\"}"));
        assertInvalid(() -> FieldConfigs.toListConf(objectMapper, "{\"format\":\"currency\"}"));
    }

    @Test
    void rejectsNonIdentifierComponent() {
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "{\"component\":\"Input x\"}"));
    }

    @Test
    void rejectsNonBooleanFlags() {
        assertInvalid(() -> FieldConfigs.toQueryConf(objectMapper, "{\"show\":\"yes\"}"));
        assertInvalid(() -> FieldConfigs.toListConf(objectMapper, "{\"show\":1}"));
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "{\"readonly\":\"true\"}"));
    }

    @Test
    void parsesNodePerms() {
        FormDefSnapshot.FormConf form = FieldConfigs.toFormConf(
                objectMapper, "{\"nodePerms\":{\"node_b\":{\"editable\":true},\"node_c\":{\"show\":false}}}");

        assertThat(form.getNodePerms()).hasSize(2);
        assertThat(form.getNodePerms().get("node_b").getEditable()).isTrue();
        assertThat(form.getNodePerms().get("node_b").getShow()).isNull();
        assertThat(form.getNodePerms().get("node_c").getEditable()).isNull();
        assertThat(form.getNodePerms().get("node_c").getShow()).isFalse();
    }

    @Test
    void rejectsIllegalNodePerms() {
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "{\"nodePerms\":[]}"));
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "{\"nodePerms\":{}}"));
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "{\"nodePerms\":{\" \":{\"editable\":true}}}"));
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "{\"nodePerms\":{\"node_b\":\"yes\"}}"));
        assertInvalid(() -> FieldConfigs.toFormConf(objectMapper, "{\"nodePerms\":{\"node_b\":{\"write\":true}}}"));
        assertInvalid(
                () -> FieldConfigs.toFormConf(objectMapper, "{\"nodePerms\":{\"node_b\":{\"editable\":\"yes\"}}}"));
    }

    private static void assertInvalid(ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(FormBusinessException.class);
    }
}
