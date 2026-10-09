package com.lambda.fusion.form.design.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.design.model.FormDefPage;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.support.DataSourceCatalog;
import java.util.List;

/**
 * 表单草稿设计服务（CRUD + 选表导列）
 *
 * @author Zx
 */
public interface FormDesignService {

    PageView<FormDefView> page(FormDefPage page);

    FormDefView detail(String id);

    String save(FormDefSaveCommand command);

    void update(String id, FormDefSaveCommand command);

    void remove(String id);

    /**
     * 可映射物理表清单（排除平台内部表；按数据源注册 id 读取，空=平台主库）
     */
    List<String> listTables(String datasourceId);

    /**
     * 目标表列元数据（选表导列一键生成字段草稿；按数据源注册 id 读取，空=平台主库）
     */
    List<TableColumnView> listColumns(String datasourceId, String tableName);

    /**
     * 设计器数据源下拉清单（动态数据源组件缺席时为空清单，仅平台主库可映射）
     */
    List<DataSourceCatalog.Option> listDataSources();
}
