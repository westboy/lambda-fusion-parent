package com.lambda.fusion.ai.storage;

import com.lambda.cloud.oss.client.OssClient;
import com.lambda.cloud.oss.manager.OssClientManager;
import com.lambda.cloud.oss.model.OssObject;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;

/** 基于 lambda-cloud-starter-oss 的 AI 文件存储。 */
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class OssAiFileStorage implements AiFileStorage {

    public static final String TYPE = "OSS";

    private final ObjectProvider<OssClientManager> ossClientManagerProvider;
    private final AiProperties aiProperties;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String store(Path source, String relativeName, String contentType) {
        String objectKey = keyPrefix() + relativeName;
        // starter 的 InputStream 重载会先完整读入内存；媒体文件使用 File 重载保持有界内存。
        client().upload(source.toFile(), objectKey);
        return objectKey;
    }

    @Override
    public void delete(String storagePath) {
        client().delete(storagePath);
    }

    @Override
    public AiFileResource open(String storagePath) {
        OssObject object = client().getObject(storagePath);
        return new AiFileResource(object.getContent(), object.getContentLength());
    }

    @Override
    public Optional<String> temporaryReadUrl(String storagePath, Duration ttl) {
        long seconds = Math.max(1, ttl.toSeconds());
        int expiration = Math.toIntExact(Math.min(seconds, Integer.MAX_VALUE));
        return Optional.of(client().getPrivateUrl(storagePath, expiration));
    }

    private String keyPrefix() {
        return aiProperties.getRag().getDocumentStorage().getOss().getKeyPrefix();
    }

    private OssClient client() {
        OssClientManager manager = ossClientManagerProvider.getIfAvailable();
        if (manager == null || manager.isEmpty()) {
            throw new AiBusinessException(
                    AiErrorCode.CONFIGURATION_ERROR, "OSS客户端未配置(lambda.oss.clients)，请配置OSS客户端或改用LOCAL");
        }
        String clientName = aiProperties.getRag().getDocumentStorage().getOss().getClientName();
        return StringUtils.isBlank(clientName) ? manager.getDefault() : manager.get(clientName);
    }
}
