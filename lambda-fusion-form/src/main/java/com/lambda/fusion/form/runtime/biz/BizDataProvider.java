package com.lambda.fusion.form.runtime.biz;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 表单运行时系统主数据源（机构/用户）SPI：authority 模块在场时由适配实现注入，
 * 缺席时端点降级返回空（动态表单不硬依赖权限模块）。
 *
 * <p>权限模型：消费方是运行时表单页（form:page 权限），不是系统管理端——
 * 因此只返回 id + 名称最小视图，不下发手机号等敏感字段。
 *
 * @author Zx
 */
public interface BizDataProvider {

    /** 机构树（仅启用机构；节点 {id, name, parentId}，children 内嵌） */
    List<Map<String, Object>> organizationTree();

    /** 角色清单（当前用户可见；节点 {authority, name}） */
    List<Map<String, Object>> roles();

    /**
     * 用户检索（昵称/账号模糊匹配，分页；节点 {username, nickName}）。
     * orgId 非空时仅返回该机构下的用户（空串/null 不限）。
     * keyword 为空时返回初始列表（打开下拉即有的可选用户，不下推 LIKE）。
     */
    List<Map<String, Object>> searchUsers(String keyword, int pageSize, String orgId);

    /**
     * 批量 id→名称（列表/详情/导出把 id 渲染为名称）：ORG 取机构名，USER 取用户昵称。
     * 未命中的 id 不进结果（调用方回退原值）。
     */
    Map<String, String> labels(String type, Collection<String> ids);
}
