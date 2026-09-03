package com.lambda.fusion.authority.application.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lambda.fusion.core.entity.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@TableName("la_applications")
@Schema(description = "应用信息")
public class ApplicationEntity extends BaseEntity {

    /**
     * 应用ID
     */
    @TableId(value = "ID", type = IdType.ASSIGN_ID)
    @Schema(description = "应用ID")
    private String id;

    /**
     * 应用名称
     */
    @Schema(description = "应用名称")
    @TableField("NAME")
    private String name;

    /**
     * 对应的 Spring application.name，用于与运行服务及接口权限上报关联
     */
    @Schema(description = "Spring应用名(spring.application.name)")
    @TableField("SPRING_APPLICATION_NAME")
    private String springApplicationName;

    /**
     * 服务密钥，用于接口权限上报等场景的服务间校验
     */
    @Schema(description = "服务密钥")
    @TableField("SECRET")
    private String secret;

    /**
     * 应用描述
     */
    @Schema(description = "应用描述")
    @TableField("DESCRIPTION")
    private String description;

    /**
     * 是否启用
     */
    @Schema(description = "是否启用")
    @TableField("ENABLED")
    private Boolean enabled;

    /**
     * 是否独立前端：独立前端应用的菜单不并入主控台导航，由对应前端按 application 参数单独获取
     */
    @Schema(description = "是否独立前端")
    @TableField("STANDALONE")
    private Boolean standalone;
}
