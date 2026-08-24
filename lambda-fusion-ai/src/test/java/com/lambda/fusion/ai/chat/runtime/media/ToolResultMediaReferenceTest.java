package com.lambda.fusion.ai.chat.runtime.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.chat.runtime.snapshot.ChatRunSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolResultMediaReferenceTest {

    @Test
    void shouldRestoreOnlyPlatformAttachmentReferencesFromSnapshot() {
        String result =
                """
                {"schema":"lambda-fusion/tool-result","version":1,"text":"",
                 "content":[
                   {"type":"data","id":"attachment:123","source":{"type":"url","url":"/preview"}},
                   {"type":"data","id":"provider-id","source":{"type":"url","url":"https://example.com"}},
                   {"type":"data","id":"attachment:456","source":{"type":"url","url":"/preview"}}
                 ]}
                """;
        ChatRunSnapshot snapshot = new ChatRunSnapshot(
                "run-1",
                "agui-1",
                1,
                "",
                "",
                null,
                null,
                false,
                false,
                List.of(new ChatRunSnapshot.ToolCall("tool-1", "generate", "{}", result, "complete")),
                List.of(),
                List.of());

        assertThat(ToolResultMediaReference.attachmentIds(snapshot)).containsExactly("123", "456");
    }
}
