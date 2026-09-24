package com.fusion.startup.leave.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lambda.fusion.core.pagination.PageQuery;
import lombok.Getter;
import lombok.Setter;

/**
 * 请假单分页查询
 *
 * @author jin
 */
@Getter
@Setter
public class LeavePage extends PageQuery<LeaveEntity> {

    /** 申请人（模糊） */
    private String applicant;

    /** 状态（等值：0 草稿 1 审批中 2 通过 3 驳回） */
    private Integer status;

    @Override
    public LambdaQueryWrapper<LeaveEntity> getLambdaQueryWrapper() {
        LambdaQueryWrapper<LeaveEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.isNotBlank(applicant), LeaveEntity::getApplicant, applicant);
        wrapper.eq(status != null, LeaveEntity::getStatus, status);
        wrapper.orderByDesc(LeaveEntity::getCreatedAt);
        return wrapper;
    }
}
