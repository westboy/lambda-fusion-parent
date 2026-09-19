package com.lambda.fusion.ai.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 附件正文缓存的预算与淘汰测试（Caffeine 加权淘汰，只断言收敛不断言具体淘汰键）。 */
class ChatAttachmentTextCacheTest {

    @Test
    void shouldConvergeWithinTotalWeightBudget() {
        ChatAttachmentTextCache cache = new ChatAttachmentTextCache();
        String fourM = "x".repeat(4_000_000);
        List<String> keys = List.of("a", "b", "c", "d", "e");

        for (String key : keys) {
            cache.put(key, fourM);
        }
        cache.cleanUp();

        // 总字符 20M 超预算 16M：维护后必有条目被淘汰（TinyLFU 决定淘汰谁）。
        long present = keys.stream().filter(key -> cache.get(key) != null).count();
        assertThat(present).isLessThan(keys.size());
    }

    @Test
    void shouldNotCacheOversizedEntry() {
        ChatAttachmentTextCache cache = new ChatAttachmentTextCache();
        cache.put("big", "x".repeat(4_000_001));

        assertThat(cache.get("big")).isNull();
    }

    @Test
    void shouldReplaceSameKeyWithoutDoubleCounting() {
        ChatAttachmentTextCache cache = new ChatAttachmentTextCache();
        cache.put("k", "first");
        cache.put("k", "second");

        assertThat(cache.get("k")).isEqualTo("second");
    }
}
