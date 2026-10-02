package com.lambda.fusion.form.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Form 模块错误码枚举
 *
 * <p>错误码规则：Form 模块使用 18100-18199 范围（workflow 占 16xxx 之后的空闲段）
 *
 * @author Zx
 */
@Getter
@AllArgsConstructor
public enum FormErrorCode implements ErrorCode {

    // ========== 通用错误 (18100-18109) ==========
    /**
     * 请求参数非法
     */
    INVALID_PARAMETER(18100, "请求参数非法"),

    /**
     * 表单不存在
     */
    FORM_NOT_FOUND(18101, "表单不存在"),

    /**
     * 表单未发布
     */
    FORM_NOT_PUBLISHED(18102, "表单未发布"),

    /**
     * 表单标识已存在
     */
    FORM_KEY_DUPLICATED(18103, "表单标识已存在"),

    /**
     * 表单已停用
     */
    FORM_DISABLED(18104, "表单已停用"),

    /**
     * 已发布表单不可删除，须先停用
     */
    FORM_DELETE_FORBIDDEN(18105, "已发布表单不可删除，请先停用"),

    FORM_KEY_RESERVED(18106, "表单标识 design 为系统设计权限保留，请使用其它标识"),

    // ========== 发布错误 (18110-18119) ==========
    /**
     * 目标表校验失败：{0}
     */
    FORM_TABLE_INVALID(18110, "目标表校验失败：{0}"),

    /**
     * 版本不存在
     */
    FORM_VERSION_NOT_FOUND(18111, "表单版本不存在"),

    /**
     * 内容与当前生效版本一致，无需重复发布
     */
    FORM_VERSION_UNCHANGED(18112, "内容与当前生效版本一致，无需重复发布"),

    /**
     * 草稿并发修改冲突，请刷新后重试
     */
    FORM_DRAFT_CONFLICT(18113, "草稿已被他人修改，请刷新后重试"),

    // ========== 查询错误 (18120-18129) ==========
    /**
     * 查询字段不在白名单内
     */
    FORM_QUERY_FIELD_FORBIDDEN(18120, "查询字段不在白名单内"),

    /**
     * 查询操作符不支持
     */
    FORM_QUERY_OPERATOR_UNSUPPORTED(18121, "查询操作符不支持"),

    /**
     * 查询条件数超过上限
     */
    FORM_QUERY_CONDITION_LIMIT(18122, "查询条件数超过上限"),

    /**
     * 树子孙节点数超过上限
     */
    FORM_TREE_DESCENDANT_LIMIT(18123, "树子孙节点数超过上限"),

    // ========== 数据错误 (18130-18139) ==========
    /**
     * 数据不存在
     */
    FORM_DATA_NOT_FOUND(18130, "数据不存在"),

    /**
     * 存在子表数据，禁止删除
     */
    FORM_SUB_DELETE_FORBIDDEN(18131, "存在子表数据，禁止删除"),

    /**
     * 字段校验失败：{0}
     */
    FORM_DATA_INVALID(18132, "字段校验失败：{0}"),

    FORM_ROUTE_UNAVAILABLE(18150, "当前服务未提供菜单管理能力"),
    FORM_ROUTE_CONFLICT(18151, "路由资源冲突：{0}"),
    FORM_ROUTE_EXISTS(18152, "请先撤销路由，再删除表单");

    private final Integer code;

    private final String message;
}
