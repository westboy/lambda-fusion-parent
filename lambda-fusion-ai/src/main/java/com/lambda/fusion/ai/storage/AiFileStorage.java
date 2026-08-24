package com.lambda.fusion.ai.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * AI 模块文件存储 SPI。知识库原文件、对话附件和工具生成媒体共用该扩展点，内置 LOCAL/OSS 实现。
 *
 * @author Jin
 */
public interface AiFileStorage {

    /** 存储类型标识：LOCAL / OSS。 */
    String type();

    /** 持久化文件并返回本地相对路径或 OSS objectKey。 */
    String store(Path source, String relativeName, String contentType);

    /** 删除文件；文件不存在时不报错。 */
    void delete(String storagePath);

    /** 打开文件读取句柄，调用方必须关闭。 */
    AiFileResource open(String storagePath);

    /**
     * 生成后端临时读取地址。LOCAL 默认不支持；OSS 实现返回预签名地址，浏览器可直接使用 Range 请求。
     */
    default Optional<String> temporaryReadUrl(String storagePath, Duration ttl) {
        return Optional.empty();
    }

    /** 将完整文件写入输出流。 */
    default void download(String storagePath, OutputStream out) {
        try (AiFileResource resource = open(storagePath)) {
            resource.content().transferTo(out);
        } catch (IOException e) {
            throw new IllegalStateException("读取AI文件失败: " + storagePath, e);
        }
    }
}
