package com.lambda.fusion.form.runtime.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class FormFieldValidatorTest {

    @ParameterizedTest
    @MethodSource("invalidDefinitions")
    void rejectsInvalidRulesAndDefaultsDuringCompilation(String type, String defaultValue, Map<String, Object> rules) {
        assertThatThrownBy(() -> field(type, true, defaultValue, rules))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_TABLE_INVALID.getCode()));
    }

    static Stream<Arguments> invalidDefinitions() {
        return Stream.of(
                Arguments.of("INT", "1.2", Map.of()),
                Arguments.of("BOOL", "yes", Map.of()),
                Arguments.of("DATE", "2026-02-29", Map.of()),
                Arguments.of("DECIMAL", "NaN", Map.of()),
                Arguments.of("STRING", "abcdef", Map.of("max", 3)),
                Arguments.of("STRING", "abc", Map.of("pattern", "^[0-9]+$")),
                Arguments.of("STRING", null, Map.of("pattern", "[")),
                Arguments.of("STRING", null, Map.of("min", 3, "max", 2)),
                Arguments.of("STRING", null, Map.of("min", -1)),
                Arguments.of("STRING", null, Map.of("max", 1.5)),
                Arguments.of("INT", null, Map.of("min", "1")),
                Arguments.of("INT", null, Map.of("pattern", "\\d+")),
                Arguments.of("BOOL", null, Map.of("max", 1)),
                Arguments.of("STRING", null, Map.of("required", "true")),
                Arguments.of("STRING", null, Map.of("unknown", true)));
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void rejectsInvalidTypesWithoutSilentCoercion(String type, Object value) {
        var field = field(type, true, null, Map.of());
        assertThatThrownBy(() -> FormFieldValidator.normalize(field, value, FormErrorCode.FORM_DATA_INVALID))
                .isInstanceOf(FormBusinessException.class)
                .satisfies(error -> assertThat(((FormBusinessException) error).getCode())
                        .isEqualTo(FormErrorCode.FORM_DATA_INVALID.getCode()));
    }

    static Stream<Arguments> invalidValues() {
        return Stream.of(
                Arguments.of("INT", 1.5),
                Arguments.of("INT", "9223372036854775808"),
                Arguments.of("DECIMAL", Double.POSITIVE_INFINITY),
                Arguments.of("DECIMAL", List.of(1)),
                Arguments.of("DECIMAL", " "),
                Arguments.of("STRING", 123),
                Arguments.of("BOOL", "yes"),
                Arguments.of("BOOL", 2),
                Arguments.of("DATE", "2026-02-30"),
                Arguments.of("DATETIME", "2026-10-01 25:00:00"),
                Arguments.of("DATETIME", "2026-10-01T12:00:00Z"));
    }

    @Test
    void convertsTextDefaultsToTypedValuesAndKeepsZeroAndFalse() {
        assertThat(normalize("INT", "0")).isEqualTo(0L);
        assertThat(normalize("DECIMAL", "0.10")).isEqualTo(new BigDecimal("0.10"));
        assertThat(normalize("BOOL", "false")).isEqualTo(false);
        assertThat(normalize("BOOL", 1)).isEqualTo(true);
        assertThat(normalize("DATE", "2024-02-29")).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(normalize("DATETIME", "2026-10-01 12:30:00")).isEqualTo(LocalDateTime.of(2026, 10, 1, 12, 30));
    }

    @Test
    void enforcesRequiredEvenOnNullableFieldsAndPreservesOptionalEmptyText() {
        var required = field("STRING", true, null, Map.of("required", true));
        for (Object empty : new Object[] {null, "", "  "}) {
            assertThatThrownBy(() -> FormFieldValidator.normalize(required, empty, FormErrorCode.FORM_DATA_INVALID))
                    .isInstanceOf(FormBusinessException.class);
        }
        var optional = field("STRING", true, null, Map.of("min", 2, "pattern", "^[A-Z]+$"));
        assertThat(FormFieldValidator.normalize(optional, "", FormErrorCode.FORM_DATA_INVALID))
                .isEqualTo("");
        assertThat(FormFieldValidator.normalize(optional, null, FormErrorCode.FORM_DATA_INVALID))
                .isNull();
        assertThatThrownBy(() -> FormFieldValidator.normalize(optional, " ", FormErrorCode.FORM_DATA_INVALID))
                .isInstanceOf(FormBusinessException.class);
    }

    @Test
    void numericBoundsAndTextRulesUseTheSameLogicForDefaultsAndInput() {
        var number = field("DECIMAL", true, "0", Map.of("min", 0, "max", 10));
        assertThatThrownBy(() -> FormFieldValidator.normalize(number, -1, FormErrorCode.FORM_DATA_INVALID))
                .isInstanceOf(FormBusinessException.class);
        assertThatThrownBy(() -> FormFieldValidator.normalize(number, 10.01, FormErrorCode.FORM_DATA_INVALID))
                .isInstanceOf(FormBusinessException.class);
        var text = field("STRING", true, "ab", Map.of("min", 2, "max", 4, "pattern", "b"));
        assertThat(FormFieldValidator.normalize(text, "abc", FormErrorCode.FORM_DATA_INVALID))
                .isEqualTo("abc");
        assertThatThrownBy(() -> FormFieldValidator.normalize(text, "abcdx", FormErrorCode.FORM_DATA_INVALID))
                .isInstanceOf(FormBusinessException.class);
    }

    private Object normalize(String type, Object value) {
        return FormFieldValidator.normalize(field(type, true, null, Map.of()), value, FormErrorCode.FORM_DATA_INVALID);
    }

    private FormModel.FormField field(String type, boolean nullable, String defaultValue, Map<String, Object> rules) {
        FormDefSnapshot snapshot = new FormDefSnapshot();
        snapshot.setFormKey("test_form");
        snapshot.setFormType("SINGLE");
        snapshot.setTableName("LA_TEST_FORM");
        FormDefSnapshot.Field field = new FormDefSnapshot.Field();
        field.setFieldName("value");
        field.setColumnName("FIELD_VALUE");
        field.setFieldType(type);
        field.setNullable(nullable);
        field.setDefaultValue(defaultValue);
        field.getForm().setRules(rules);
        snapshot.setFields(List.of(field));
        SnapshotJson json = new SnapshotJson(new ObjectMapper());
        return new FormModelCompiler(json)
                .compile(json.write(snapshot), 1)
                .fields()
                .getFirst();
    }
}
