package com.lambda.fusion.ai.chat.runtime.media;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentPreviewTokenService;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.runtime.tools.AudioGenerationTool;
import com.lambda.fusion.ai.runtime.tools.ImageGenerationTool;
import com.lambda.fusion.ai.runtime.tools.VideoGenerationTool;
import com.lambda.fusion.core.utils.TenantUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 将 AgentScope 工具结果中的 URL/Base64 媒体复制到平台文件存储，并替换为官方 {@link DataBlock}。
 * 阻塞下载和存储在 bounded-elastic 线程执行，{@code concatMap} 调用方负责保持事件顺序。
 *
 * @author Jin
 */
@Slf4j
@Component
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ToolResultMediaMaterializer {

    public static final String ATTACHMENT_ID_PREFIX = "attachment:";
    private static final String AUTHENTICATED_DOWNLOAD_PATH = "/v1/ai/chat/attachments/%s/download";
    private static final int COPY_BUFFER_SIZE = 8192;

    private final ChatAttachmentService attachmentService;
    private final ChatAttachmentPreviewTokenService previewTokenService;
    private final AiProperties aiProperties;
    private final HttpClient httpClient;

    public ToolResultMediaMaterializer(
            ChatAttachmentService attachmentService,
            ChatAttachmentPreviewTokenService previewTokenService,
            AiProperties aiProperties) {
        this.attachmentService = attachmentService;
        this.previewTokenService = previewTokenService;
        this.aiProperties = aiProperties;
        Duration connectTimeout = Duration.ofSeconds(
                aiProperties.getChat().getAttachment().getGeneratedMedia().getConnectTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 异步物化单个工具结果媒体事件；其他事件原样透传。 */
    public Mono<AgentEvent> materialize(AgentEvent event, Context context) {
        if (!(event instanceof ToolResultDataDeltaEvent dataEvent) || sourceOfGeneratedMedia(dataEvent) == null) {
            return Mono.just(event);
        }
        return Mono.fromCallable(
                        () -> TenantUtils.withTenant(context.tenantId(), () -> materializeBlocking(dataEvent, context)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private AgentEvent materializeBlocking(ToolResultDataDeltaEvent event, Context context) {
        PreparedMedia prepared = null;
        try {
            ContentBlock original = event.getData();
            Source source = sourceOfGeneratedMedia(event);
            prepared = prepare(source, original);
            ChatAttachmentEntity attachment = attachmentService.storeGenerated(
                    context.sessionId(), prepared.path(), prepared.fileName(), prepared.mimeType());
            String url = StringUtils.defaultIfBlank(
                    previewTokenService.generatePreviewUrl(attachment),
                    AUTHENTICATED_DOWNLOAD_PATH.formatted(attachment.getId()));
            DataBlock materialized = DataBlock.builder()
                    .id(ATTACHMENT_ID_PREFIX + attachment.getId())
                    .name(attachment.getFileName())
                    .source(new URLSource(url, attachment.getMimeType()))
                    .build();
            ToolResultDataDeltaEvent transformed = new ToolResultDataDeltaEvent(
                    event.getId(),
                    event.getCreatedAt(),
                    event.getReplyId(),
                    event.getToolCallId(),
                    event.getToolCallName(),
                    materialized);
            transformed.withSource(event.getSource()).withMetadata(event.getMetadata());
            return transformed;
        } catch (AiBusinessException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn(
                    "工具结果媒体物化失败: runId={}, toolCallId={}, error={}",
                    context.runId(),
                    event.getToolCallId(),
                    e.getMessage());
            throw new AiBusinessException(AiErrorCode.TOOL_RESULT_MEDIA_MATERIALIZATION_FAILED, e);
        } finally {
            if (prepared != null && prepared.deleteAfterUse()) {
                deleteQuietly(prepared.path());
            }
        }
    }

    private PreparedMedia prepare(Source source, ContentBlock block) throws IOException, InterruptedException {
        if (source instanceof Base64Source base64) {
            return decodeBase64(base64, suggestedName(block, null));
        }
        if (source instanceof URLSource urlSource) {
            URI uri = URI.create(urlSource.getUrl());
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return localFile(uri, urlSource, block);
            }
            return downloadRemote(uri, urlSource, block, 0);
        }
        throw new AiBusinessException(AiErrorCode.TOOL_RESULT_MEDIA_MATERIALIZATION_FAILED, "不支持的媒体来源");
    }

    private PreparedMedia decodeBase64(Base64Source source, String fileName) throws IOException {
        String encoded = source.getData();
        int separator = encoded.indexOf(',');
        if (encoded.startsWith("data:") && separator >= 0) {
            encoded = encoded.substring(separator + 1);
        }
        Path temp = Files.createTempFile("tool-media-", ".bin");
        try (InputStream encodedInput = new ByteArrayInputStream(encoded.getBytes(StandardCharsets.ISO_8859_1));
                InputStream decoded = Base64.getMimeDecoder().wrap(encodedInput);
                OutputStream output = Files.newOutputStream(temp)) {
            copyBounded(decoded, output, fileName);
            return new PreparedMedia(temp, true, fileName, normalizeMimeType(source.getMediaType(), null));
        } catch (RuntimeException | IOException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    private PreparedMedia localFile(URI uri, URLSource source, ContentBlock block) throws IOException {
        Path path = Path.of(uri).toRealPath();
        if (!Files.isRegularFile(path) || !isAllowedLocalPath(path)) {
            throw new AiBusinessException(AiErrorCode.TOOL_RESULT_MEDIA_MATERIALIZATION_FAILED, "本地媒体路径不在允许目录内");
        }
        String fileName = suggestedName(block, path.getFileName().toString());
        ensureWithinLimit(Files.size(path), fileName);
        return new PreparedMedia(
                path, false, fileName, normalizeMimeType(source.getMimeType(), Files.probeContentType(path)));
    }

    private PreparedMedia downloadRemote(URI uri, URLSource source, ContentBlock block, int redirectCount)
            throws IOException, InterruptedException {
        validateRemoteUri(uri);
        int maxRedirects =
                aiProperties.getChat().getAttachment().getGeneratedMedia().getMaxRedirects();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(aiProperties
                        .getChat()
                        .getAttachment()
                        .getGeneratedMedia()
                        .getRequestTimeoutSeconds()))
                .header(HttpHeaders.ACCEPT, "*/*")
                .header(HttpHeaders.USER_AGENT, "lambda-fusion-ai/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            try (InputStream ignored = response.body()) {
                String location = response.headers()
                        .firstValue(HttpHeaders.LOCATION)
                        .orElseThrow(() -> new IOException("媒体重定向缺少Location"));
                if (redirectCount >= maxRedirects) {
                    throw new IOException("媒体重定向次数超限");
                }
                return downloadRemote(uri.resolve(location), source, block, redirectCount + 1);
            }
        }
        if (status < 200 || status >= 300) {
            response.body().close();
            throw new IOException("媒体下载HTTP状态异常: " + status);
        }
        String fileName = suggestedName(block, fileNameFromUri(uri));
        response.headers()
                .firstValueAsLong(HttpHeaders.CONTENT_LENGTH)
                .ifPresent(length -> ensureWithinLimit(length, fileName));
        Path temp = Files.createTempFile("tool-media-", ".bin");
        try (InputStream input = response.body();
                OutputStream output = Files.newOutputStream(temp)) {
            copyBounded(input, output, fileName);
            String responseType =
                    response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null);
            return new PreparedMedia(temp, true, fileName, normalizeMimeType(source.getMimeType(), responseType));
        } catch (RuntimeException | IOException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    private void copyBounded(InputStream input, OutputStream output, String fileName) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        long copied = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            copied += read;
            ensureWithinLimit(copied, fileName);
            output.write(buffer, 0, read);
        }
    }

    private void ensureWithinLimit(long size, String fileName) {
        long maxBytes =
                aiProperties.getChat().getAttachment().getGeneratedMedia().getMaxFileSizeMb() * 1024L * 1024L;
        if (size > maxBytes) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_SIZE_EXCEEDED, fileName);
        }
    }

    private boolean isAllowedLocalPath(Path path) {
        for (Path root : allowedLocalRoots()) {
            if (path.startsWith(root.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    private List<Path> allowedLocalRoots() {
        String workspaceRoot = aiProperties.getWorkspace().getRoot();
        String storageRoot =
                aiProperties.getRag().getDocumentStorage().getLocal().getRoot();
        Path defaultWorkspace = Path.of(System.getProperty("user.home"), ".agentscope", "fusion");
        return List.of(
                Path.of(System.getProperty("java.io.tmpdir")),
                StringUtils.isBlank(workspaceRoot) ? defaultWorkspace : Path.of(workspaceRoot),
                StringUtils.isBlank(storageRoot) ? defaultWorkspace.resolve("knowledge-files") : Path.of(storageRoot));
    }

    private static void validateRemoteUri(URI uri) throws IOException {
        String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || StringUtils.isBlank(uri.getHost())
                || uri.getUserInfo() != null) {
            throw new IOException("仅允许无用户信息的HTTP/HTTPS媒体地址");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (isPrivateAddress(address)) {
                throw new IOException("媒体地址解析到非公网IP");
            }
        }
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 0 || (first == 100 && second >= 64 && second <= 127);
    }

    private static Source sourceOfGeneratedMedia(ToolResultDataDeltaEvent event) {
        ContentBlock block = event.getData();
        if (block == null || event.getToolCallName() == null) {
            return null;
        }
        return switch (event.getToolCallName()) {
            case ImageGenerationTool.TOOL_NAME -> block instanceof ImageBlock image ? image.getSource() : null;
            case VideoGenerationTool.TOOL_NAME -> block instanceof VideoBlock video ? video.getSource() : null;
            case AudioGenerationTool.TOOL_NAME -> block instanceof AudioBlock audio ? audio.getSource() : null;
            default -> null;
        };
    }

    private static String suggestedName(ContentBlock block, String sourceName) {
        if (block instanceof DataBlock data && StringUtils.isNotBlank(data.getName())) {
            return data.getName();
        }
        return StringUtils.defaultIfBlank(sourceName, "generated-media");
    }

    private static String fileNameFromUri(URI uri) {
        String path = StringUtils.defaultString(uri.getPath());
        return StringUtils.substringAfterLast("/" + path, "/");
    }

    private static String normalizeMimeType(String preferred, String fallback) {
        String value = StringUtils.defaultIfBlank(preferred, fallback);
        value = StringUtils.substringBefore(StringUtils.defaultString(value), ";")
                .trim()
                .toLowerCase(Locale.ROOT);
        return StringUtils.defaultIfBlank(value, "application/octet-stream");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除工具结果媒体临时文件失败: {}, {}", path, e.getMessage());
        }
    }

    /** 单次运行的媒体物化上下文。 */
    public record Context(String runId, String sessionId, String tenantId) {}

    private record PreparedMedia(Path path, boolean deleteAfterUse, String fileName, String mimeType) {}
}
