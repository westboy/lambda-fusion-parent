package com.lambda.fusion.authority.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.fusion.authority.user.model.*;
import com.lambda.fusion.authority.user.model.entity.UserInfoEntity;
import com.lambda.fusion.core.identity.UserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户服务层接口
 */
public interface UserService {

    /**
     * 根据角色查询用户名集合
     *
     * @param orgId     组织编号
     * @param authority 角色
     * @return 用户集合
     */
    List<String> getUserNamesByAuthority(String orgId, @NotBlank String authority);

    /**
     * 根据组织机构id查询用户名集合
     *
     * @param orgId 组织机构id
     * @param type  机构类型
     * @return
     */
    List<String> getUserNamesByOrgId(@NotBlank String orgId, Integer type);

    /**
     * 查询所有用户信息
     *
     * @param
     **/
    List<User> getAllUsers();

    /***
     * 根据username查询用户详情
     *
     * @param username 用户名
     */
    User getByUsername(String username);

    /***
     * 获取当前用户得详情
     */
    User getCurrentUser(UserDetails userDetails);

    /***
     * 评估密码时效：有效期策略 + 豁免角色 + 最近一次密码变更日志
     * <p>密码过期时同步落库 password_reset_required 标记</p>
     *
     * @param userDetails 当前登录用户
     * @return 密码时效评估结果
     */
    PasswordExpiry evaluatePasswordExpiry(UserDetails userDetails);

    /***
     * 查询注册用户列表
     *
     * @param users 用户列表
     */
    List<User> getUsers(List<User> users);

    /***
     * 查询注册用户列表
     *
     * @param pageable   分页信息
     * @param parameters 参数信息
     */
    Page<User> getUsers(Page<User> pageable, UserQueryContext parameters);

    /***
     * 根据关键字模糊查询用户列表
     *
     * @param key 关键字
     */
    List<User> getUsersByKey(String key);

    /**
     * 保存用户
     *
     * @param user     保存对象
     * @param operator 当前操作人
     */
    void addUser(CreateUser user, UserDetails operator);

    /***
     * 更新用户
     *
     * @param user     更新对象
     * @param operator 当前操作人
     */
    void updateUser(UpdateUser user, UserDetails operator);

    /***
     * 根据用户名删除用户
     *
     * @param operator
     * @param username 用户名
     */
    void deleteUser(UserDetails operator, String username);

    /***
     * 检查用户名是否已经存在
     *
     * @param username 用户名
     * @return boolean
     */
    boolean checkUserName(String username);

    /***
     * 修改用户密码
     *
     * @param username    用户名
     * @param oldpassword 原密码
     * @param newpassword 新密码
     */
    void updateUserPassword(String username, String oldpassword, String newpassword);

    /**
     * 重置密码
     *
     * @param pwdParameter
     * @return java.lang.String
     */
    String resetUserPassword(ResetPassword pwdParameter);

    /**
     * 禁用/启用用户帐号
     *
     * @param operator
     * @param type
     * @param username
     */
    void deactivateUser(UserDetails operator, Integer type, String username);

    /**
     * 解锁用户
     *
     * @param username
     * @param operator
     */
    void unlockUser(String username, UserDetails operator);

    /**
     * 根据组织编号查询用户列表
     *
     * @param orgid  组织编号
     * @param roleid 角色编号
     * @return
     */
    List<String> getUsernamesByOrgId(String orgid, String roleid);

    /**
     * 获取用户的权限列表
     *
     * @param operator
     * @param source
     */
    Set<String> getPermissions(UserDetails operator, String source);

    /**
     * 批量保存用户的权限
     *
     * @param operator
     * @param username
     * @param permissions
     * @return void
     */
    void batchSavePermissions(UserDetails operator, String username, Set<String> permissions);

    /**
     * 查询用户所有权限
     *
     * @param username
     * @param mode
     * @param application 所属应用ID，null 表示全部应用
     */
    List<Permission> getUserPermissions(String username, String mode, String application);

    /**
     * 获取所有用户下拉数据
     *
     * @param operator
     * @param orgIds
     * @return
     */
    List<UserProfile> getUserProfiles(UserDetails operator, List<String> orgIds);

    /**
     * 当前用户组织机构数据权限
     *
     * @param orgId    orgId
     * @param operator operator
     * @return 返回组织机构集合
     */
    Set<String> getSubOrganizationIds(String orgId, UserDetails operator);

    /**
     * 根据用户名称列表查询用户的扩展属性
     *
     * @param names 用户名称列表
     * @return Map<String, MutableUser>
     */
    Map<String, UserInfoEntity> getUserProps(Set<String> names);

    /**
     * 获取上级领导集合
     *
     * @param uid  uid
     * @param rank 机构层级
     * @return
     */
    List<String> getSuperiors(String uid, Integer rank);

    /**
     * 增加用户新增字段信息
     *
     * @param personal 字段map
     * @param username 用户id
     */
    void addUserFields(Map<String, Object> personal, String username);

    /***
     * 根据租户ID查询租户管理员列表
     *
     * @param tenantId 租户ID
     */
    List<User> queryTenantAdmins(String tenantId);

    /***
     * 更新租户管理员用户
     *
     * @param user     更新对象
     * @param operator 当前操作人
     */
    void updateTenantUser(@Valid User user, UserDetails operator);

    /**
     * 导出用户列表
     *
     * @param pageable   分页
     * @param parameters 查询参数
     */
    void exportUsers(Page<User> pageable, UserQueryContext parameters);
}
