package com.lambda.fusion.authority.authentication.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/**
 * 导航查询数据传输对象
 * 用于封装导航菜单查询的参数
 */
@Data
@Schema(description = "导航查询参数")
@SuppressFBWarnings("EI_EXPOSE_REP")
public class MenuQuery {

    /**
     * 父级菜单ID
     */
    @Schema(description = "父级菜单ID")
    private String parentId;

    /**
     * 菜单层级
     */
    @Schema(description = "菜单层级")
    private Integer level;

    /**
     * 资源模式
     * 0: 系统资源（后台管理）
     * 1: App资源（移动端）
     */
    @Schema(description = "资源模式(0:系统资源,1:App资源)")
    private Integer mode = 0;

    /**
     * 所属应用ID；不传时下发主控台菜单（排除独立前端应用），独立前端传入自身应用ID以获取专属菜单
     */
    @Schema(description = "所属应用ID")
    private String application;

    /**
     * 模型类型
     */
    @Schema(description = "模型类型")
    private String model;

    /**
     * 资源名称
     */
    @Schema(description = "资源名称")
    private String name;

    /**
     * 是否查询所有
     */
    @Schema(description = "是否查询所有")
    private Boolean all;

    /**
     * 指定的资源ID列表
     */
    @Schema(description = "指定的资源ID列表")
    private List<String> ids;
}
