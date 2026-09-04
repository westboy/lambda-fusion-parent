package com.lambda.fusion.authority.user.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.fusion.authority.user.model.entity.UserPasswordEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 密码日志表无 TENANT_ID 列，类级忽略租户拦截（含 BaseMapper 继承方法）
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface UserPasswordMapper extends BaseMapper<UserPasswordEntity> {

    /**
     * 用户更改密码后插入日志
     * @param updatePwdLog 修改密码日志对象
     */
    void insertLog(UserPasswordEntity updatePwdLog);
}
