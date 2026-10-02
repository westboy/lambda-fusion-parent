package com.lambda.fusion.permission.service;

import com.lambda.fusion.permission.model.ApiPermissionMetadata;
import java.util.List;

/** 请求期接口目录扩展；实现负责按当前调用上下文读取动态业务定义，不进入跨租户静态上报缓存。 */
public interface ApiPermissionProvider {
    List<ApiPermissionMetadata> getApis();
}
