package com.lambda.fusion.authority.authentication.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "登陆用户信息")
public class AuthenticatedUser implements Serializable {
    /**
     * 头像
     */
    private String avatar;
    /**
     * 用户昵称
     */
    private String realName;
    /**
     * 用户角色
     */
    private List<String> roles;
    /**
     * 用户id
     */
    private String userId;
    /**
     * 用户名
     */
    private String username;

    /**
     * 用户描述
     */
    private String desc;

    /**
     * 首页地址
     */
    private String homePath;

    /**
     * accessToken
     */
    private String token;

    /**
     * 是否需要强制修改密码（首次登录/被重置/密码已过期）
     */
    @Schema(description = "是否需要强制修改密码")
    private Boolean passwordResetRequired = false;

    /**
     * 密码剩余有效天数：0 表示已过期；-1 表示未启用密码有效期或不在提醒窗口内（提醒阈值由后端 remind-days 配置）
     */
    @Schema(description = "密码剩余有效天数")
    private Integer passwordRemainDays = -1;
}
