package com.lambda.fusion.ai.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 附件正文缓存的预算与淘汰测试。 */
class ChatAttachmentTextCacheTest {

    @Test
    void shouldEvictLeastRecentlyAccessedWhenTotalBudgetExceeded() {
        ChatAttachmentTextCache cache = new ChatAttachmentTextCache();
        String fourM = "x".repeat(4_000_000);
        cache.put("a", fourM);
        cache.put("b", fourM);
        cache.put("c", fourM);
        cache.put("d", fourM);
        // 触活 a，使其成为最近使用。
        assertThat(cache.get("a")).isNotNull();

        cache.put("e", fourM);

        // 总字符 20M 超预算 16M：淘汰最久未访问的 b，其余保留。
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("a")).isNotNull();
        assertThat(cache.get("e")).isNotNull();
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
