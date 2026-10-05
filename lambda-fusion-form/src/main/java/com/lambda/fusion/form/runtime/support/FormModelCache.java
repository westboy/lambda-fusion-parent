package com.lambda.fusion.form.runtime.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 不可变发布快照的编译结果缓存
 *
 * <p>key 为数据库表单 ID；命中时必须同时匹配调用方已核对的生效版本。
 * 状态与版本每次从数据库核对，旧版本条目永远不会被误服务，
 * 发布/停用/回滚/删除后由 {@link FormModelProvider#evictAfterCommit} 释放内存。
 *
 * @author Zx
 */
@Component
public class FormModelCache {

    private final Map<String, FormModel> cache = new ConcurrentHashMap<>();

    public FormModel get(String formId, long version) {
        FormModel model = cache.get(formId);
        return model != null && model.version() == version ? model : null;
    }

    public void put(String formId, FormModel model) {
        cache.put(formId, model);
    }

    /**
     * 清除指定表单缓存（发布/停用/回滚/删除后调用）
     */
    public void evict(String formId) {
        cache.remove(formId);
    }
}
