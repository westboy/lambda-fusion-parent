package com.lambda.fusion.form.runtime.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormDataPageView;
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

    FormDataPageView page(String formKey, FormDataPageQuery query);

    /**
     * 导出当前筛选全集为 CSV（权限由 Controller 层校验）
     */
    void export(String formKey, FormDataPageQuery query, java.io.Writer writer) throws java.io.IOException;

    List<Map<String, Object>> tree(String formKey, String parentValue);

    /**
     * 台账式子表分页（外键由服务端绑定主行 id）
     */
    PageView<Map<String, Object>> pageSubRows(
            String formKey, String id, String subFormKey, long pageNum, long pageSize);

    /**
     * 台账式子表新增（外键由服务端绑定主行 id，写权限并入主表）
     */
    void createSubRow(String formKey, String id, String subFormKey, Map<String, Object> data);

    /**
     * 台账式子表编辑（归属由路径主行 id 绑定，可选 __etag 行指纹乐观锁）
     */
    void updateSubRow(String formKey, String id, String subFormKey, String subId, Map<String, Object> data);

    /**
     * 台账式子表删除（归属由路径主行 id 绑定）
     */
    void deleteSubRow(String formKey, String id, String subFormKey, String subId);

    Map<String, Object> getById(String formKey, String id);

    String create(String formKey, Map<String, Object> data);

    /**
     * 流程表单提交：同一事务内新增业务行并发起流程
     * （businessId=行 id，bizType=formKey，variables=行数据供条件分支；workflow 缺席时拒绝）
     *
     * @param formKey  表单标识
     * @param flowCode 流程编码（发起中心卡片下发）
     * @param data     表单数据
     * @return 业务行 id
     */
    String submit(String formKey, String flowCode, Map<String, Object> data);

    void update(String formKey, String id, Map<String, Object> data);

    void delete(String formKey, String id);
}
