package com.lambda.fusion.authority.user.model;

import com.lambda.fusion.core.pagination.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询DTO
 *
 * @author lambda
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用户分页查询参数")
public class UserQuery extends PageQuery<User> {

    @Schema(description = "用户名称")
    private String username;

    @Schema(description = "用户昵称")
    private String nickname;

    @Schema(description = "关键字（同时模糊匹配用户名/昵称/手机号/邮箱）")
    private String keyword;

    @Schema(description = "角色名称")
    private String authority;

    @Schema(description = "电话号码")
    private String mobile;

    @Schema(description = "电子邮箱")
    private String email;

    @Schema(description = "组织ID")
    private String organizationId;

    @Schema(description = "是否查询下级组织的人员", defaultValue = "true")
    private Boolean includeChildren = true;

    @Schema(description = "是否是分配人员接口调用", defaultValue = "false")
    private Boolean allocation = false;

    @Schema(description = "新增查询字段")
    private String personal;

    @Schema(description = "是否在线")
    private Boolean isOnline;

    @Schema(description = "是否导出")
    private Boolean isExport;

    @Schema(description = "导出列")
    private String exportColumns;

    @Schema(description = "是否开启数据权限", defaultValue = "true")
    private Boolean enableDataPermission = true;
}
