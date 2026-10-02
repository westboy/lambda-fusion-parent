package com.lambda.fusion.form.runtime.support;

import com.lambda.fusion.form.FormProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 不可变发布快照的编译结果缓存
 *
 * <p>key 为数据库表单 ID；命中时必须同时匹配调用方已核对的生效版本。
 * 状态不由缓存判断，旧请求回填旧版本最多导致再次编译，不会作为新版本返回。
 *
 * @author Zx
 */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class FormModelCache {

    private final FormProperties properties;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public FormModel get(String formId, long version) {
        Entry entry = cache.get(formId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            cache.remove(formId, entry);
            return null;
        }
        return entry.model().version() == version ? entry.model() : null;
    }

    public void put(String formId, FormModel model) {
        cache.put(formId, new Entry(model, System.currentTimeMillis() + ttlMillis()));
    }

    /**
     * 清除指定表单缓存（发布/停用/回滚后调用）
     */
    public void evict(String formId) {
        cache.remove(formId);
    }

    private long ttlMillis() {
        return Math.max(1, properties.getCacheTtlSeconds()) * 1000L;
    }

    private record Entry(FormModel model, long expiresAt) {}
}
