package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 草稿、发布和预览共用布局校验，未布置的新字段按默认占一列追加。 */
public final class FormLayouts {
    private FormLayouts() {}

    public static FormLayout read(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return new FormLayout(2, List.of());
        }
        try {
            var node = mapper.readTree(json);
            if (!node.isObject()
                    || node.properties().stream()
                            .anyMatch(entry -> !Set.of("columns", "items").contains(entry.getKey()))) {
                throw invalid("布局必须是包含 columns、items 的对象");
            }
            if (!node.path("columns").isIntegralNumber() || !node.path("items").isArray()) {
                throw invalid("布局 columns 必须是整数，items 必须是数组");
            }
            for (var item : node.path("items")) {
                if (!item.isObject()
                        || !item.path("field").isTextual()
                        || !item.path("span").isIntegralNumber()
                        || item.properties().stream()
                                .anyMatch(entry -> !Set.of("field", "span").contains(entry.getKey()))) {
                    throw invalid("布局项必须包含字段 field 和整数占列 span");
                }
            }
            return mapper.readValue(json, FormLayout.class);
        } catch (JacksonException e) {
            throw invalid("布局 JSON 非法");
        }
    }

    public static FormLayout compile(FormLayout layout, List<String> fields) {
        FormLayout source = layout == null ? new FormLayout(2, List.of()) : layout;
        if (source.columns() < 1 || source.columns() > 4) {
            throw invalid("布局列数必须在 1 到 4 之间");
        }
        Set<String> available = new HashSet<>(fields);
        Set<String> seen = new HashSet<>();
        List<FormLayout.Item> items = new ArrayList<>();
        for (FormLayout.Item item : source.items()) {
            if (item == null || !available.contains(item.field()) || !seen.add(item.field())) {
                throw invalid("布局引用了不存在或重复的字段");
            }
            if (item.span() < 1 || item.span() > source.columns()) {
                throw invalid("字段占列必须在 1 到布局列数之间：" + item.field());
            }
            items.add(item);
        }
        fields.stream()
                .filter(field -> !seen.contains(field))
                .forEach(field -> items.add(new FormLayout.Item(field, 1)));
        return new FormLayout(source.columns(), items);
    }

    private static FormBusinessException invalid(String message) {
        return new FormBusinessException(FormErrorCode.INVALID_PARAMETER, message);
    }
}
