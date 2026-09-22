package com.lambda.fusion.workflow.instance.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.warm.flow.core.dto.FlowCombine;

/**
 * 流程图视图：定义静态结构（节点/出边/坐标）+ 实例当前状态标注
 *
 * <p>前端 @vue-flow 据此渲染：allNodes → nodes（coordinate 定位、按 instance.nodeCode 高亮）、
 * allSkips → edges（skip_condition 可标注）。
 *
 * @author jin
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "流程图视图")
public class ChartView {

    @Schema(description = "定义与节点/出边")
    private FlowCombine flow;

    @Schema(description = "实例当前状态")
    private InstanceView instance;
}
