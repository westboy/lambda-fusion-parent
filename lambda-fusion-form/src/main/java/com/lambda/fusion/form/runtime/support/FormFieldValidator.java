package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.util.StringUtils;

/** 字段定义、默认值与写入值共用的类型和规则校验，不依赖数据库隐式转换。 */
public final class FormFieldValidator {

    private static final Set<String> RULE_KEYS = Set.of("required", "min", "max", "pattern", "message");

    private FormFieldValidator() {}

    public static void validateDefinition(FormField field) {
        Map<String, Object> rules = field.form().rules();
        if (!RULE_KEYS.containsAll(rules.keySet())) {
            throw invalid(field, FormErrorCode.FORM_TABLE_INVALID, "包含不支持的校验规则");
        }
        if (rules.get("required") != null && !(rules.get("required") instanceof Boolean)
                || rules.get("message") != null && !(rules.get("message") instanceof String)) {
            throw invalid(field, FormErrorCode.FORM_TABLE_INVALID, "required 必须是布尔值，message 必须是文本");
        }
        BigDecimal min = bound(field, "min");
        BigDecimal max = bound(field, "max");
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw invalid(field, FormErrorCode.FORM_TABLE_INVALID, "min 不能大于 max");
        }
        if (rules.get("pattern") != null) {
            if (!textual(field) || !(rules.get("pattern") instanceof String pattern)) {
                throw invalid(field, FormErrorCode.FORM_TABLE_INVALID, "pattern 仅支持文本字段和字符串表达式");
            }
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw invalid(field, FormErrorCode.FORM_TABLE_INVALID, "正则表达式无效");
            }
        }
        if (StringUtils.hasText(field.defaultValue())) {
            normalize(field, field.defaultValue(), FormErrorCode.FORM_TABLE_INVALID);
        }
    }

    /** null 明确表示清空；调用方负责区分未提交、表单默认值和数据库默认值。 */
    public static Object normalize(FormField field, Object value, FormErrorCode error) {
        boolean required =
                !field.nullable() || Boolean.TRUE.equals(field.form().rules().get("required"));
        if (required && (value == null || value instanceof String text && text.isBlank())) {
            throw invalid(field, error, "不能为空");
        }
        if (value == null || value instanceof String text && text.isEmpty() && textual(field)) {
            return value;
        }
        Object normalized;
        try {
            normalized = switch (field.type()) {
                case "STRING", "TEXT", "FILE" -> requireString(value);
                case "DICT" -> value instanceof Number ? decimal(value).toPlainString() : requireString(value);
                case "INT" -> decimal(value).longValueExact();
                case "DECIMAL" -> decimal(value);
                case "BOOL" -> bool(value);
                case "DATE" ->
                    value instanceof LocalDate
                            ? value
                            : value instanceof java.sql.Date date
                                    ? date.toLocalDate()
                                    : LocalDate.parse(requireString(value));
                case "DATETIME" ->
                    value instanceof LocalDateTime
                            ? value
                            : value instanceof java.sql.Timestamp timestamp
                                    ? timestamp.toLocalDateTime()
                                    : LocalDateTime.parse(requireString(value).replace(' ', 'T'));
                default -> throw new IllegalArgumentException("unsupported field type");
            };
        } catch (IllegalArgumentException | ArithmeticException | DateTimeParseException e) {
            throw invalid(field, error, "不是有效的 " + field.type() + " 值");
        }
        BigDecimal min = bound(field, "min");
        BigDecimal max = bound(field, "max");
        // 与前端 String.length / Zod 长度规则一致（UTF-16 长度）；物理列容量单独按字符校验。
        BigDecimal size = normalized instanceof String text
                ? BigDecimal.valueOf(text.length())
                : normalized instanceof Number ? decimal(normalized) : null;
        if (size != null && (min != null && size.compareTo(min) < 0 || max != null && size.compareTo(max) > 0)) {
            throw invalid(field, error, "超出配置的" + (textual(field) ? "长度" : "数值") + "范围");
        }
        Object pattern = field.form().rules().get("pattern");
        if (pattern instanceof String expression
                && normalized instanceof String text
                && !Pattern.compile(expression).matcher(text).find()) {
            throw invalid(field, error, "不符合正则规则");
        }
        return normalized;
    }

    private static BigDecimal bound(FormField field, String key) {
        Object value = field.form().rules().get(key);
        if (value == null) {
            return null;
        }
        try {
            if (!(value instanceof Number)
                    || !textual(field) && !Set.of("INT", "DECIMAL").contains(field.type())) {
                throw new IllegalArgumentException("unsupported bound");
            }
            BigDecimal bound = decimal(value);
            if (textual(field)
                    && (bound.signum() < 0 || bound.stripTrailingZeros().scale() > 0)) {
                throw new IllegalArgumentException("invalid length");
            }
            return bound;
        } catch (IllegalArgumentException e) {
            throw invalid(field, FormErrorCode.FORM_TABLE_INVALID, key + " 必须是有效的数值或非负整数长度");
        }
    }

    private static boolean textual(FormField field) {
        return Set.of("STRING", "TEXT", "DICT", "FILE").contains(field.type());
    }

    private static String requireString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("expected string");
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number || value instanceof String) {
            return new BigDecimal(value.toString());
        }
        throw new IllegalArgumentException("expected number");
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if ("true".equals(value)
                || "1".equals(value)
                || value instanceof Number && decimal(value).compareTo(BigDecimal.ONE) == 0) {
            return true;
        }
        if ("false".equals(value)
                || "0".equals(value)
                || value instanceof Number && decimal(value).signum() == 0) {
            return false;
        }
        throw new IllegalArgumentException("expected boolean or 0/1");
    }

    private static FormBusinessException invalid(FormField field, FormErrorCode error, String reason) {
        return new FormBusinessException(error, field.name() + " " + reason);
    }
}
