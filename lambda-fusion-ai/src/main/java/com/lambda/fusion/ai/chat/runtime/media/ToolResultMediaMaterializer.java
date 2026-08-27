package com.lambda.fusion.ai.chat.runtime.media;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentBlockFactory;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.core.utils.TenantUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 将 AgentScope 工具结果中的 URL/Base64 媒体复制到平台文件存储，并替换为官方 {@link DataBlock}。
 * 阻塞下载和存储在 bounded-elastic 线程执行；转换发生在 Toolkit 返回结果之前，因此事件流、
 * AgentState 与运行快照看到同一个平台附件引用。
 *
 * @author Jin
 */
@Slf4j
@Component
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ToolResultMediaMaterializer {

    public static final String ATTACHMENT_ID_PREFIX = ChatAttachmentBlockFactory.ATTACHMENT_ID_PREFIX;
    private static final int COPY_BUFFER_SIZE = 8192;

    private final ChatAttachmentService attachmentService;
    private final ChatAttachmentBlockFactory attachmentBlockFactory;
    private final AiProperties aiProperties;
    private final CloseableHttpClient httpClient;

    public ToolResultMediaMaterializer(
            ChatAttachmentService attachmentService,
            ChatAttachmentBlockFactory attachmentBlockFactory,
            AiProperties aiProperties) {
        this.attachmentService = attachmentService;
        this.attachmentBlockFactory = attachmentBlockFactory;
        this.aiProperties = aiProperties;
        var generatedMedia = aiProperties.getChat().getAttachment().getGeneratedMedia();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(generatedMedia.getConnectTimeoutSeconds()))
                .build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicDnsResolver())
                .setDefaultConnectionConfig(connectionConfig)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(generatedMedia.getRequestTimeoutSeconds()))
                .setRedirectsEnabled(false)
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }

    @PreDestroy
    public void close() {
        try {
            httpClient.close();
        } catch (IOException e) {
            log.warn("关闭媒体下载客户端失败: {}", e.getMessage());
        }
    }

    /** 异步物化工具结果中的所有媒体块；非媒体结果原样返回。 */
    public Mono<ToolResultBlock> materialize(ToolResultBlock result, Context context, String toolCallId) {
        if (result.getOutput().stream().noneMatch(block -> sourceOfMedia(block) != null)) {
            return Mono.just(result);
        }
        return Flux.fromIterable(result.getOutput())
                .concatMap(block -> materialize(block, context, toolCallId))
                .collectList()
                .map(output -> new ToolResultBlock(
                        result.getId(), result.getName(), output, result.getMetadata(), result.getState()));
    }

    private Mono<ContentBlock> materialize(ContentBlock block, Context context, String toolCallId) {
        Source source = sourceOfMedia(block);
        if (source == null) {
            return Mono.just(block);
        }
        return Mono.fromCallable(() -> TenantUtils.withTenant(
                        context.tenantId(), () -> materializeBlocking(block, source, context, toolCallId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ContentBlock materializeBlocking(ContentBlock original, Source source, Context context, String toolCallId) {
        PreparedMedia prepared = null;
        try {
            prepared = prepare(source, original);
            ChatAttachmentEntity attachment = attachmentService.storeGenerated(
                    context.sessionId(), prepared.path(), prepared.fileName(), prepared.mimeType());
            return attachmentBlockFactory.create(attachment);
        } catch (AiBusinessException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("工具结果媒体物化失败: runId={}, toolCallId={}, error={}", context.runId(), toolCallId, e.getMessage());
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
        HttpGet request = new HttpGet(uri);
        request.setHeader(HttpHeaders.ACCEPT, "*/*");
        request.setHeader(HttpHeaders.USER_AGENT, "lambda-fusion-ai/1.0");
        DownloadOutcome outcome = httpClient.execute(request, response -> handleResponse(uri, source, block, response));
        if (outcome.redirect() != null) {
            if (redirectCount >= maxRedirects) {
                throw new IOException("媒体重定向次数超限");
            }
            return downloadRemote(outcome.redirect(), source, block, redirectCount + 1);
        }
        return outcome.media();
    }

    private DownloadOutcome handleResponse(
            URI requestUri, URLSource source, ContentBlock block, org.apache.hc.core5.http.ClassicHttpResponse response)
            throws IOException {
        int status = response.getCode();
        if (status >= 300 && status < 400) {
            var location = response.getFirstHeader(HttpHeaders.LOCATION);
            if (location == null || StringUtils.isBlank(location.getValue())) {
                throw new IOException("媒体重定向缺少Location");
            }
            return DownloadOutcome.redirect(requestUri.resolve(location.getValue()));
        }
        if (status < 200 || status >= 300) {
            throw new IOException("媒体下载HTTP状态异常: " + status);
        }
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            throw new IOException("媒体下载响应为空");
        }
        String fileName = suggestedName(block, fileNameFromUri(requestUri));
        if (entity.getContentLength() >= 0) {
            ensureWithinLimit(entity.getContentLength(), fileName);
        }
        Path temp = Files.createTempFile("tool-media-", ".bin");
        try (InputStream input = entity.getContent();
                OutputStream output = Files.newOutputStream(temp)) {
            copyBounded(input, output, fileName);
            return DownloadOutcome.media(new PreparedMedia(
                    temp, true, fileName, normalizeMimeType(source.getMimeType(), entity.getContentType())));
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
    }

    private static boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            boolean uniqueLocal = (bytes[0] & 0xfe) == 0xfc;
            boolean documentation =
                    bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && (bytes[3] & 0xff) == 0xb8;
            return uniqueLocal || documentation;
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        return first == 0
                || first == 10
                || first == 127
                || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 0 && (third == 0 || third == 2))
                || (first == 192 && second == 88 && third == 99)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113);
    }

    private static Source sourceOfMedia(ContentBlock block) {
        return switch (block) {
            case ImageBlock image -> image.getSource();
            case VideoBlock video -> video.getSource();
            case AudioBlock audio -> audio.getSource();
            case DataBlock data when !StringUtils.startsWith(data.getId(), ATTACHMENT_ID_PREFIX) -> data.getSource();
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

    private record DownloadOutcome(URI redirect, PreparedMedia media) {

        private static DownloadOutcome redirect(URI uri) {
            return new DownloadOutcome(uri, null);
        }

        private static DownloadOutcome media(PreparedMedia media) {
            return new DownloadOutcome(null, media);
        }
    }

    private record PreparedMedia(Path path, boolean deleteAfterUse, String fileName, String mimeType) {}

    /** DNS 在建立连接时解析并校验，连接使用的就是本次返回地址，不存在预校验后的二次解析窗口。 */
    private static final class PublicDnsResolver implements DnsResolver {

        private final DnsResolver delegate = SystemDefaultDnsResolver.INSTANCE;

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = delegate.resolve(host);
            for (InetAddress address : addresses) {
                if (isNonPublicAddress(address)) {
                    throw new UnknownHostException("媒体地址解析到非公网IP: " + host);
                }
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return delegate.resolveCanonicalHostname(host);
        }
    }
}
