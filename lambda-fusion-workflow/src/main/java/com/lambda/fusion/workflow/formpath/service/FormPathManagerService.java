package com.lambda.fusion.workflow.formpath.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.formpath.model.FormPathEntity;
import com.lambda.fusion.workflow.formpath.model.FormPathPage;
import com.lambda.fusion.workflow.formpath.model.FormPathSaveCommand;
import java.util.List;

/**
 * 表单注册表管理服务（CRUD）
 *
 * <p>命名区别于引擎 SPI {@code org.dromara.warm.flow.ui.service.FormPathService}
 * （设计器下拉适配见 bridge 包 {@code RegistryFormPathService}）。
 *
 * @author jin
 */
public interface FormPathManagerService {

    PageView<FormPathEntity> page(FormPathPage page);

    List<FormPathEntity> listEnabled();

    void save(FormPathSaveCommand command);

    void update(String id, FormPathSaveCommand command);

    void toggle(String id, boolean enabled);

    void remove(String id);

    /**
     * 按路径幂等注册/更新（动态表单挂路由时自动登记）：路径已存在则更新名称并启用，不存在则插入
     *
     * @param formName 表单名称（设计器下拉显示）
     * @param formPath 表单路径（租户内唯一）
     * @param remarks 备注
     */
    void upsert(String formName, String formPath, String remarks);

    /**
     * 按路径注销（撤路由时调用）：记录不存在时静默跳过
     *
     * @param formPath 表单路径
     */
    void removeByPath(String formPath);
}
