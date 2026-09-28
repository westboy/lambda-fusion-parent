package com.lambda.fusion.form.design.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.design.model.FormDefPage;
import com.lambda.fusion.form.design.model.FormDefSaveCommand;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.model.TableColumnView;
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
     * 可映射物理表清单（排除元数据表与系统表）
     */
    List<String> listTables();

    /**
     * 目标表列元数据（选表导列一键生成字段草稿）
     */
    List<TableColumnView> listColumns(String tableName);
}
