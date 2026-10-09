package com.lambda.fusion.form.runtime.submit;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.service.FormRuntimeService;
import com.lambda.fusion.workflow.instance.model.StartCommand;
import com.lambda.fusion.workflow.instance.service.InstanceService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流程表单提交（新增业务行 + 同一事务发起流程实例）。
 *
 * <p>独立于 FormRuntimeServiceImpl：workflow 模块 optional 依赖，本类经
 * {@code @ConditionalOnClass} 守护，裁剪部署下不装配（Controller 侧降级为
 * FORM_WORKFLOW_UNAVAILABLE），避免 optional 类型在通用运行时类上直接引用导致 NoClassDefFoundError。
 *
 * @author Zx
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class WorkflowSubmitService {

    /** 实例变量键：提交时表单生效版本（workflow 侧读出随 TaskView/InstanceView 下发） */
    public static final String FORM_VERSION_VARIABLE = "__formVersion";

    private final FormRuntimeService formRuntimeService;
    private final ObjectProvider<InstanceService> instanceServices;

    /**
     * 同一事务：业务行落库 + 流程实例发起，start 失败随事务回滚不留孤行。
     * 业务行 id 作流程 business_id；bizType=formKey 供待办标题解析路由；行数据作流程变量供条件分支。
     */
    @Transactional(rollbackFor = Exception.class)
    public String submit(String formKey, String flowCode, java.util.Map<String, Object> data) {
        InstanceService instanceService = instanceServices.getIfAvailable();
        if (instanceService == null) {
            throw new FormBusinessException(FormErrorCode.FORM_WORKFLOW_UNAVAILABLE);
        }
        String businessId = formRuntimeService.create(formKey, data);
        StartCommand command = new StartCommand();
        command.setFlowCode(flowCode);
        command.setBusinessId(businessId);
        command.setBizType(formKey);
        command.setVariable(new HashMap<>(data));
        // 实例记录发起时的生效版本：办理/详情按该版本快照渲染单据（发布迭代不影响在途实例）
        command.getVariable().put(FORM_VERSION_VARIABLE, formRuntimeService.liveVersion(formKey));
        instanceService.start(command);
        return businessId;
    }
}
