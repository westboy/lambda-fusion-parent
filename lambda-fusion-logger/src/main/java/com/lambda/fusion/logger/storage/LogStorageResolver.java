package com.lambda.fusion.logger.storage;

import com.lambda.fusion.logger.exception.LoggerBusinessException;
import com.lambda.fusion.logger.exception.LoggerErrorCode;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 按存储类型路由日志存储实现（仿 AI 模块 AiFileStorageResolver）。 */
@Component
public class LogStorageResolver {

    private final List<LogStorage> storages;

    public LogStorageResolver(List<LogStorage> storages) {
        this.storages = List.copyOf(storages);
    }

    public LogStorage resolve(String type) {
        return storages.stream()
                .filter(storage -> storage.type().equalsIgnoreCase(StringUtils.defaultString(type)))
                .findFirst()
                .orElseThrow(() -> new LoggerBusinessException(LoggerErrorCode.LOG_STORAGE_NOT_SUPPORTED, type));
    }
}
