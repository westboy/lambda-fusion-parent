package com.lambda.fusion.ai.storage;

import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 按存储类型路由模块级 AI 文件存储实现。 */
@Component
public class AiFileStorageResolver {

    private final List<AiFileStorage> storages;

    public AiFileStorageResolver(List<AiFileStorage> storages) {
        this.storages = List.copyOf(storages);
    }

    public AiFileStorage resolve(String type) {
        return storages.stream()
                .filter(storage -> storage.type().equalsIgnoreCase(StringUtils.defaultString(type)))
                .findFirst()
                .orElseThrow(() -> new AiBusinessException(AiErrorCode.DOCUMENT_STORAGE_NOT_SUPPORTED, type));
    }
}
