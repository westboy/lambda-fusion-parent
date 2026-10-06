package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 子表列表呈现配置（主从关系上的 JSON 文本）的键白名单、值域与解析校验
 *
 * <p>结构为 {@code {columns:[{field,width,label,required,readonly,defaultValue}]}}，field 为子表单字段标识，
 * 未配置时列取子表单全量可见字段。label/required/readonly/defaultValue 为**主从场景覆盖**（仅本关系生效，
 * 不改子表单自身定义）。列归属（field 须为子表单已映射字段）由保存期对照子表单字段校验，编译期在此再拦截一道防快照篡改。
 *
 * @author Zx
 */
public final class SubTableConfigs {

    public static final Set<String> ROOT_KEYS = Set.of("columns");

    public static final Set<String> COLUMN_KEYS =
            Set.of("field", "width", "label", "required", "readonly", "defaultValue");

    private SubTableConfigs() {}

    /**
     * 校验并按配置顺序返回子表列表引用的字段标识（null/blank 视为未配置，返回空列表）
     */
    public static List<String> columnFields(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        final JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "subTableConfig 不是合法 JSON");
        }
        if (!root.isObject()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "subTableConfig 必须是 JSON 对象");
        }
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (!ROOT_KEYS.contains(entry.getKey())) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "subTableConfig 存在非法键：" + entry.getKey());
            }
        }
        JsonNode columns = root.get("columns");
        if (columns == null || columns.isNull()) {
            return List.of();
        }
        if (!columns.isArray() || columns.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns 必须是非空数组");
        }
        List<String> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode column : columns) {
            if (!column.isObject()) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns 项必须是对象");
            }
            for (Map.Entry<String, JsonNode> entry : column.properties()) {
                if (!COLUMN_KEYS.contains(entry.getKey())) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns 存在非法键：" + entry.getKey());
                }
            }
            String field = textValue(column.get("field"));
            if (field == null || !FormConstants.isIdentifier(field)) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns.field 非法：" + field);
            }
            if (!seen.add(field)) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns 字段重复：" + field);
            }
            JsonNode width = column.get("width");
            if (width != null && !width.isNull()) {
                if (!width.isIntegralNumber()
                        || width.intValue() < FieldConfigs.MIN_WIDTH
                        || width.intValue() > FieldConfigs.MAX_WIDTH) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER,
                            "subTableConfig.columns.width 必须是 "
                                    + FieldConfigs.MIN_WIDTH
                                    + "-"
                                    + FieldConfigs.MAX_WIDTH
                                    + " 的整数");
                }
            }
            validateOverride(column, "label", 128);
            validateOverride(column, "defaultValue", 512);
            validateBoolean(column, "required");
            validateBoolean(column, "readonly");
            fields.add(field);
        }
        return fields;
    }

    /** 文本覆盖键的类型与长度校验 */
    private static void validateOverride(JsonNode column, String key, int maxLength) {
        JsonNode value = column.get(key);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual() || value.textValue().length() > maxLength) {
            throw new FormBusinessException(
                    FormErrorCode.INVALID_PARAMETER,
                    "subTableConfig.columns." + key + " 必须是不超过 " + maxLength + " 字符的文本");
        }
    }

    /** 布尔覆盖键的类型校验 */
    private static void validateBoolean(JsonNode column, String key) {
        JsonNode value = column.get(key);
        if (value != null && !value.isNull() && !value.isBoolean()) {
            throw new FormBusinessException(
                    FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns." + key + " 必须是布尔值");
        }
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns.field 必须是字符串");
        }
        return node.textValue();
    }
}
