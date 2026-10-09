package com.lambda.fusion.authority.api;

import java.util.Collection;
import java.util.Map;

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
     * 分页查询用户列表（供办理人选择、动态表单用户选择器等场景）。
     *
     * @param tenantId 租户 ID（为空时按平台角色查询口径处理）
     * @param keyword 关键字（用户名/昵称模糊匹配，可为空；为空=初始列表，不下推 LIKE）
     * @param orgId 机构 ID（非空时仅返回该机构下的用户；可为空）
     * @param pageNum 页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页结果
     */
    RemotePage<RemoteUser> pageUsers(String tenantId, String keyword, String orgId, int pageNum, int pageSize);

    /**
     * 批量查询用户显示名（昵称，缺失回退用户名；供列表/详情把用户名渲染为名称）。
     *
     * @param usernames 用户名集合（空集合返回空 Map）
     * @return username → 显示名（未命中的用户名不进结果）
     */
    Map<String, String> mapNicknames(Collection<String> usernames);
}
