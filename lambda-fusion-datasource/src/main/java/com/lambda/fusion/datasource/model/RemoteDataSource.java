package com.lambda.fusion.datasource.model;

import com.lambda.cloud.core.annotation.AutoConverter;
import com.lambda.fusion.datasource.DatasourceConstants;
import java.io.Serial;
import java.io.Serializable;
import lombok.Getter;
import lombok.Setter;

/**
 * 远程数据源传输对象
 */
@AutoConverter(target = UpsertDataSource.class)
@Getter
@Setter
public class RemoteDataSource implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String tenantId;
    private String datasourceName;
    private String dbType;
    private String jdbcUrl;
    private String username;
    private String password;
    private String nodeRole;
    private DatasourceConstants.DatasourceStatus status;
    private String extraConfig;
    private long version;
}
