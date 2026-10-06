package com.lambda.fusion.workflow.launch.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.workflow.launch.model.LaunchGroupView;
import com.lambda.fusion.workflow.launch.service.LaunchService;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.dromara.warm.flow.orm.entity.FlowDefinition;
import org.dromara.warm.flow.orm.mapper.FlowDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 发起中心实现：已发布定义按分类分组；可发起性 = 已绑定动态表单且当前用户具备该表单新增权限
 *
 * <p>表单权限码与动态表单引擎同源（form:{formKey}:add），判定经框架权限工具（开发者豁免同源）。
 *
 * @author Zx
 */
@Service
@RequiredArgsConstructor
public class LaunchServiceImpl implements LaunchService {

    private static final String UNCATEGORIZED = "未分类";

    private final FlowDefinitionMapper definitionMapper;

    @Override
    public List<LaunchGroupView> groups() {
        Map<String, LaunchGroupView> byCategory = new LinkedHashMap<>();
        for (FlowDefinition definition : published()) {
            LaunchGroupView group = byCategory.computeIfAbsent(categoryOf(definition.getCategory()), key -> {
                LaunchGroupView view = new LaunchGroupView();
                view.setCategory(key);
                return view;
            });
            group.getItems().add(toItem(definition));
        }
        return byCategory.values().stream()
                .peek(group -> group.getItems()
                        .sort(Comparator.comparing(item -> item.getFlowName() == null ? "" : item.getFlowName())))
                .sorted(Comparator.comparing(LaunchGroupView::getCategory))
                .toList();
    }

    private List<FlowDefinition> published() {
        return definitionMapper.selectList(
                new LambdaQueryWrapper<FlowDefinition>().eq(FlowDefinition::getIsPublish, 1));
    }

    private String categoryOf(String category) {
        return StringUtils.hasText(category) ? category : UNCATEGORIZED;
    }

    private LaunchGroupView.Item toItem(FlowDefinition definition) {
        LaunchGroupView.Item item = new LaunchGroupView.Item();
        item.setFlowCode(definition.getFlowCode());
        item.setFlowName(definition.getFlowName());
        item.setFormKey(StringUtils.hasText(definition.getFormPath()) ? definition.getFormPath() : null);
        item.setLaunchable(item.getFormKey() != null && AuthUtils.hasPermission("form:" + item.getFormKey() + ":add"));
        return item;
    }
}
