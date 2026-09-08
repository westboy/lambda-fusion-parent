package com.lambda.fusion.authority.authentication.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.lambda.fusion.authority.authentication.model.AuthUser;
import com.lambda.fusion.authority.authentication.model.MenuQuery;
import com.lambda.fusion.authority.authentication.model.MenuRoute;
import com.lambda.fusion.authority.user.model.UserProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 认证数据访问层接口
 * 负责用户认证相关的数据库操作
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AuthenticationMapper {

    /**
     * 根据用户名加载用户详细信息
     *
     * @param username 用户名
     * @return 用户详细信息
     */
    AuthUser selectUserDetailByUsername(@Param("username") String username);

    /**
     * 根据手机号加载用户详细信息
     *
     * @param mobile 手机号
     * @return 用户详细信息列表
     */
    List<AuthUser> selectUserDetailsByMobile(@Param("mobile") String mobile);

    /**
     * 根据查询条件获取导航菜单
     *
     * @param query 导航查询参数
     * @return 导航菜单列表
     */
    List<MenuRoute> selectNavigation(@Param("query") MenuQuery query);

    /**
     * 根据角色ID获取用户列表
     *
     * @param roleId 角色ID
     * @return 用户列表
     */
    List<UserProfile> selectUserProfileByRoleId(@Param("roleId") String roleId);

    /**
     * 根据用户名获取用户的所有权限码（包括按钮权限等）
     *
     * @param username 用户名
     * @return 权限码列表
     */
    List<String> selectAuthoritiesByUsername(@Param("username") String username);

    /**
     * 根据用户名获取用户的所有权限
     *
     * @param username 用户名
     * @return 权限列表
     */
    List<String> selectPermissionsByUsername(@Param("username") String username);

    List<String> selectTenantAdminPermissions(@Param("username") String username);

    List<String> selectTenantAdminAuthorities(@Param("username") String username);
}
