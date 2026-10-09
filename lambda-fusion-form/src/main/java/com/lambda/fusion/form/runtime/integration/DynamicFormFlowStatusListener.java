package com.lambda.fusion.form.runtime.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lambda.fusion.form.FormConstants;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.model.TableColumnView;
import com.lambda.fusion.form.design.support.TableMetadataReader;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.runtime.mapper.DynamicTableMapper;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import com.lambda.fusion.workflow.WorkflowConstants;
import com.lambda.fusion.workflow.bridge.WorkflowFlowEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.util.StringUtils;

/**
 * 动态表单流程状态回写桥：监听 FINISH 钩子把引擎终态映射为目标表 STATUS 列
 *
 * <p>约定：目标表含 STATUS 列（tinyint/int，0 草稿 1 审批中 2 通过 3 驳回）即启用回写，
 * 无该列的流程表单静默跳过（列存在性经表元数据缓存校验）。事件按 bizType
 * （=流程表单 formKey，见提交编排）定位表，仅处理已开启流程表单标识的表单；
 * 状态分组口径消费 {@link WorkflowConstants} 谓词。
 *
 * <p>回写是次要动作：任何失败（表无 STATUS 列、业务行已被删等）只记告警，
 * 绝不阻断审批主流程——事件同步发布在审批事务内，向外抛异常会导致审批整体失败。
 * 装配由 FormConfigure 按 workflow 类路径条件守护（本类签名引用 workflow 类型）。
 *
 * @author Zx
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
@RequiredArgsConstructor
public class DynamicFormFlowStatusListener {

    private static final String STATUS_COLUMN = "STATUS";

    private static final int STATUS_DRAFT = 0;

    private static final int STATUS_APPROVED = 2;

    private static final int STATUS_REJECTED = 3;

    private final FormDefMapper forms;
    private final FormModelProvider modelProvider;
    private final DynamicTableMapper dynamicTableMapper;
    private final TableMetadataReader metadataReader;

    @EventListener
    public void on(WorkflowFlowEvent event) {
        if (event.type() != WorkflowConstants.Type.FINISH
                || !StringUtils.hasText(event.bizType())
                || !isProcessForm(event.bizType())) {
            return;
        }
        try {
            writeStatus(event);
        } catch (Exception e) {
            // 回写失败不阻断审批主流程（事件同步同事务，向外抛会导致审批整体失败）
            log.warn(
                    "流程状态回写失败，已放行审批：formKey={} businessId={} flowStatus={} - {}",
                    event.bizType(),
                    event.businessId(),
                    event.flowStatus(),
                    e.getMessage());
        }
    }

    private void writeStatus(WorkflowFlowEvent event) throws FormBusinessException {
        Integer status = mapStatus(event.flowStatus());
        if (status == null) {
            return;
        }
        FormModel model = modelProvider.require(event.bizType());
        // 无 STATUS 列的表不启用回写（列元数据走短 TTL 缓存，DDL 变更后最长一个周期生效）
        Map<String, TableColumnView> columns =
                metadataReader.listColumns(model.datasourceId(), model.tableName()).stream()
                        .collect(java.util.stream.Collectors.toMap(TableColumnView::getColumnName, column -> column));
        if (!columns.containsKey(STATUS_COLUMN)) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", event.businessId());
        params.put("status", status);
        int updated = dynamicTableMapper.updateDynamic(
                model.tableName(), STATUS_COLUMN + " = #{params.status}", activeRows(model), params);
        if (updated == 0) {
            log.warn("流程状态回写未命中业务行：formKey={} businessId={}", event.bizType(), event.businessId());
        }
    }

    /** 逻辑删除过滤（mapper 的 WHERE 内置 ID 条件，此处只叠加删除标记） */
    private String activeRows(FormModel model) {
        return StringUtils.hasText(model.logicDeleteColumn()) ? " AND " + model.logicDeleteColumn() + " = 0" : "";
    }

    /** bizType 是否为已发布的流程表单 */
    private boolean isProcessForm(String formKey) {
        return forms.selectCount(new LambdaQueryWrapper<FormDefEntity>()
                        .eq(FormDefEntity::getFormKey, formKey)
                        .eq(FormDefEntity::getProcessForm, Boolean.TRUE)
                        .eq(FormDefEntity::getStatus, FormConstants.STATUS_PUBLISHED))
                > 0;
    }

    /** 引擎 flowStatus → 动态表单 STATUS（分组口径消费 WorkflowConstants 谓词）；中间态返回 null 忽略 */
    private Integer mapStatus(String flowStatus) {
        if (WorkflowConstants.isPassed(flowStatus)) {
            return STATUS_APPROVED;
        }
        if (WorkflowConstants.isReturned(flowStatus)) {
            return STATUS_DRAFT;
        }
        if (WorkflowConstants.isTerminated(flowStatus)) {
            return STATUS_REJECTED;
        }
        return null;
    }
}
