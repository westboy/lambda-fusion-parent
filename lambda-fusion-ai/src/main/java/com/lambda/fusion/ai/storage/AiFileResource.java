package com.lambda.fusion.ai.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 可关闭的 AI 文件读取句柄。
 *
 * @param content 文件内容流
 * @param contentLength 文件总字节数
 * @author Jin
 */
public record AiFileResource(InputStream content, long contentLength) implements AutoCloseable {

    public AiFileResource {
        Objects.requireNonNull(content, "content");
        if (contentLength < 0) {
            throw new IllegalArgumentException("contentLength不能小于0");
        }
    }

    @Override
    public void close() throws IOException {
        content.close();
    }
}
