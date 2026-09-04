package com.lambda.fusion.authority.user.model;

import lombok.Data;

/**
 * 密码时效评估结果
 * <p>依据 {@code lambda.fusion.authorize.password-strategy} 的有效期策略
 * 与最近一次密码变更日志计算得出。</p>
 */
@Data
public class PasswordExpiry {

    /**
     * 是否需要强制修改密码（密码已超过有效期）
     */
    private boolean resetRequired = false;

    /**
     * 密码剩余有效天数：已过期为 0；超出提醒窗口、未启用策略、豁免角色或无密码日志（存量用户）为 -1，不参与提醒
     */
    private int remainDays = -1;

    /**
     * 密码已使用天数；未启用策略、豁免角色或无密码日志时保持 -1
     */
    private int usedDays = -1;
}
