package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 字段三段配置（查询/列表/表单详情）的键白名单、值域与 JSON 文本解析校验
 *
 * <p>草稿保存期第一道校验；发布快照编译期由 FormModelCompiler 对照快照对象再校验一道。
 *
 * @author Zx
 */
public final class FieldConfigs {

    public static final Set<String> QUERY_KEYS = Set.of("show", "op", "defaultValue");

    public static final Set<String> LIST_KEYS =
            Set.of("show", "width", "align", "format", "frozen", "summary", "defaultSort", "sortOrder");

    public static final Set<String> FORM_KEYS = Set.of("show", "component", "props", "rules", "readonly");

    public static final Set<String> ALIGNS = Set.of("left", "center", "right");

    public static final Set<String> FORMATS = Set.of("date", "datetime", "dict");

    public static final int MIN_WIDTH = 1;

    public static final int MAX_WIDTH = 500;

    private FieldConfigs() {}

    /** 草稿保存期校验三段 JSON 文本（null/blank 视为缺省配置） */
    public static void validate(ObjectMapper mapper, String queryJson, String listJson, String formJson) {
        toQueryConf(mapper, queryJson);
        toListConf(mapper, listJson);
        toFormConf(mapper, formJson);
    }

    /** 查询配置：{show, op, defaultValue} */
    public static FormDefSnapshot.QueryConf toQueryConf(ObjectMapper mapper, String json) {
        JsonNode node = readObject(mapper, json, QUERY_KEYS, "queryConfig");
        FormDefSnapshot.QueryConf conf = new FormDefSnapshot.QueryConf();
        if (node == null) {
            return conf;
        }
        conf.setShow(booleanValue(node.get("show"), "queryConfig.show"));
        String op = textValue(node.get("op"), "queryConfig.op");
        if (op != null && !FormConstants.QUERY_OPERATORS.contains(op)) {
            throw new FormBusinessException(FormErrorCode.FORM_QUERY_OPERATOR_UNSUPPORTED, op);
        }
        conf.setOp(op);
        conf.setDefaultValue(scalarValue(node.get("defaultValue"), "queryConfig.defaultValue"));
        return conf;
    }

    /** 列表配置：{show, width, align, format, frozen, summary, defaultSort, sortOrder} */
    public static FormDefSnapshot.ListConf toListConf(ObjectMapper mapper, String json) {
        JsonNode node = readObject(mapper, json, LIST_KEYS, "listConfig");
        FormDefSnapshot.ListConf conf = new FormDefSnapshot.ListConf();
        if (node == null) {
            return conf;
        }
        conf.setShow(booleanValue(node.get("show"), "listConfig.show"));
        JsonNode widthNode = node.get("width");
        if (widthNode != null && !widthNode.isNull()) {
            if (!widthNode.isIntegralNumber()) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig.width 必须是整数");
            }
            int width = widthNode.intValue();
            if (width < MIN_WIDTH || width > MAX_WIDTH) {
                throw new FormBusinessException(
                        FormErrorCode.INVALID_PARAMETER, "listConfig.width 必须在 " + MIN_WIDTH + "-" + MAX_WIDTH);
            }
            conf.setWidth(width);
        }
        String align = textValue(node.get("align"), "listConfig.align");
        if (align != null && !ALIGNS.contains(align)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig.align 非法：" + align);
        }
        conf.setAlign(align);
        String format = textValue(node.get("format"), "listConfig.format");
        if (format != null && !FORMATS.contains(format)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig.format 非法：" + format);
        }
        conf.setFormat(format);
        String frozen = textValue(node.get("frozen"), "listConfig.frozen");
        if (frozen != null && !"left".equals(frozen) && !"right".equals(frozen)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig.frozen 非法：" + frozen);
        }
        conf.setFrozen(frozen);
        conf.setSummary(booleanValue(node.get("summary"), "listConfig.summary"));
        String defaultSort = textValue(node.get("defaultSort"), "listConfig.defaultSort");
        if (defaultSort != null && !"asc".equals(defaultSort) && !"desc".equals(defaultSort)) {
            throw new FormBusinessException(
                    FormErrorCode.INVALID_PARAMETER, "listConfig.defaultSort 非法：" + defaultSort);
        }
        conf.setDefaultSort(defaultSort);
        JsonNode sortOrderNode = node.get("sortOrder");
        if (sortOrderNode != null && !sortOrderNode.isNull()) {
            if (!sortOrderNode.isIntegralNumber()) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig.sortOrder 必须是整数");
            }
            conf.setSortOrder(sortOrderNode.intValue());
        }
        return conf;
    }

    /** 列表级配置：{layout, pageSizes, advancedQuery, fullQuery, showSeq, buttons} */
    public static FormDefSnapshot.ListConfig toListConfig(ObjectMapper mapper, String json) {
        JsonNode node = readObject(
                mapper,
                json,
                Set.of("layout", "pageSizes", "advancedQuery", "fullQuery", "showSeq", "buttons"),
                "listConfig");
        if (node == null) {
            return new FormDefSnapshot.ListConfig();
        }
        try {
            return mapper.readValue(node.toString(), FormDefSnapshot.ListConfig.class);
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "listConfig 解析失败：" + e.getMessage());
        }
    }

    /** 表单与详情配置：{show, component, props, rules, readonly} */
    public static FormDefSnapshot.FormConf toFormConf(ObjectMapper mapper, String json) {
        JsonNode node = readObject(mapper, json, FORM_KEYS, "formConfig");
        FormDefSnapshot.FormConf conf = new FormDefSnapshot.FormConf();
        if (node == null) {
            return conf;
        }
        conf.setShow(booleanValue(node.get("show"), "formConfig.show"));
        String component = textValue(node.get("component"), "formConfig.component");
        if (component != null && !FormConstants.isIdentifier(component)) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "formConfig.component 非法：" + component);
        }
        conf.setComponent(component);
        conf.setProps(objectValue(mapper, node.get("props"), "formConfig.props"));
        conf.setRules(objectValue(mapper, node.get("rules"), "formConfig.rules"));
        conf.setReadonly(booleanValue(node.get("readonly"), "formConfig.readonly"));
        return conf;
    }

    /** 解析为 JSON 对象并校验键白名单；null/blank 返回 null */
    private static JsonNode readObject(ObjectMapper mapper, String json, Set<String> allowedKeys, String name) {
        if (json == null || json.isBlank()) {
            return null;
        }
        final JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, name + " 不是合法 JSON：" + e.getMessage());
        }
        if (!node.isObject()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, name + " 必须是 JSON 对象");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!allowedKeys.contains(entry.getKey())) {
                throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, name + " 存在非法键：" + entry.getKey());
            }
        }
        return node;
    }

    /** 三态布尔：null 表示配置缺省（调用方按默认归一），布尔值用于显式开关 */
    @SuppressFBWarnings("NP_BOOLEAN_RETURN_NULL")
    private static Boolean booleanValue(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, name + " 必须是布尔");
        }
        return node.asBoolean();
    }

    private static String textValue(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, name + " 必须是字符串");
        }
        String text = node.textValue();
        return text.isBlank() ? null : text;
    }

    /** 标量（字符串/数字/布尔）转字符串存储；其余类型拒绝 */
    private static String scalarValue(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.toString();
        }
        throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, name + " 必须是标量");
    }

    private static Map<String, Object> objectValue(ObjectMapper mapper, JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, name + " 必须是 JSON 对象");
        }
        try {
            return mapper.readValue(node.toString(), new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException e) {
            throw new FormBusinessException(FormErrorCode.INVALID_PARAMETER, name + " 解析失败：" + e.getMessage());
        }
    }
}
