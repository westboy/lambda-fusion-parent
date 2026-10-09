package com.lambda.fusion.form.design.support;

import java.util.List;

/**
 * 设计器数据源下拉的枚举端口：动态数据源组件在场时列出已启用数据源，缺席时为空清单
 * （设计器仅展示「平台主库」一项，行为与集成前一致）。
 *
 * @author Zx
 */
public interface DataSourceCatalog {

    /** 已启用数据源清单（注册 id + 显示名 + 库类型），组件缺席时为空 */
    List<Option> listEnabled();

    /**
     * 数据源下拉项
     *
     * @param id 注册 id（la_datasources.ID，回存 la_form_def.DATASOURCE_ID）
     * @param name 显示名
     * @param dbType 库类型（mysql/postgresql…，展示用）
     */
    record Option(String id, String name, String dbType) {}
}
