package com.lambda.fusion.ai.chat.attachment;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 暂存附件全文的进程内缓存。附件暂存（stage）时正文已在内存，顺势入缓存；{@code read_chat_attachment}
 * 的后续分页读取命中缓存，未命中才回源下载——此前每页都完整下载一次，N 页读取产生 O(N²) 流量。
 * 按总字符预算做 LRU 淘汰；附件内容不可变，缓存无需失效。缓存只是读取加速，不是事实来源。
 *
 * @author Jin
 */
@Slf4j
@Component
public class ChatAttachmentTextCache {

    /** 缓存正文总字符预算（约 32MB 堆）。 */
    private static final int MAX_TOTAL_CHARS = 16_000_000;
    /** 单条缓存的最大字符数；超过则不缓存，回源分段下载。 */
    private static final int MAX_ENTRY_CHARS = 4_000_000;

    private final Map<String, String> cache = new LinkedHashMap<>(64, 0.75f, true);
    private int totalChars;

    /** 读取缓存；未命中返回 {@code null}。 */
    public synchronized String get(String key) {
        return cache.get(key);
    }

    /** 写入缓存；超过单条上限不缓存，超出总预算时按最久未访问淘汰。 */
    public synchronized void put(String key, String text) {
        if (text == null || text.length() > MAX_ENTRY_CHARS) {
            return;
        }
        String previous = cache.put(key, text);
        if (previous != null) {
            totalChars -= previous.length();
        }
        totalChars += text.length();
        while (totalChars > MAX_TOTAL_CHARS && !cache.isEmpty()) {
            Map.Entry<String, String> eldest = cache.entrySet().iterator().next();
            totalChars -= eldest.getValue().length();
            cache.remove(eldest.getKey());
        }
    }

    /** 命中直接返回；未命中回源加载并写入缓存（并发下允许重复回源，结果一致）。 */
    public String getOrLoad(String key, Supplier<String> loader) {
        String cached = get(key);
        if (cached != null) {
            return cached;
        }
        String loaded = loader.get();
        put(key, loaded);
        return loaded;
    }
}
