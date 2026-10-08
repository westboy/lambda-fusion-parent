package com.lambda.fusion.form.runtime.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormDefSnapshot;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelCompiler;
import com.lambda.fusion.form.runtime.support.SnapshotJson;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 详情呈现配置编译回归（la_form_def.DETAIL_CONFIG → FormModel.DetailConfig）：
 * 缺省/空串回落 dialog/medium/2 列，非法 mode/width/columns 保守收敛，配置随快照生效。
 */
class DetailConfigCompileTest {

    private FormModel compile(String detailConfig) {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("detail_test");
        snapshot.setFormName("详情测试");
        snapshot.setFormType("SINGLE");
        snapshot.setTableName("LA_TEST_FORM");
        snapshot.setFields(List.of(field("personName", "PERSON_NAME", "STRING", false)));
        if (detailConfig != null) {
            snapshot.setDetailConfig(detailConfig);
        }
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        return new FormModelCompiler(json).compile(json.write(snapshot), 1L);
    }

    private static FormDefSnapshot.Field field(String name, String column, String type, boolean nullable) {
        FormDefSnapshot.Field f = new FormDefSnapshot.Field();
        f.setFieldName(name);
        f.setColumnName(column);
        f.setFieldType(type);
        f.setNullable(nullable);
        return f;
    }

    @Test
    void absentDetailConfigFallsBackToDialogMediumTwoColumns() {
        FormModel.DetailConfig config = compile(null).detailConfig();
        assertThat(config.mode()).isEqualTo("dialog");
        assertThat(config.width()).isEqualTo("medium");
        assertThat(config.columns()).isEqualTo(2);
        assertThat(config.systemCollapsed()).isFalse();
    }

    @Test
    void configuredValuesAreTakenAndBlankFallsBack() {
        FormModel.DetailConfig config = compile(
                        "{\"mode\":\"drawer\",\"width\":\"large\",\"columns\":3,\"systemCollapsed\":true}")
                .detailConfig();
        assertThat(config.mode()).isEqualTo("drawer");
        assertThat(config.width()).isEqualTo("large");
        assertThat(config.columns()).isEqualTo(3);
        assertThat(config.systemCollapsed()).isTrue();

        FormModel.DetailConfig blank = compile("  ").detailConfig();
        assertThat(blank.mode()).isEqualTo("dialog");
        assertThat(blank.columns()).isEqualTo(2);
    }

    @Test
    void illegalValuesAreConvergedNotRejected() {
        // 非法 mode/width/columns 保守回落（呈现配置不影响数据路径，不阻断发布）
        FormModel.DetailConfig config =
                compile("{\"mode\":\"modal\",\"width\":\"huge\",\"columns\":9}").detailConfig();
        assertThat(config.mode()).isEqualTo("dialog");
        assertThat(config.width()).isEqualTo("medium");
        assertThat(config.columns()).isEqualTo(2);
    }

    @Test
    void malformedJsonIsRejected() {
        assertThatThrownBy(() -> compile("{not-json"))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> {
                    assertThat(((FormBusinessException) error).getCode())
                            .isEqualTo(FormErrorCode.FORM_TABLE_INVALID.getCode());
                    // 参数化消息的 {0} 位携带解析失败明细
                    assertThat(String.valueOf(((FormBusinessException) error).getArgs()[0]))
                            .contains("detailConfig");
                });
    }
}
