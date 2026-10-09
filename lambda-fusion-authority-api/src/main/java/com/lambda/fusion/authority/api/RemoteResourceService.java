package com.lambda.fusion.authority.api;

import java.util.List;

/**
 * 远程资源管理服务：外部功能（如动态表单挂载路由）在 authority 菜单树上
 * 读取、创建、更新、移动、删除自有资源子树并绑定接口权限。
 *
 * <p>authority 侧经 Dubbo 暴露实现；消费方通过 {@code authority-api} 引用，
 * 不得绕过本接口直接跨服务引用实现类（见工程契约 §7.3）。
 * 归属校验由调用方携带的业务扩展标记（businessExpand）配合资源查询完成；
 * {@link #lockResourceTree()} 为语句级锁，跨调用的归属检查由调用方在每次变更前重读资源保证。
 *
 * @author zx
 */
public interface RemoteResourceService {

    /**
     * 根据编号查询资源。
     *
     * @param id 资源编号
     * @return 资源信息；不存在时返回 {@code null}
     */
    RemoteResource getResourceById(String id);

    /**
     * 列举指定资源的可用下级节点。
     *
     * @param parentId 上级资源编号
     * @return 下级资源列表；上级不存在时返回空列表
     */
    List<RemoteResource> queryAvailableChildren(String parentId);

    /**
     * 创建资源。
     *
     * @param create 创建参数
     * @return 创建后的资源（含生成的资源编号）
     */
    RemoteResource addResource(RemoteCreateResource create);

    /**
     * 更新资源（仅覆盖契约携带的字段，其余字段由 authority 侧保留原值）。
     *
     * @param resource 待更新资源（id 必填）
     */
    void updateResource(RemoteResource resource);

    /**
     * 移动资源到新的上级。
     *
     * @param move 移动参数
     */
    void moveResource(RemoteMoveResource move);

    /**
     * 删除资源（级联删除下级资源）。
     *
     * @param id 资源编号
     */
    void deleteResource(String id);

    /** 管理事务内锁定资源树（变更前调用，配合每次变更前的归属重读校验） */
    void lockResourceTree();

    /**
     * 绑定资源与接口权限（幂等，重复绑定忽略）。
     *
     * @param resourceId 资源编号
     * @param permissionId 接口权限编号（API 资源编号）
     */
    void bindPermission(String resourceId, String permissionId);
}
