package com.lambda.fusion.form.design.adapter;

import com.lambda.fusion.workflow.formpath.service.FormPathManagerService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;

/**
 * workflow 注册表适配：把 form 侧注册语义翻译为 FormPathManagerService 的幂等写
 *
 * @author Zx
 */
@SuppressFBWarnings("EI_EXPOSE_REP2")
@RequiredArgsConstructor
public class WorkflowFormPathRegistry implements FormPathRegistry {

    private final FormPathManagerService formPaths;

    @Override
    public void register(String formName, String formPath, String remarks) {
        formPaths.upsert(formName, formPath, remarks);
    }

    @Override
    public void unregister(String formPath) {
        formPaths.removeByPath(formPath);
    }
}
