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
}
