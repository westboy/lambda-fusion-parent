package com.lambda.fusion.form.runtime.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormSchemaView;
import java.util.List;
import java.util.Map;

/**
 * 动态表单运行时编排（权限由 Controller 层校验）
 *
 * @author Zx
 */
public interface FormRuntimeService {

    /**
     * 前端渲染元数据（含当前用户按钮权限解析）
     */
    FormSchemaView schema(String formKey);

    PageView<Map<String, Object>> page(String formKey, FormDataPageQuery query);

    List<Map<String, Object>> tree(String formKey, String parentValue);

    /**
     * 台账式子表分页（外键由服务端绑定主行 id）
     */
    PageView<Map<String, Object>> pageSubRows(
            String formKey, String id, String subFormKey, long pageNum, long pageSize);

    /**
     * 台账式子表新增（外键由服务端绑定主行 id，并校验子表单新增权限）
     */
    void createSubRow(String formKey, String id, String subFormKey, Map<String, Object> data);

    Map<String, Object> getById(String formKey, String id);

    String create(String formKey, Map<String, Object> data);

    void update(String formKey, String id, Map<String, Object> data);

    void delete(String formKey, String id);
}
