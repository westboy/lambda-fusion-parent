package com.lambda.fusion.datasource.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.cloud.core.annotation.FieldMapping;
import com.lambda.fusion.core.convert.FusionConvertFunctions;
import com.lambda.fusion.core.entity.BaseEntity;
import com.lambda.fusion.datasource.DatasourceConstants;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * create table la_datasources
 * (
 * id               varchar(32)  not null primary key comment '资源ID',
 * datasource_name     varchar(128) not null comment '数据源名称（展示用）',
 * db_type          varchar(32)  not null comment 'mysql pg oracle',
 * jdbc_url         varchar(256) not null,
 * username         varchar(128) null,
 * password         varchar(512) null,
 * node_role        varchar(32)  default 'PRIMARY' comment 'PRIMARY 主库, REPLICA 从库',
 * status           tinyint      default 1 comment '0下线 1在线',
 * extra_config     json         null comment '扩展配置',
 * created_by       varchar(32),
 * created_at       datetime,
 * updated_by       varchar(32),
 * updated_at       datetime
 * )
 * comment '数据库资源池';
 */
@Setter
@Getter
@AutoConverter(target = RemoteDataSource.class, uses = FusionConvertFunctions.class)
@TableName("la_datasources")
@Schema(description = "动态数据源")
public class DataSourceEntity extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    @Schema(description = "数据源编号")
    private String id;

    @TableField("datasource_name")
    @Schema(description = "数据源名称")
    private String datasourceName;

    @TableField("db_type")
    @Schema(description = "数据库类型")
    private String dbType;

    @TableField("jdbc_url")
    @Schema(description = "连接地址")
    private String jdbcUrl;

    @TableField("username")
    @Schema(description = "用户名")
    private String username;

    @FieldMapping(target = "password", qualifiedByName = "mapEncodePassword")
    @Hidden
    @JsonIgnore
    @TableField("password")
    private String password;

    @TableField("node_role")
    @Schema(description = "节点角色")
    private String nodeRole;

    @TableField("status")
    @Schema(description = "状态")
    private DatasourceConstants.DatasourceStatus status;

    @TableField("extra_config")
    @Schema(description = "扩展配置")
    private String extraConfig;
}
