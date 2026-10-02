package com.lambda.fusion.form.runtime.support;

import java.util.List;

/** 表单与详情共用的栅格布局；字段引用与顺序、占列只在此定义。 */
public record FormLayout(int columns, List<Item> items) {
    public FormLayout {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(String field, int span) {}
}
