package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.form.exception.FormBusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 子表列表呈现配置 JSON 文本解析校验测试
 *
 * @author Zx
 */
class SubTableConfigsTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void blankTextMeansUnconfigured() {
        assertThat(SubTableConfigs.columnFields(objectMapper, null)).isEmpty();
        assertThat(SubTableConfigs.columnFields(objectMapper, "  ")).isEmpty();
    }

    @Test
    void parsesColumnsInOrder() {
        assertThat(SubTableConfigs.columnFields(
                        objectMapper, "{\"columns\":[{\"field\":\"personName\",\"width\":120},{\"field\":\"days\"}]}"))
                .containsExactly("personName", "days");
    }

    @Test
    void columnsAbsentMeansUnconfigured() {
        assertThat(SubTableConfigs.columnFields(objectMapper, "{}")).isEmpty();
        assertThat(SubTableConfigs.columnFields(objectMapper, "{\"columns\":null}"))
                .isEmpty();
    }

    @Test
    void rejectsInvalidJsonAndShapes() {
        assertDetail(() -> SubTableConfigs.columnFields(objectMapper, "{"), "不是合法 JSON");
        assertDetail(() -> SubTableConfigs.columnFields(objectMapper, "[]"), "必须是 JSON 对象");
        assertDetail(() -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[]}"), "必须是非空数组");
        assertDetail(() -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":\"name\"}"), "必须是非空数组");
    }

    @Test
    void rejectsUnknownKeys() {
        assertDetail(() -> SubTableConfigs.columnFields(objectMapper, "{\"order\":1}"), "subTableConfig 存在非法键：order");
        assertDetail(
                () -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[{\"field\":\"name\",\"span\":2}]}"),
                "subTableConfig.columns 存在非法键：span");
        assertDetail(() -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[\"name\"]}"), "项必须是对象");
    }

    @Test
    void acceptsOverrideKeysAndRejectsBadTypes() {
        // 场景覆盖键（label/required/readonly/defaultValue）合法放行
        assertThat(
                        SubTableConfigs.columnFields(
                                objectMapper,
                                "{\"columns\":[{\"field\":\"reason\",\"label\":\"请假事由\",\"required\":true,\"readonly\":false,\"defaultValue\":\"年假\",\"width\":200}]}"))
                .containsExactly("reason");
        // 类型非法拦截
        assertDetail(
                () -> SubTableConfigs.columnFields(
                        objectMapper, "{\"columns\":[{\"field\":\"reason\",\"required\":\"yes\"}]}"),
                "columns.required 必须是布尔值");
        assertDetail(
                () -> SubTableConfigs.columnFields(
                        objectMapper, "{\"columns\":[{\"field\":\"reason\",\"readonly\":1}]}"),
                "columns.readonly 必须是布尔值");
        assertDetail(
                () -> SubTableConfigs.columnFields(
                        objectMapper, "{\"columns\":[{\"field\":\"reason\",\"label\":123}]}"),
                "columns.label 必须是不超过 128 字符的文本");
    }

    @Test
    void rejectsInvalidFieldAndDuplicate() {
        assertDetail(() -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[{\"width\":10}]}"), "field 非法");
        assertDetail(
                () -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[{\"field\":\"1bad\"}]}"), "field 非法");
        assertDetail(() -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[{\"field\":3}]}"), "field 必须是字符串");
        assertDetail(
                () -> SubTableConfigs.columnFields(
                        objectMapper, "{\"columns\":[{\"field\":\"name\"},{\"field\":\"name\"}]}"),
                "字段重复：name");
    }

    @Test
    void rejectsWidthOutOfRangeOrNonInteger() {
        assertDetail(
                () -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[{\"field\":\"name\",\"width\":0}]}"),
                "width 必须是");
        assertDetail(
                () -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[{\"field\":\"name\",\"width\":501}]}"),
                "width 必须是");
        assertDetail(
                () -> SubTableConfigs.columnFields(objectMapper, "{\"columns\":[{\"field\":\"name\",\"width\":1.5}]}"),
                "width 必须是");
    }

    @Test
    void parsesRowButtonsInOrder() {
        assertThat(
                        SubTableConfigs.rowButtons(
                                objectMapper,
                                "{\"rowButtons\":[{\"action\":\"edit\",\"label\":\"修改\"},{\"action\":\"delete\",\"show\":false}]}"))
                .containsExactly("edit", "delete");
        // 与 columns 共存、缺省未配置均为空
        assertThat(SubTableConfigs.rowButtons(
                        objectMapper, "{\"columns\":[{\"field\":\"name\"}],\"rowButtons\":[{\"action\":\"add\"}]}"))
                .containsExactly("add");
        assertThat(SubTableConfigs.rowButtons(objectMapper, "{}")).isEmpty();
        assertThat(SubTableConfigs.rowButtons(objectMapper, "{\"rowButtons\":null}"))
                .isEmpty();
    }

    @Test
    void rejectsInvalidRowButtons() {
        assertDetail(() -> SubTableConfigs.rowButtons(objectMapper, "{\"rowButtons\":[]}"), "必须是非空数组");
        assertDetail(() -> SubTableConfigs.rowButtons(objectMapper, "{\"rowButtons\":[\"edit\"]}"), "项必须是对象");
        assertDetail(
                () -> SubTableConfigs.rowButtons(objectMapper, "{\"rowButtons\":[{\"action\":\"detail\"}]}"),
                "action 非法：detail");
        assertDetail(
                () -> SubTableConfigs.rowButtons(
                        objectMapper, "{\"rowButtons\":[{\"action\":\"edit\"},{\"action\":\"edit\"}]}"),
                "动作重复：edit");
        assertDetail(
                () -> SubTableConfigs.rowButtons(objectMapper, "{\"rowButtons\":[{\"action\":\"edit\",\"title\":1}]}"),
                "存在非法键：title");
        assertDetail(
                () -> SubTableConfigs.rowButtons(
                        objectMapper, "{\"rowButtons\":[{\"action\":\"edit\",\"label\":\"" + "x".repeat(129) + "\"}]}"),
                "label");
        assertDetail(
                () -> SubTableConfigs.rowButtons(objectMapper, "{\"rowButtons\":[{\"action\":\"edit\",\"show\":1}]}"),
                "show");
    }

    /** 业务异常的中文明细存于 args 而非 message，据此断言具体拦截原因 */
    private static void assertDetail(ThrowingCallable callable, String expectedDetail) {
        assertThatThrownBy(callable).isInstanceOf(FormBusinessException.class).satisfies(error -> assertThat(
                        String.valueOf(((FormBusinessException) error).getArgs()[0]))
                .contains(expectedDetail));
    }
}
