package com.lambda.fusion.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FormPropertiesTest {

    @Test
    void allowsTableIsCaseInsensitiveAgainstWhitelist() {
        FormProperties properties = new FormProperties();
        properties.setAllowedTables(List.of("la_demo_area", "LA_DEMO_LEAVE", " "));
        // yml 小写配置命中库里大写表名，反之亦然
        assertThat(properties.allowsTable("LA_DEMO_LEAVE")).isTrue();
        assertThat(properties.allowsTable("la_demo_leave")).isTrue();
        assertThat(properties.allowsTable("La_Demo_Area")).isTrue();
        // 未开放/非法/被排除正则命中（大小写不敏感）仍拒
        assertThat(properties.allowsTable("la_demo_other")).isFalse();
        assertThat(properties.allowsTable("1bad")).isFalse();
        properties.setAllowedTables(List.of("la_form_def"));
        assertThat(properties.allowsTable("LA_FORM_DEF")).isFalse();
        // 未配置白名单=不开放任何表
        new FormProperties().setAllowedTables(null);
        assertThat(new FormProperties().allowsTable("la_demo_area")).isFalse();
    }
}
