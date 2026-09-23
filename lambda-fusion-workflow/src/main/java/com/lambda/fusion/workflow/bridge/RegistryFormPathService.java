package com.lambda.fusion.workflow.bridge;

import com.lambda.fusion.workflow.formpath.model.FormPathEntity;
import com.lambda.fusion.workflow.formpath.service.FormPathManagerService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.dto.Tree;
import org.dromara.warm.flow.ui.service.FormPathService;

/**
 * 设计器自定义表单路径：本模块 la_workflow_form_path 注册表（启用项）→ 设计器 form_path 下拉。
 *
 * <p>Tree.id = form_path（落 flow_definition.form_path），Tree.name = form_name（显示）。
 * 查询失败按次降级为空列表。
 *
 * @author jin
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class RegistryFormPathService implements FormPathService {

    private final FormPathManagerService formPathManagerService;

    public RegistryFormPathService(FormPathManagerService formPathManagerService) {
        this.formPathManagerService = formPathManagerService;
    }

    @Override
    public List<Tree> queryFormPath() {
        try {
            return formPathManagerService.listEnabled().stream()
                    .map(this::toTree)
                    .toList();
        } catch (Exception e) {
            log.warn("表单注册表查询失败，设计器表单下拉降级为空：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Tree toTree(FormPathEntity entity) {
        Tree tree = new Tree();
        tree.setId(entity.getFormPath());
        tree.setName(entity.getFormName());
        tree.setParentId(null);
        return tree;
    }
}
