package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    public static final Set<String> ROOT_KEYS = Set.of("columns", "rowButtons");

    public static final Set<String> COLUMN_KEYS = Set.of(
            "field", "width", "label", "required", "readonly", "defaultValue", "pattern", "min", "max", "message");

    /** 子表行按钮动作白名单：add=添加子行、edit=编辑子行（仅 LIST）、delete=删除子行（INLINE 为移除） */
    public static final Set<String> ROW_BUTTON_ACTIONS = Set.of("add", "edit", "delete");

    public static final Set<String> ROW_BUTTON_KEYS = Set.of("action", "label", "show");

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
            validateOverride(column, "message", 256);
            validateBoolean(column, "required");
            validateBoolean(column, "readonly");
            JsonNode pattern = column.get("pattern");
            if (pattern != null && !pattern.isNull()) {
                if (!pattern.isTextual() || pattern.textValue().length() > 256) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns.pattern 必须是不超过 256 字符的文本");
                }
                try {
                    java.util.regex.Pattern.compile(pattern.textValue());
                } catch (java.util.regex.PatternSyntaxException e) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns.pattern 正则无效");
                }
            }
            for (String boundKey : List.of("min", "max")) {
                JsonNode bound = column.get(boundKey);
                if (bound != null && !bound.isNull() && !bound.isNumber()) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER, "subTableConfig.columns." + boundKey + " 必须是数值");
                }
            }
            fields.add(field);
        }
        return fields;
    }

    /**
     * 校验子表行按钮配置 rowButtons：[{action, show, label}]。
     * action 取 {@link #ROW_BUTTON_ACTIONS}（add/edit/delete）且不重复；
     * show 布尔；label 不超过 128 字符。未配置返回空列表。
     */
    public static List<String> rowButtons(ObjectMapper mapper, String json) {
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
        JsonNode buttons = root.get("rowButtons");
        if (buttons == null || buttons.isNull()) {
            return List.of();
        }
        if (!buttons.isArray() || buttons.isEmpty()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "subTableConfig.rowButtons 必须是非空数组");
        }
        List<String> actions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode button : buttons) {
            if (!button.isObject()) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "subTableConfig.rowButtons 项必须是对象");
            }
            for (Map.Entry<String, JsonNode> entry : button.properties()) {
                if (!ROW_BUTTON_KEYS.contains(entry.getKey())) {
                    throw new FormBusinessException(
                            FormErrorCode.INVALID_PARAMETER, "subTableConfig.rowButtons 存在非法键：" + entry.getKey());
                }
            }
            String action = textValue(button.get("action"));
            if (action == null || !ROW_BUTTON_ACTIONS.contains(action)) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "subTableConfig.rowButtons.action 非法：" + action);
            }
            if (!seen.add(action)) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "subTableConfig.rowButtons 动作重复：" + action);
            }
            validateOverride(button, "label", 128);
            validateBoolean(button, "show");
            actions.add(action);
        }
        return actions;
    }

    /**
     * 解析各列的场景覆盖（key=字段标识，value=覆盖属性 Map，仅含出现过的键）。
     * 供写入路径叠加到子表字段规则上（后端校验与前端呈现同一事实源）
     */
    public static Map<String, Map<String, Object>> overridesOf(ObjectMapper mapper, String json) {
        Map<String, Map<String, Object>> overrides = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return overrides;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JacksonException e) {
            return overrides;
        }
        JsonNode columns = root.get("columns");
        if (columns == null || !columns.isArray()) {
            return overrides;
        }
        for (JsonNode column : columns) {
            JsonNode field = column.get("field");
            if (field == null || !field.isTextual()) {
                continue;
            }
            Map<String, Object> override = new LinkedHashMap<>();
            putText(column, override, "label");
            putText(column, override, "defaultValue");
            putText(column, override, "pattern");
            putText(column, override, "message");
            putBoolean(column, override, "required");
            putBoolean(column, override, "readonly");
            putNumber(column, override, "min");
            putNumber(column, override, "max");
            if (!override.isEmpty()) {
                overrides.put(field.textValue(), override);
            }
        }
        return overrides;
    }

    private static void putText(JsonNode column, Map<String, Object> override, String key) {
        JsonNode value = column.get(key);
        if (value != null && value.isTextual()) {
            override.put(key, value.textValue());
        }
    }

    private static void putBoolean(JsonNode column, Map<String, Object> override, String key) {
        JsonNode value = column.get(key);
        if (value != null && value.isBoolean()) {
            override.put(key, value.booleanValue());
        }
    }

    private static void putNumber(JsonNode column, Map<String, Object> override, String key) {
        JsonNode value = column.get(key);
        if (value != null && value.isNumber()) {
            override.put(key, value.numberValue());
        }
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
