package com.lambda.fusion.ai.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.chat.model.MediaGenerationRequest;
import org.junit.jupiter.api.Test;

class MediaGenerationPromptBuilderTest {

    private final MediaGenerationPromptBuilder builder = new MediaGenerationPromptBuilder();

    @Test
    void shouldLeaveOrdinaryMessageWithoutAnAgentPromptOverride() {
        assertThat(builder.build("hello", null)).isNull();
    }

    @Test
    void shouldAppendOnlyMediaIntentAndKeepParametersInTheUserPrompt() {
        MediaGenerationRequest request = new MediaGenerationRequest();
        request.setKind(" IMAGE ");

        String prompt = builder.build("画一只没有水印的猫，比例 16:9", request);

        assertThat(prompt)
                .startsWith("画一只没有水印的猫，比例 16:9")
                .contains("<media_generation_intent kind=\"image\">")
                .contains("工具的 Schema")
                .contains("成功产生媒体后")
                .doesNotContain("provider", "modelId", "ToolChoice", "preview?", "sig=");
    }

    @Test
    void shouldSupportEveryUiMediaKindWithoutSelectingATool() {
        for (String kind : new String[] {"image", "video", "audio"}) {
            MediaGenerationRequest request = new MediaGenerationRequest();
            request.setKind(kind);

            assertThat(builder.build("prompt", request)).contains("kind=\"" + kind + "\"");
        }
    }
}
