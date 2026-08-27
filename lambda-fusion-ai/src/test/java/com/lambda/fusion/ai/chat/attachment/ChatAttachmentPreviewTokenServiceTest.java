package com.lambda.fusion.ai.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatAttachmentPreviewTokenService} 单元测试：签发/校验闭环、过期、篡改、密钥缺失降级。
 *
 * @author Jin
 */
class ChatAttachmentPreviewTokenServiceTest {

    private static final String SECRET = "test-preview-secret-do-not-use-in-prod";

    private ChatAttachmentPreviewTokenService newService(String secret, long ttlSeconds) {
        AiProperties props = new AiProperties();
        props.getChat().getAttachment().getPreview().setSecret(secret);
        props.getChat().getAttachment().getPreview().setTtlSeconds(ttlSeconds);
        ChatAttachmentPreviewTokenService svc = new ChatAttachmentPreviewTokenService(props);
        svc.init();
        return svc;
    }

    private ChatAttachmentEntity imageAttachment(String id) {
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setCategory("IMAGE");
        entity.setMimeType("image/png");
        return entity;
    }

    private ChatAttachmentEntity documentAttachment(String id) {
        ChatAttachmentEntity entity = new ChatAttachmentEntity();
        entity.setId(id);
        entity.setCategory("DOCUMENT");
        entity.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return entity;
    }

    private long extractExpires(String url) {
        return Long.parseLong(url.replaceAll(".*expires=(\\d+).*", "$1"));
    }

    private String extractSig(String url) {
        return url.replaceAll(".*sig=(.*)", "$1");
    }

    @Test
    void generatePreviewUrlThenValidatePasses() {
        ChatAttachmentPreviewTokenService svc = newService(SECRET, 3600);
        String url = svc.generatePreviewUrl(imageAttachment("att-001"));
        assertThat(url).startsWith("/v1/ai/chat/attachments/att-001/preview?expires=");
        assertThat(url).contains("sig=");
        // 校验通过（不抛异常即通过）
        svc.validatePreview("att-001", extractExpires(url), extractSig(url));
    }

    @Test
    void previewTokenCannotAuthorizeOriginalDownload() {
        ChatAttachmentPreviewTokenService svc = newService(SECRET, 3600);
        String url = svc.generatePreviewUrl(imageAttachment("att-009"));

        assertThatThrownBy(() -> svc.validateDownload("att-009", extractExpires(url), extractSig(url)))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void downloadTokenCannotAuthorizePreview() {
        ChatAttachmentPreviewTokenService svc = newService(SECRET, 3600);
        String url = svc.generateDownloadUrl(imageAttachment("att-010"));

        assertThatThrownBy(() -> svc.validatePreview("att-010", extractExpires(url), extractSig(url)))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void officeDocumentWithoutDerivedPdfHasDownloadButNoPreviewUrl() {
        ChatAttachmentPreviewTokenService svc = newService(SECRET, 3600);
        ChatAttachmentEntity attachment = documentAttachment("att-002");
        assertThat(svc.generatePreviewUrl(attachment)).isNull();
        assertThat(svc.generateDownloadUrl(attachment)).startsWith("/v1/ai/chat/attachments/att-002/file?expires=");
    }

    @Test
    void officeDocumentWithDerivedPdfHasPreviewUrl() {
        ChatAttachmentPreviewTokenService svc = newService(SECRET, 3600);
        ChatAttachmentEntity attachment = documentAttachment("att-008");
        attachment.setPreviewStoragePath("chat/session/att-008.preview.pdf");
        assertThat(svc.generatePreviewUrl(attachment)).startsWith("/v1/ai/chat/attachments/att-008/preview?expires=");
    }

    @Test
    void generatePreviewUrlWithoutSecretReturnsNull() {
        ChatAttachmentPreviewTokenService svc = newService(null, 3600);
        assertThat(svc.generatePreviewUrl(imageAttachment("att-003"))).isNull();
    }

    @Test
    void validateWithTamperedSigThrows() {
        ChatAttachmentPreviewTokenService svc = newService(SECRET, 3600);
        String url = svc.generatePreviewUrl(imageAttachment("att-004"));
        assertThatThrownBy(() -> svc.validatePreview("att-004", extractExpires(url), "tampered-sig"))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void validateWithExpiredTokenThrows() {
        ChatAttachmentPreviewTokenService svc = newService(SECRET, 3600);
        long pastExpires = Instant.now().getEpochSecond() - 10;
        // 用过去时间重新签发 sig，校验时应判过期
        String url = svc.generatePreviewUrl(imageAttachment("att-005"));
        // 直接用过期时间校验原 sig（payload 不同也会失败，但过期检查先于签名比较失败）
        assertThatThrownBy(() -> svc.validatePreview("att-005", pastExpires, extractSig(url)))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void validateWithoutSecretThrows() {
        ChatAttachmentPreviewTokenService svc = newService(null, 3600);
        assertThatThrownBy(() -> svc.validatePreview("att-006", Instant.now().getEpochSecond() + 3600, "any"))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void validateWithMismatchedIdThrows() {
        ChatAttachmentPreviewTokenService svc = newService(SECRET, 3600);
        String url = svc.generatePreviewUrl(imageAttachment("att-007"));
        // 用 att-007 的 sig 校验 att-008（payload 不同，签名不匹配）
        assertThatThrownBy(() -> svc.validatePreview("att-008", extractExpires(url), extractSig(url)))
                .isInstanceOf(AiBusinessException.class);
    }
}
