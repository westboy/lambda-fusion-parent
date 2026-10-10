package com.lambda.fusion.form;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormPropertiesTest {

    @Test
    void mappableTableUsesCaseInsensitiveExcludePatternOverIdentifiers() {
        FormProperties properties = new FormProperties();
        // 默认排除平台内部表（la_ 前缀除 la_demo_ 演示表、ai_/flow_/系统表），大小写不敏感
        assertThat(properties.isMappableTable("la_demo_area")).isTrue();
        assertThat(properties.isMappableTable("LA_DEMO_LEAVE")).isTrue();
        assertThat(properties.isMappableTable("crm_order")).isTrue();
        assertThat(properties.isMappableTable("la_form_def")).isFalse();
        assertThat(properties.isMappableTable("LA_FORM_DEF")).isFalse();
        assertThat(properties.isMappableTable("ai_chat")).isFalse();
        assertThat(properties.isMappableTable("flow_definition")).isFalse();
        assertThat(properties.isMappableTable("qrtz_locks")).isFalse();
        assertThat(properties.isMappableTable("databasechangelog")).isFalse();
        // 非标识符整体拒绝
        assertThat(properties.isMappableTable("1bad")).isFalse();
        assertThat(properties.isMappableTable(null)).isFalse();
    }

    @Test
    void customExcludePatternReplacesDefaultAndRecompiles() {
        FormProperties properties = new FormProperties();
        properties.setTableExcludePattern("crm_.*");
        // 自定义正则整体替换默认集：crm_ 被拒，la_form_def 不再被保护（由调用方自行收口）
        assertThat(properties.isMappableTable("crm_order")).isFalse();
        assertThat(properties.isMappableTable("CRM_ORDER")).isFalse();
        assertThat(properties.isMappableTable("la_form_def")).isTrue();
        // 再次修改立即生效（编译产物随 setter 重算）
        properties.setTableExcludePattern("flow_.*");
        assertThat(properties.isMappableTable("crm_order")).isTrue();
        assertThat(properties.isMappableTable("flow_definition")).isFalse();
    }
}
