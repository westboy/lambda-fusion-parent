package com.lambda.fusion.workflow.definition.service;

import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.definition.model.DefinitionPage;
import com.lambda.fusion.workflow.definition.model.DefinitionSaveCommand;
import com.lambda.fusion.workflow.definition.model.DefinitionView;
import org.dromara.warm.flow.core.dto.FlowCombine;

/**
 * 流程定义服务（包装引擎 DefService）
 *
 * @author jin
 */
public interface DefinitionService {

    /**
     * 定义分页
     */
    PageView<DefinitionView> page(DefinitionPage page);

    /**
     * 定义详情（含节点与跳转出边，设计器加载用）
     */
    FlowCombine detail(Long id);

    /**
     * 保存设计器 JSON（新增或按版本升级）
     */
    DefinitionView save(DefinitionSaveCommand command);

    /**
     * 发布
     */
    boolean publish(Long id);

    /**
     * 失效（取消发布）
     */
    boolean unPublish(Long id);

    /**
     * 复制定义
     */
    boolean copy(Long id);

    /**
     * 删除（已发布定义不可删除，须先失效）
     */
    void remove(Long id);

    /**
     * 导出定义 JSON
     */
    String exportJson(Long id);
}
