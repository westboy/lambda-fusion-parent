package com.lambda.fusion.datasource.util;

import cn.hutool.db.dialect.DriverUtil;
import com.lambda.cloud.datasource.property.DataSourceProperty;
import com.lambda.fusion.core.convert.FusionConvertFunctions;
import com.lambda.fusion.datasource.model.DataSourceEntity;
import com.lambda.fusion.datasource.model.DynamicDataSourceProperty;
import com.lambda.fusion.datasource.model.RemoteDataSource;

public class DataSourcePropertyUtils {

    public static DataSourceProperty getDataSourceProperty(DataSourceEntity entity) {
        DynamicDataSourceProperty property = new DynamicDataSourceProperty();
        property.setPoolName(entity.getDatasourceName());
        property.setId(entity.getId());
        property.setUrl(entity.getJdbcUrl());
        property.setUsername(entity.getUsername());
        property.setPassword(FusionConvertFunctions.mapDecodePassword(entity.getPassword()));
        String driver = DriverUtil.identifyDriver(entity.getJdbcUrl());
        property.setDriverClassName(driver);
        return property;
    }

    public static DataSourceProperty getDataSourceProperty(RemoteDataSource remoteDataSource) {
        DynamicDataSourceProperty property = new DynamicDataSourceProperty();
        property.setPoolName(remoteDataSource.getDatasourceName());
        property.setId(remoteDataSource.getId());
        property.setUrl(remoteDataSource.getJdbcUrl());
        property.setUsername(remoteDataSource.getUsername());
        property.setPassword(FusionConvertFunctions.mapDecodePassword(remoteDataSource.getPassword()));
        String driver = DriverUtil.identifyDriver(remoteDataSource.getJdbcUrl());
        property.setDriverClassName(driver);
        return property;
    }
}
