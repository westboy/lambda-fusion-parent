package com.lambda.fusion.workflow.bridge;

import com.lambda.fusion.workflow.integration.ProcessFormInfo;
import com.lambda.fusion.workflow.integration.ProcessFormSource;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.dto.Tree;
import org.dromara.warm.flow.ui.service.FormPathService;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 设计器自定义表单路径：form 模块的已发布流程表单（ProcessFormSource）→ 设计器 form_path 下拉。
 *
 * <p>Tree.id = formKey（落 flow_definition.form_path，办理端按 formKey 渲染动态表单），
 * Tree.name = form_name。数据源缺席（无 form 模块）或查询失败按次降级为空列表。
 *
 * @author Zx
 */
@Slf4j
public class ProcessFormPathService implements FormPathService {

    private final ObjectProvider<ProcessFormSource> sources;

    public ProcessFormPathService(ObjectProvider<ProcessFormSource> sources) {
        this.sources = sources;
    }

    @Override
    public List<Tree> queryFormPath() {
        ProcessFormSource source = sources.getIfAvailable();
        if (source == null) {
            return Collections.emptyList();
        }
        try {
            return source.listMountable().stream().map(this::toTree).toList();
        } catch (Exception e) {
            log.warn("流程表单数据源查询失败，设计器表单下拉降级为空：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Tree toTree(ProcessFormInfo item) {
        Tree tree = new Tree();
        tree.setId(item.formKey());
        tree.setName(item.formName());
        tree.setParentId(null);
        return tree;
    }
}
