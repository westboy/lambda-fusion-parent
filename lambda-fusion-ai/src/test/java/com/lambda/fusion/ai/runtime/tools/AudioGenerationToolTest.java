package com.lambda.fusion.ai.runtime.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

class AudioGenerationToolTest {

    @Test
    void shouldPassThroughOfficialAudioBlockAndUseProvidedParameters() {
        DashScopeMultiModalTool client = Mockito.mock(DashScopeMultiModalTool.class);
        ToolResultBlock official = ToolResultBlock.of(AudioBlock.builder()
                .source(URLSource.builder()
                        .url("https://audio.example.com/speech.mp3")
                        .build())
                .build());
        when(client.dashscopeTextToAudio("用户输入", "qwen3-tts-flash", "Cherry", "Chinese", 24000))
                .thenReturn(Mono.just(official));
        ToolResultBlock result = new AudioGenerationTool(client, "qwen3-tts-flash")
                .generateAudio("用户输入", "Cherry", "Chinese", 24000)
                .block(Duration.ofSeconds(5));

        assertThat(result).isSameAs(official);
        assertThat(result.getOutput()).singleElement().isInstanceOf(AudioBlock.class);
        verify(client).dashscopeTextToAudio("用户输入", "qwen3-tts-flash", "Cherry", "Chinese", 24000);
    }
}
