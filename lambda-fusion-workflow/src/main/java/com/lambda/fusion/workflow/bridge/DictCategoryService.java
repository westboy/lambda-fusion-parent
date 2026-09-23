package com.lambda.fusion.workflow.bridge;

import com.lambda.fusion.dict.model.DictInfo;
import com.lambda.fusion.dict.service.DictInfoService;
import com.lambda.fusion.workflow.WorkflowConstants;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.dto.Tree;
import org.dromara.warm.flow.ui.service.CategoryService;

/**
 * 设计器流程类别：dictionary 模块字典（{@code workflow_category}）→ 设计器类别下拉。
 *
 * <p>字典项经字典管理界面维护，此处只读映射；字典类型未配置时返回空列表。
 *
 * @author jin
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class DictCategoryService implements CategoryService {

    private final DictInfoService dictInfoService;

    public DictCategoryService(DictInfoService dictInfoService) {
        this.dictInfoService = dictInfoService;
    }

    @Override
    public List<Tree> queryCategory() {
        try {
            List<DictInfo> dicts = dictInfoService.getTreeData(WorkflowConstants.DICT_TYPE_WORKFLOW_CATEGORY);
            // 设计器以 Tree.id 落 flow_definition.category（展示/过滤口径），故映射字典名而非字典项 ID
            Map<String, String> idToName =
                    dicts.stream().collect(Collectors.toMap(DictInfo::getId, DictInfo::getFieldName, (a, b) -> a));
            return dicts.stream().map(dict -> toTree(dict, idToName)).toList();
        } catch (Exception e) {
            log.warn("流程类别字典查询失败，类别下拉降级为空：{}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Tree toTree(DictInfo dict, Map<String, String> idToName) {
        Tree tree = new Tree();
        tree.setId(dict.getDictType());
        tree.setName(dict.getFieldName());
        tree.setParentId(idToName.getOrDefault(dict.getParentId(), dict.getParentId()));
        return tree;
    }
}
