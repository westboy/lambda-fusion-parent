package com.lambda.fusion.form.runtime.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.runtime.model.FormDataPageQuery;
import com.lambda.fusion.form.runtime.model.FormDataPageView;
import com.lambda.fusion.form.runtime.support.FormModel;
import java.util.List;
import java.util.Map;

/**
 * 动态表单数据读写服务（通用引擎出口）
 *
 * @author Zx
 */
public interface DynamicDataService {

    /**
     * 分页查询（AST 条件 + 白名单排序）
     */
    FormDataPageView page(FormModel model, FormDataPageQuery query);

    /**
     * 导出当前筛选全集为 CSV（UTF-8 BOM；行数超 max-export-rows 先拒绝）
     */
    void export(FormModel model, FormDataPageQuery query, java.io.Writer writer) throws java.io.IOException;

    /**
     * 详情
     */
    Map<String, Object> getById(FormModel model, String id);

    /**
     * 新增，返回主键
     */
    String create(FormModel model, Map<String, Object> data);

    /**
     * 编辑
     */
    void update(FormModel model, String id, Map<String, Object> data);

    /**
     * 删除（按主从级联策略）
     */
    void delete(FormModel model, String id);

    /**
     * TREE 类型：全量节点列表（前端组树）
     */
    List<Map<String, Object>> tree(FormModel model, String parentValue);

    /**
     * 台账式子表分页（外键由服务端绑定为主行 id，不占用子表单查询白名单）
     */
    PageView<Map<String, Object>> pageSubRows(
            FormModel model, String id, String subFormKey, long pageNum, long pageSize);

    /**
     * 台账式子表新增：外键由服务端绑定，写权限并入主表
     */
    void createSubRow(FormModel model, String id, String subFormKey, Map<String, Object> data);

    /**
     * 台账式子表编辑：归属由路径主行 id 绑定（WHERE 带外键条件），可选 __etag 行指纹乐观锁
     */
    void updateSubRow(FormModel model, String id, String subFormKey, String subId, Map<String, Object> data);

    /**
     * 台账式子表删除：归属由路径主行 id 绑定，尊重子表逻辑删除语义
     */
    void deleteSubRow(FormModel model, String id, String subFormKey, String subId);
}
