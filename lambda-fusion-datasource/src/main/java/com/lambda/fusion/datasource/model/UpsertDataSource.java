package com.lambda.fusion.datasource.model;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.shared.BaseDTO;
import com.lambda.fusion.datasource.DatasourceConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@AutoConverter(target = DataSourceEntity.class)
@Schema(description = "新增/更新动态数据源")
public class UpsertDataSource extends BaseDTO<DataSourceEntity> {

    @Schema(description = "数据源编号")
    private String id;

    @NotBlank
    @Schema(description = "数据源名称")
    private String datasourceName;

    @NotBlank
    @Schema(description = "数据库类型")
    private String dbType;

    @NotBlank
    @Schema(description = "连接地址")
    private String jdbcUrl;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "节点角色")
    private String nodeRole;

    @Schema(description = "状态")
    private DatasourceConstants.DatasourceStatus status;

    @Schema(description = "扩展配置")
    private String extraConfig;
}
