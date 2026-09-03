package com.lambda.fusion.authority.application.model;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用新增/更新参数
 */
@AutoConverter(target = ApplicationEntity.class)
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "应用信息")
public class UpsertApplication extends BaseDTO<ApplicationEntity> {

    /**
     * 应用名称
     */
    @Schema(description = "应用名称")
    @NotBlank
    @Size(max = 64)
    private String name;

    /**
     * 对应的 Spring application.name
     */
    @Schema(description = "Spring应用名(spring.application.name)")
    @NotBlank
    @Size(max = 128)
    private String springApplicationName;

    /**
     * 应用描述
     */
    @Schema(description = "应用描述")
    @Size(max = 500)
    private String description;

    /**
     * 是否启用
     */
    @Schema(description = "是否启用")
    private Boolean enabled;

    /**
     * 是否独立前端：独立前端应用的菜单不并入主控台导航
     */
    @Schema(description = "是否独立前端")
    private Boolean standalone;
}
