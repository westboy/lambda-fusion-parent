package com.lambda.fusion.authority.api;

import java.util.List;

/**
 * 远程角色查询服务：列举可选角色（供工作流设计器办理人选择等场景）。
 *
 * <p>authority 侧经 Dubbo 暴露实现；消费方通过 {@code authority-api} 引用，
 * 不得绕过本接口直接跨服务引用实现类（见工程契约 §7.3）。
 *
 * @author jin
 */
public interface RemoteRoleService {

    /**
     * 列举指定租户下的启用角色。
     *
     * @param tenantId 租户 ID（为空时按平台角色查询口径处理）
     * @param keyword 关键字（角色权限码/显示名模糊匹配，可为空）
     * @return 角色摘要列表
     */
    List<RemoteRole> listRoles(String tenantId, String keyword);
}
