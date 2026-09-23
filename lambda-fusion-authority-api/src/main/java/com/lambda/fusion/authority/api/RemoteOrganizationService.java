package com.lambda.fusion.authority.api;

import java.util.List;

/**
 * 远程组织查询服务：列举可选组织/部门（供工作流设计器办理人选择等场景）。
 *
 * <p>authority 侧经 Dubbo 暴露实现；消费方通过 {@code authority-api} 引用，
 * 不得绕过本接口直接跨服务引用实现类（见工程契约 §7.3）。
 *
 * @author jin
 */
public interface RemoteOrganizationService {

    /**
     * 列举指定租户下的组织。
     *
     * @param tenantId 租户 ID（为空时按框架默认租户口径处理）
     * @param keyword 关键字（组织名/别名模糊匹配，可为空）
     * @return 组织摘要列表
     */
    List<RemoteOrg> listOrganizations(String tenantId, String keyword);
}
