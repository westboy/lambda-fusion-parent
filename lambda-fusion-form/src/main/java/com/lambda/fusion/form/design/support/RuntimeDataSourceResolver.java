package com.lambda.fusion.form.design.support;

import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import javax.sql.DataSource;

/**
 * 按数据源注册 id（la_datasources.ID）解析运行时 DataSource 的端口，设计期元数据读取与
 * 发布校验共用。空 id 表示平台主库，由调用方直用注入的主 {@link DataSource}，不经过本端口；
 * 动态数据源组件缺席时解析端口不装配，传入非空 id 即报错（设计器下拉同步降级为空清单）。
 *
 * <p>切换只发生在取连接这一步，与事务边界的先后关系由调用方保证（§4.1/§5.1：与租户上下文解耦，
 * 仅表单级显式配置，不提供请求期按租户切换）。
 *
 * @author Zx
 */
public interface RuntimeDataSourceResolver {

    /**
     * 解析注册数据源对应的运行时连接池
     *
     * @param datasourceId 数据源注册 id（非空）
     * @return 运行时 DataSource；id 未注册或已停用时抛 {@link FormBusinessException}
     */
    DataSource resolve(String datasourceId);

    /** 端口缺席时的统一报错口径 */
    static FormBusinessException missing() {
        return new FormBusinessException(FormErrorCode.INVALID_PARAMETER, "当前部署未装配动态数据源组件，无法使用平台主库以外的数据源");
    }
}
