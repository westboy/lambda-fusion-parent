package com.lambda.fusion.authority.authentication.service;

import com.lambda.fusion.authority.authentication.model.AuthenticatedUser;
import com.lambda.fusion.authority.authentication.model.MenuQuery;
import com.lambda.fusion.authority.authentication.model.MenuRoute;
import com.lambda.fusion.authority.user.model.UserProfile;
import com.lambda.fusion.core.identity.UserDetails;
import java.util.List;

/**
 * 认证服务接口
 * 负责用户认证、授权和导航菜单相关的业务逻辑
 */
public interface AuthenticationService {

    /**
     * 获取用户的导航菜单
     *
     * @param user     登录用户
     * @param parentId 父级菜单ID，只返回此菜单下的数据
     * @param level    指定菜单层级
     * @return 导航菜单列表
     */
    List<MenuRoute> getUserMenus(UserDetails user, String parentId, Integer level);

    /**
     * 获取用户的导航菜单（按 application 区分下发语义，SQL 层统一生效，dev/非 dev 一致）
     *
     * <ul>
     *   <li>主控台前端：{@code /auth/menus?mode=0}（不传 application）——下发 APPLICATION 为空的
     *       菜单 ∪ 属于 STANDALONE=0（非独立前端）应用的菜单；
     *   <li>独立前端：{@code /auth/menus?mode=0&application={appId}}——只返回该应用的菜单树。
     * </ul>
     *
     * <p>独立前端应用的菜单必须挂在标记了对应应用的目录下，否则主控台会出现空目录残留。
     *
     * @param user  登录用户
     * @param query 导航查询参数
     * @return 导航菜单列表
     */
    default List<MenuRoute> getUserMenus(UserDetails user, MenuQuery query) {
        return getUserMenus(user, query.getParentId(), query.getLevel());
    }

    /**
     * 获取当前登录用户的导航菜单，语义同 {@link #getUserMenus(UserDetails, MenuQuery)}
     *
     * @param query 导航查询参数
     * @return 导航菜单列表
     */
    List<MenuRoute> getUserMenus(MenuQuery query);

    /**
     * 根据角色ID获取用户列表
     *
     * @param roleId 角色ID
     * @return 用户列表
     */
    List<UserProfile> getUsersByRoleId(String roleId);

    /**
     * 获取当前登录用户的信息。
     *
     * @return 返回包含用户详细信息的AuthenticatedUser对象，包括用户ID、真实姓名、角色列表、头像、描述、token以及主页路径等信息。
     */
    AuthenticatedUser getUserInfo();

    /**
     * 获取当前用户的权限码集合
     *
     * @return 权限码列表
     */
    List<String> getUserPermissions();
}
