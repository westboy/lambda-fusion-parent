package com.lambda.fusion.form.runtime.support;

import java.util.List;

/** 表单与详情共用的栅格布局；字段引用与顺序、占列只在此定义。 */
public record FormLayout(int columns, List<Item> items, String labelPosition) {

    /** 标签位置：TOP 标签在控件上方（默认） / LEFT 标签在控件左侧 */
    public static final String LABEL_POSITION_TOP = "TOP";

    public static final String LABEL_POSITION_LEFT = "LEFT";

    /** 缺省/非法值回落上方，保持兼容 */
    public String effectiveLabelPosition() {
        return LABEL_POSITION_LEFT.equalsIgnoreCase(labelPosition) ? LABEL_POSITION_LEFT : LABEL_POSITION_TOP;
    }

    public FormLayout {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(String field, int span) {}
}
