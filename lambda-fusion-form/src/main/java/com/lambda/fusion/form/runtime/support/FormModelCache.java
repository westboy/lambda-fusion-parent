package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 表单模型读穿缓存
 *
 * <p>key = tenantId + formKey，值为已编译的 {@link FormModel}；版本变化由调用方传入新模型替换。
 * 无 Dubbo 环境下失效经 TTL 降级（契约 §4.1），发布/停用/回滚时本节点主动清除。
 *
 * @author Zx
 */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormModelCache {

    private final FormProperties properties;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public FormModel get(String tenantId, String formKey) {
        Entry entry = cache.get(key(tenantId, formKey));
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            cache.remove(key(tenantId, formKey));
            return null;
        }
        return entry.model();
    }

    public void put(String tenantId, String formKey, FormModel model) {
        cache.put(key(tenantId, formKey), new Entry(model, System.currentTimeMillis() + ttlMillis()));
    }

    /**
     * 清除指定表单缓存（发布/停用/回滚后调用）
     */
    public void evict(String tenantId, String formKey) {
        cache.remove(key(tenantId, formKey));
    }

    private long ttlMillis() {
        return Math.max(1, properties.getCacheTtlSeconds()) * 1000L;
    }

    private String key(String tenantId, String formKey) {
        return (tenantId == null ? "" : tenantId) + ':' + formKey;
    }

    private record Entry(FormModel model, long expiresAt) {}
}
