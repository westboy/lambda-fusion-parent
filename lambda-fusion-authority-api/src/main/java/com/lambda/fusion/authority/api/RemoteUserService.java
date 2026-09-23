package com.lambda.fusion.authority.api;

/**
 * 远程用户查询服务：按用户名获取用户身份详情（对齐 {@code UserDetails}）。
 *
 * <p>authority 侧经 Dubbo 暴露实现；其他服务（如 ai）通过 {@code authority-api} 引用消费，
 * 不得绕过本接口直接跨服务引用实现类（见工程契约 §7.3）。
 *
 * @author zx
 */
public interface RemoteUserService {

    /**
     * 根据用户名查询用户详情。
     *
     * @param username 用户名（登录名）
     * @return 用户详情；用户不存在时返回 {@code null}
     */
    RemoteUser getByUsername(String username);

    /**
     * 分页查询用户列表（供办理人选择等场景）。
     *
     * @param tenantId 租户 ID（为空时按平台角色查询口径处理）
     * @param keyword 关键字（用户名/昵称模糊匹配，可为空）
     * @param pageNum 页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页结果
     */
    RemotePage<RemoteUser> pageUsers(String tenantId, String keyword, int pageNum, int pageSize);
}
