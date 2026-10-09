package com.lambda.fusion.form.design.support;

import com.lambda.fusion.datasource.api.RemoteDataSourceApi;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * 经动态数据源组件远程契约 {@link RemoteDataSourceApi} 枚举已启用数据源（工程契约 §7.3，
 * 不引用实现类）。api 为 null（容器无本地实现且 Dubbo 缺席）时降级为空清单，不阻断设计器。
 *
 * @author Zx
 */
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class RemoteDataSourceCatalog implements DataSourceCatalog {

    /** 动态数据源远程契约（可 null：组件/契约缺席降级） */
    private final RemoteDataSourceApi remoteApi;

    @Override
    public List<Option> listEnabled() {
        if (remoteApi == null) {
            return List.of();
        }
        return remoteApi.listEnabled().stream()
                .map(source -> new Option(source.getId(), source.getDatasourceName(), source.getDbType()))
                .toList();
    }
}
