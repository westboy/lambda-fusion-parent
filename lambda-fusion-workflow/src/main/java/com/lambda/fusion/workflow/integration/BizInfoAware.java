package com.lambda.fusion.workflow.integration;

/**
 * 可回填业务展示信息的视图载体（InstanceView / TaskView 实现）
 *
 * @author jin
 */
public interface BizInfoAware {

    /**
     * 业务类型（发起时约定存入 flow_instance.ext）
     */
    String getBizType();

    /**
     * 业务单据 ID（flow_instance.business_id）
     */
    String getBusinessId();

    /**
     * 回填展示信息
     */
    void setBizInfo(BizInfo bizInfo);
}
