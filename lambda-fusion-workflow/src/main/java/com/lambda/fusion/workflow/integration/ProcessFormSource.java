package com.lambda.fusion.workflow.integration;

import java.util.List;

/**
 * 流程表单数据源 SPI（form 模块在场时提供实现）
 *
 * <p>供 warm-flow 设计器「自定义表单路径」下拉与流程定义发布校验消费：
 * 只有标记为流程表单且已发布的动态表单才可被流程定义挂载。
 * 消费侧经 ObjectProvider 取用，缺席时降级（下拉为空、发布校验跳过）。
 *
 * @author Zx
 */
public interface ProcessFormSource {

    /** 已发布流程表单清单（设计器下拉数据源） */
    List<ProcessFormInfo> listMountable();

    /** 指定 formKey 当前是否可挂载（流程定义发布前校验） */
    boolean mountable(String formKey);
}
