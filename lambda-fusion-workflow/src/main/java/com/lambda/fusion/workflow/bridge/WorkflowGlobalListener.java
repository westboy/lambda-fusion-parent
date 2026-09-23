package com.lambda.fusion.workflow.bridge;

import com.lambda.fusion.workflow.WorkflowConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.entity.Instance;
import org.dromara.warm.flow.core.entity.Task;
import org.dromara.warm.flow.core.listener.GlobalListener;
import org.dromara.warm.flow.core.listener.ListenerVariable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 引擎全局监听器：warm-flow 四钩子（start/assignment/finish/create）统一转为 Spring 事件
 * {@link WorkflowFlowEvent}。节点级 listener_path 保留给业务方按引擎原生方式使用，本模块不封装。
 *
 * @author jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowGlobalListener implements GlobalListener {

    private final ApplicationEventPublisher publisher;

    @Override
    public void start(ListenerVariable listenerVariable) {
        publish(WorkflowConstants.Type.START, listenerVariable);
    }

    @Override
    public void assignment(ListenerVariable listenerVariable) {
        publish(WorkflowConstants.Type.ASSIGNMENT, listenerVariable);
    }

    @Override
    public void finish(ListenerVariable listenerVariable) {
        publish(WorkflowConstants.Type.FINISH, listenerVariable);
    }

    @Override
    public void create(ListenerVariable listenerVariable) {
        publish(WorkflowConstants.Type.CREATE, listenerVariable);
    }

    private void publish(WorkflowConstants.Type type, ListenerVariable listenerVariable) {
        Instance instance = listenerVariable.getInstance();
        if (instance == null) {
            return;
        }
        Task task = listenerVariable.getTask();
        WorkflowFlowEvent event = new WorkflowFlowEvent(
                type,
                instance.getId(),
                instance.getBusinessId(),
                task == null ? null : task.getId(),
                instance.getFlowStatus(),
                instance.getTenantId(),
                instance.getNodeCode(),
                System.currentTimeMillis());
        publisher.publishEvent(event);
        log.debug(
                "发布流程事件 {} instanceId={} businessId={} flowStatus={}",
                type,
                instance.getId(),
                instance.getBusinessId(),
                instance.getFlowStatus());
    }
}
