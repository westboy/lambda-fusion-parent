package com.lambda.fusion.ai.runtime.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentTextExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsLongTextWithoutReaderOverlapDuplication() throws Exception {
        // 明确越过默认 8000 字符内联预算，证明 Reader 层仍返回全文，长短分流只发生在消息组装层。
        String content = "设备通讯协议正文".repeat(2000);
        Path file = tempDir.resolve("protocol.txt");
        Files.writeString(file, content);

        assertThat(DocumentTextExtractor.extractText("txt", file)).isEqualTo(content);
    }
}
