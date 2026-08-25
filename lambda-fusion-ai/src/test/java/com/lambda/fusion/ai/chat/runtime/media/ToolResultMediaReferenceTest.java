package com.lambda.fusion.ai.chat.runtime.media;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.URLSource;
import org.junit.jupiter.api.Test;

class ToolResultMediaReferenceTest {

    @Test
    void shouldExtractOnlyPlatformAttachmentReferenceFromLiveEvent() {
        ToolResultDataDeltaEvent platformEvent = event("attachment:123");
        ToolResultDataDeltaEvent providerEvent = event("provider-id");

        assertThat(ToolResultMediaReference.attachmentId(platformEvent)).contains("123");
        assertThat(ToolResultMediaReference.attachmentId(providerEvent)).isEmpty();
    }

    private static ToolResultDataDeltaEvent event(String dataId) {
        DataBlock data = DataBlock.builder()
                .id(dataId)
                .source(new URLSource("/preview", "image/png"))
                .build();
        return new ToolResultDataDeltaEvent("reply-1", "tool-1", "text_to_image", data);
    }
}
