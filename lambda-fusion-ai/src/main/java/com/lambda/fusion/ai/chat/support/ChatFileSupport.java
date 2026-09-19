package com.lambda.fusion.ai.chat.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 对话链路通用文件小工具：文件名清洗、MIME 归一与临时文件静默删除。
 *
 * <p>供附件服务、工作区产物发布与工具结果媒体物化共用，避免各处复制同构实现。
 * 文件名清洗只做分隔符统一、路径段截取与控制字符剔除，裁剪与判空策略由调用方决定。
 */
@Slf4j
public final class ChatFileSupport {

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    /** 平台统一临时目录路径：{java.io.tmpdir}/lambda-fusion；只允许平台自身在此写临时文件。 */
    public static final Path TEMP_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"), "lambda-fusion");

    private ChatFileSupport() {}

    /** 返回平台统一临时目录，不存在时创建；创建失败按存储错误抛出。 */
    public static Path tempDirectory() {
        try {
            return Files.createDirectories(TEMP_DIRECTORY);
        } catch (IOException e) {
            throw new IllegalStateException("创建临时目录失败: " + TEMP_DIRECTORY, e);
        }
    }

    /**
     * 清洗文件名：统一分隔符、截取最后一段路径并剔除控制字符；不裁剪空白、不判空。
     */
    public static String cleanFileName(String fileName) {
        String value = StringUtils.defaultString(fileName).replace('\\', '/');
        return StringUtils.substringAfterLast("/" + value, "/").replaceAll("[\\p{Cntrl}]", "");
    }

    /**
     * 归一 MIME 类型：截断参数段、去除空白并转小写，空值回落 {@code application/octet-stream}。
     */
    public static String normalizeMimeType(String mimeType) {
        String value = StringUtils.substringBefore(StringUtils.defaultString(mimeType), ";")
                .trim()
                .toLowerCase(Locale.ROOT);
        return StringUtils.defaultIfBlank(value, DEFAULT_MIME_TYPE);
    }

    /**
     * 静默删除临时文件：路径为空或删除失败仅记录告警，不向上抛出。
     *
     * @param path 临时文件路径，可为 {@code null}
     * @param description 文件用途描述，用于告警日志定位来源
     */
    public static void deleteQuietly(Path path, String description) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除{}失败: {}, {}", description, path, e.getMessage());
        }
    }
}
