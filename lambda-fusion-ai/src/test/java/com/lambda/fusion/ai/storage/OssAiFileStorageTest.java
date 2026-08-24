package com.lambda.fusion.ai.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lambda.cloud.oss.client.OssClient;
import com.lambda.cloud.oss.manager.OssClientManager;
import com.lambda.fusion.ai.AiProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class OssAiFileStorageTest {

    @Test
    void shouldStoreWithConfiguredPrefixAndCreateTemporaryUrl(@TempDir Path tempDir) throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<OssClientManager> provider = mock(ObjectProvider.class);
        OssClientManager manager = mock(OssClientManager.class);
        OssClient client = mock(OssClient.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        when(manager.isEmpty()).thenReturn(false);
        when(manager.getDefault()).thenReturn(client);
        when(client.getPrivateUrl("ai/knowledge/chat/session/media.mp4", 300)).thenReturn("https://oss.example/signed");
        AiProperties properties = new AiProperties();
        OssAiFileStorage storage = new OssAiFileStorage(provider, properties);
        Path source = tempDir.resolve("media.mp4");
        Files.write(source, new byte[] {1, 2, 3});

        String path = storage.store(source, "chat/session/media.mp4", "video/mp4");

        assertThat(path).isEqualTo("ai/knowledge/chat/session/media.mp4");
        verify(client).upload(source.toFile(), path);
        assertThat(storage.temporaryReadUrl(path, Duration.ofSeconds(300))).contains("https://oss.example/signed");
    }
}
