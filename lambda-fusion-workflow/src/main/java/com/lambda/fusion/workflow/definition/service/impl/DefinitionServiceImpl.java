package com.lambda.fusion.workflow.definition.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.workflow.definition.model.DefinitionPage;
import com.lambda.fusion.workflow.definition.model.DefinitionSaveCommand;
import com.lambda.fusion.workflow.definition.model.DefinitionView;
import com.lambda.fusion.workflow.definition.service.DefinitionService;
import com.lambda.fusion.workflow.exception.WorkflowBusinessException;
import com.lambda.fusion.workflow.exception.WorkflowErrorCode;
import com.lambda.fusion.workflow.integration.ProcessFormSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.dto.FlowCombine;
import org.dromara.warm.flow.core.entity.Definition;
import org.dromara.warm.flow.core.exception.FlowException;
import org.dromara.warm.flow.core.service.DefService;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.dromara.warm.flow.orm.mapper.FlowDefinitionMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 流程定义服务实现（包装引擎 DefService）
 *
 * @author jin
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class DefinitionServiceImpl implements DefinitionService {

    private final FlowDefinitionMapper definitionMapper;

    private final DefService defService;

    private final ObjectProvider<ProcessFormSource> processFormSources;

    @Override
    public PageView<DefinitionView> page(DefinitionPage page) {
        Page<FlowDefinition> result = definitionMapper.selectPage(page.getPage(), page.getLambdaQueryWrapper());
        return PageView.of(result, this::toView);
    }

    @Override
    public FlowCombine detail(Long id) {
        FlowCombine combine = callEngine(() -> defService.getFlowCombine(id));
        if (combine == null || combine.getDefinition() == null) {
            throw new WorkflowBusinessException(WorkflowErrorCode.DEFINITION_NOT_FOUND);
        }
        return combine;
    }

    @Override
    public DefinitionView save(DefinitionSaveCommand command) {
        Definition definition = callEngine(() -> defService.importJson(command.getDefJson()));
        return toView(definition);
    }

    @Override
    public boolean publish(Long id) {
        FlowDefinition definition = requireExists(id);
        requireMountableForm(definition.getFormPath());
        return callEngine(() -> defService.publish(id));
    }

    @Override
    public boolean unPublish(Long id) {
        requireExists(id);
        return callEngine(() -> defService.unPublish(id));
    }

    @Override
    public boolean copy(Long id) {
        requireExists(id);
        return callEngine(() -> defService.copyDef(id));
    }

    @Override
    public void remove(Long id) {
        FlowDefinition definition = requireExists(id);
        if (Integer.valueOf(1).equals(definition.getIsPublish())) {
            throw new WorkflowBusinessException(WorkflowErrorCode.DEFINITION_DELETE_FORBIDDEN);
        }
        callEngine(() -> defService.removeDef(List.of(id)));
    }

    @Override
    public String exportJson(Long id) {
        requireExists(id);
        return callEngine(() -> defService.exportJson(id));
    }

    private FlowDefinition requireExists(Long id) {
        FlowDefinition definition = definitionMapper.selectById(id);
        if (definition == null) {
            throw new WorkflowBusinessException(WorkflowErrorCode.DEFINITION_NOT_FOUND);
        }
        return definition;
    }

    /** 绑定了自定义表单的定义发布前校验表单可挂载（已发布流程表单）；数据源缺席（模块裁剪态）时跳过 */
    private void requireMountableForm(String formPath) {
        if (!StringUtils.hasText(formPath)) {
            return;
        }
        ProcessFormSource source = processFormSources.getIfAvailable();
        if (source == null) {
            return;
        }
        if (!source.mountable(formPath)) {
            throw new WorkflowBusinessException(WorkflowErrorCode.FORM_NOT_MOUNTABLE, formPath);
        }
    }

    private <T> T callEngine(Supplier<T> action) {
        try {
            return action.get();
        } catch (FlowException e) {
            throw new WorkflowBusinessException(WorkflowErrorCode.OPERATION_REJECTED, e.getMessage());
        }
    }

    private DefinitionView toView(Definition definition) {
        DefinitionView view = new DefinitionView();
        view.setId(definition.getId());
        view.setFlowCode(definition.getFlowCode());
        view.setFlowName(definition.getFlowName());
        view.setModelValue(definition.getModelValue());
        view.setCategory(definition.getCategory());
        view.setVersion(definition.getVersion());
        view.setIsPublish(definition.getIsPublish());
        view.setActivityStatus(definition.getActivityStatus());
        view.setFormCustom(definition.getFormCustom());
        view.setFormPath(definition.getFormPath());
        view.setListenerType(definition.getListenerType());
        view.setListenerPath(definition.getListenerPath());
        view.setExt(definition.getExt());
        view.setCreateTime(definition.getCreateTime());
        view.setUpdateTime(definition.getUpdateTime());
        return view;
    }
}
