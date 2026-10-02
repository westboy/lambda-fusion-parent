package com.lambda.fusion.authority.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Authority 模块错误码枚举
 * <p>
 * 定义Authority 模块所有业务错误码，统一管理错误信息
 * 错误码规则：Authority 模块使用 13001-13999 范围
 *
 * @author Jin
 */
@Getter
@AllArgsConstructor
public enum AuthorityErrorCode implements ErrorCode {

    // ========== 用户相关错误 (13001-13099) ==========
    /**
     * 用户不存在
     */
    USER_NOT_FOUND(13001, "用户不存在"),

    /**
     * 用户名已存在
     */
    USER_NAME_EXISTS(13002, "用户名已存在"),

    /**
     * 验证码错误
     */
    VERIFY_CODE_ERROR(13006, "验证码错误"),

    /**
     * 验证码已过期
     */
    VERIFY_CODE_EXPIRED(13007, "验证码已过期"),

    /**
     * 用户手机号已存在
     */
    USER_MOBILE_EXISTS(13008, "该手机号已被注册"),

    /**
     * 用户邮箱已存在
     */
    USER_EMAIL_EXISTS(13009, "该邮箱已被注册"),

    /**
     * 原密码错误
     */
    ORIGINAL_PASSWORD_ERROR(13010, "原密码错误"),

    /**
     * 手机号不存在
     */
    USER_MOBILE_NOT_FOUND(13011, "手机号不存在"),

    // ========== 角色相关错误 (13100-13199) ==========
    /**
     * 角色不存在
     */
    ROLE_NOT_FOUND(13101, "角色不存在"),

    /**
     * 角色名称已存在
     */
    ROLE_NAME_EXISTS(13103, "角色名称已存在"),

    /**
     * 角色已分配用户，无法删除
     */
    ROLE_ASSIGNED_TO_USER(13104, "角色已分配用户，无法删除"),

    /**
     * 角色已禁用
     */
    ROLE_DISABLED(13105, "角色已禁用"),

    /**
     * 分组不存在
     */
    GROUP_NOT_FOUND(13106, "分组不存在"),

    /**
     * 分组名称已存在
     */
    GROUP_NAME_EXISTS(13107, "分组名称已存在"),

    // ========== 租户相关错误 (13200-13299) ==========
    /**
     * 租户不存在
     */
    TENANT_NOT_FOUND(13201, "租户不存在"),

    /**
     * 租户已被禁用
     */
    TENANT_DISABLED(13202, "租户已被禁用"),

    /**
     * 租户待审核
     */
    TENANT_PENDING_APPROVAL(13203, "租户待审核"),

    /**
     * 租户审核未通过
     */
    TENANT_REJECTED(13204, "租户审核未通过"),

    /**
     * 租户配置无效
     */
    TENANT_CONFIG_INVALID(13205, "租户配置无效"),

    /**
     * 租户名称已存在
     */
    TENANT_NAME_EXISTS(13206, "租户名称已存在"),

    /**
     * 域名已被绑定
     */
    TENANT_DOMAIN_ALREADY_BOUND(13207, "域名已被其他租户绑定"),

    /**
     * 域名格式无效
     */
    TENANT_DOMAIN_INVALID(13208, "域名格式无效"),

    // ========== 组织相关错误 (13300-13399) ==========
    /**
     * 组织不存在
     */
    ORGANIZATION_NOT_FOUND(13301, "组织不存在"),

    /**
     * 组织名称已存在
     */
    ORGANIZATION_NAME_EXISTS(13302, "组织名称已存在"),

    /**
     * 组织已被禁用
     */
    ORGANIZATION_DISABLED(13303, "组织已被禁用"),

    /**
     * 存在子组织，无法删除
     */
    ORGANIZATION_HAS_CHILDREN(13304, "存在子组织，无法删除"),

    /**
     * 组织下存在用户，无法删除
     */
    ORGANIZATION_HAS_USERS(13305, "组织下存在用户，无法删除"),

    /**
     * 不能移动到自身或子组织
     */
    ORGANIZATION_CANNOT_MOVE_TO_SELF(13306, "不能移动到自身或子组织"),

    // ========== 地区相关错误 (13400-13499) ==========
    /**
     * 地区不存在
     */
    AREA_NOT_FOUND(13401, "地区不存在"),

    /**
     * 地区名称已存在
     */
    AREA_NAME_EXISTS(13402, "地区名称已存在"),

    // ========== 资源相关错误 (13500-13599) ==========
    /**
     * 资源不存在
     */
    RESOURCE_NOT_FOUND(13501, "资源不存在"),

    /**
     * 资源标识已存在
     */
    RESOURCE_AUTHORITY_EXISTS(13502, "资源标识已存在"),

    /**
     * 资源类型不支持
     */
    RESOURCE_TYPE_NOT_SUPPORTED(13503, "资源类型不支持"),

    RESOURCE_PATH_CONFLICT(13504, "访问路径已被其它资源占用：{0}"),

    // ========== 令牌相关错误 (13600-13699) ==========
    /**
     * API令牌不存在
     */
    API_TOKEN_NOT_FOUND(13601, "API令牌不存在"),

    /**
     * API令牌已被禁用
     */
    API_TOKEN_DISABLED(13603, "API令牌已被禁用"),

    /**
     * API令牌密钥错误
     */
    API_TOKEN_SECRET_ERROR(13604, "API令牌密钥错误"),

    // ========== 应用相关错误 (13400-13499) ==========
    /**
     * 应用不存在
     */
    APPLICATION_NOT_FOUND(13401, "应用不存在"),

    /**
     * 应用名称已存在
     */
    APPLICATION_NAME_EXISTS(13402, "应用名称已存在"),

    /**
     * Spring应用名已存在
     */
    APPLICATION_SPRING_NAME_EXISTS(13403, "Spring应用名已存在"),

    /**
     * 应用下存在资源，不允许删除
     */
    APPLICATION_HAS_RESOURCES(13404, "应用下存在资源，不允许删除"),

    // ========== 客户端相关错误 (13700-13799) ==========
    /**
     * 客户端不存在
     */
    CLIENT_NOT_FOUND(13701, "客户端不存在"),

    /**
     * 客户端密钥错误
     */
    CLIENT_SECRET_ERROR(13702, "客户端密钥错误"),

    /**
     * 客户端已被禁用
     */
    CLIENT_DISABLED(13703, "客户端已被禁用"),

    // ========== 认证相关错误 (13800-13899) ==========
    /**
     * 用户名或密码错误
     */
    AUTH_USERNAME_OR_PASSWORD_ERROR(13801, "用户名或密码错误"),

    /**
     * 账户已被禁用
     */
    AUTH_ACCOUNT_DISABLED(13802, "账户已被禁用"),

    /**
     * 无访问权限
     */
    AUTH_NO_PERMISSION(13805, "无访问权限"),

    /**
     * 用户不存在
     */
    AUTH_USER_NOT_FOUND(13806, "用户不存在"),

    // ========== 用户中心相关错误 (13900-13999) ==========
    /**
     * 手机号格式错误
     */
    MOBILE_FORMAT_ERROR(13901, "手机号格式错误"),

    /**
     * 邮箱格式错误
     */
    EMAIL_FORMAT_ERROR(13902, "邮箱格式错误"),

    /**
     * 验证码发送失败
     */
    VERIFY_CODE_SEND_FAILED(13903, "验证码发送失败"),

    // ========== 系统错误 (13990-13999) ==========
    /**
     * 系统内部错误
     */
    SYSTEM_ERROR(13990, "系统内部错误"),

    /**
     * 配置错误
     */
    CONFIGURATION_ERROR(13991, "系统配置错误"),

    /**
     * 不支持的操作
     */
    OPERATION_NOT_SUPPORTED(13992, "不支持的操作"),

    /**
     * 参数无效
     */
    INVALID_PARAMETER(13993, "参数无效"),

    /**
     * 并发更新失败
     */
    CONCURRENT_UPDATE_FAILED(13994, "并发更新失败，请重试");

    /**
     * 错误码
     */
    private final Integer code;

    /**
     * 错误消息
     */
    private final String message;
}
