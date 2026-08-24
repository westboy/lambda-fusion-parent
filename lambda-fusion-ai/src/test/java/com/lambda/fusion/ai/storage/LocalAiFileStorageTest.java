package com.lambda.fusion.ai.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 LOCAL AI 文件存储的读写删除与路径边界。 */
class LocalAiFileStorageTest {

    @Test
    void storeDownloadDeleteRoundTrip(@TempDir Path tempDir) throws Exception {
        AiProperties props = new AiProperties();
        props.getRag().getDocumentStorage().getLocal().setRoot(tempDir.toString());
        LocalAiFileStorage storage = new LocalAiFileStorage(props);
        Path source = Files.createTempFile("kb-src-", ".txt");
        try {
            Files.writeString(source, "知识库文档内容", StandardCharsets.UTF_8);

            String storagePath = storage.store(source, "t1/kb1/doc1.txt", "text/plain");

            assertThat(storagePath).isEqualTo("t1/kb1/doc1.txt");
            Path stored = tempDir.resolve("t1/kb1/doc1.txt");
            assertThat(stored).exists();
            try (AiFileResource resource = storage.open(storagePath)) {
                assertThat(resource.contentLength()).isEqualTo(Files.size(source));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            storage.download(storagePath, out);
            assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("知识库文档内容");

            storage.delete(storagePath);
            assertThat(stored).doesNotExist();
            assertThatCode(() -> storage.delete(storagePath)).doesNotThrowAnyException();
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void rootFallsBackToWorkspaceRoot(@TempDir Path tempDir) throws Exception {
        AiProperties props = new AiProperties();
        props.getWorkspace().setRoot(tempDir.resolve("ws").toString());
        LocalAiFileStorage storage = new LocalAiFileStorage(props);
        Path source = Files.createTempFile("kb-src-", ".md");
        try {
            Files.writeString(source, "# md", StandardCharsets.UTF_8);
            String storagePath = storage.store(source, "t1/kb2/doc2.md", "text/markdown");
            assertThat(tempDir.resolve("ws/knowledge-files").resolve(storagePath))
                    .exists();
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void rejectsPathOutsideRoot(@TempDir Path tempDir) {
        AiProperties props = new AiProperties();
        props.getRag().getDocumentStorage().getLocal().setRoot(tempDir.toString());
        LocalAiFileStorage storage = new LocalAiFileStorage(props);
        assertThatThrownBy(() -> storage.open("../secret.txt"))
                .isInstanceOfSatisfying(AiBusinessException.class, error -> assertThat(error.getCode())
                        .isEqualTo(AiErrorCode.INVALID_PARAMETER.getCode()));
    }
}
