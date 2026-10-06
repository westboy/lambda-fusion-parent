package com.lambda.fusion.workflow.launch.service;

import com.lambda.fusion.workflow.launch.model.LaunchGroupView;
import java.util.List;

/**
 * 发起中心（已发布流程定义按分类分组，供发起页卡片墙）
 *
 * @author Zx
 */
public interface LaunchService {

    /**
     * 分组查询当前用户视角的可发起流程
     *
     * @return 分类分组（组内按流程名排序；分类按名称排序，空分类归「未分类」）
     */
    List<LaunchGroupView> groups();
}
