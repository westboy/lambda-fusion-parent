package com.lambda.fusion.ai.chat.attachment;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 对话附件预览直链签名服务：为附件签发/校验 HMAC-SHA256 签名 token。preview 端点放行 Bearer 登录，鉴权完全
 * 依赖 token：token 由后端在 Bearer 鉴权后签发（upload / list messages 时），绑定 attachmentId + 过期时间，有时效
 * 且不可伪造，与 OSS presigned URL 同一安全模型；未配置密钥时不阻断启动，仅使预览直链不可用（前端降级为文件名）。
 * 签名比较用 {@link MessageDigest#isEqual} 常量时间比较，规避时序攻击。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ChatAttachmentPreviewTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String PREVIEW_PATH_TEMPLATE = "/v1/ai/chat/attachments/%s/preview?expires=%d&sig=%s";

    private final AiProperties aiProperties;

    private SecretKeySpec keySpec;

    @PostConstruct
    public void init() {
        String secret = aiProperties.getChat().getAttachment().getPreview().getSecret();
        if (StringUtils.isBlank(secret)) {
            log.warn("未配置 lambda.fusion.ai.chat.attachment.preview.secret，附件媒体预览直链不可用");
            return;
        }
        keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * 生成附件预览直链（相对路径）。密钥未配置返回 {@code null}。图片走 inline 预览、文档走 attachment 下载
     * （由 preview 端点按 category 区分），统一用签名 token 鉴权。
     *
     * @param entity 附件实体
     * @return 形如 {@code /v1/ai/chat/attachments/{id}/preview?expires=...&sig=...} 的相对路径，或 {@code null}
     */
    public String generatePreviewUrl(ChatAttachmentEntity entity) {
        if (entity == null || keySpec == null) {
            return null;
        }
        long expires = Instant.now().getEpochSecond()
                + aiProperties.getChat().getAttachment().getPreview().getTtlSeconds();
        String sig = sign(entity.getId(), expires);
        return String.format(PREVIEW_PATH_TEMPLATE, entity.getId(), expires, sig);
    }

    /**
     * 校验 preview token：密钥已配置 + 签名匹配 + 未过期。失败抛 {@link AiErrorCode#ATTACHMENT_PREVIEW_TOKEN_INVALID}。
     *
     * @param attachmentId 路径中的附件 ID
     * @param expires 过期时间（epoch 秒）
     * @param sig 签名（base64url）
     */
    public void validate(String attachmentId, long expires, String sig) {
        if (keySpec == null || StringUtils.isBlank(sig)) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_PREVIEW_TOKEN_INVALID);
        }
        if (Instant.now().getEpochSecond() > expires) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_PREVIEW_TOKEN_INVALID);
        }
        byte[] expected = sign(attachmentId, expires).getBytes(StandardCharsets.UTF_8);
        byte[] actual = sig.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new AiBusinessException(AiErrorCode.ATTACHMENT_PREVIEW_TOKEN_INVALID);
        }
    }

    private String sign(String attachmentId, long expires) {
        try {
            String payload = attachmentId + ":" + expires;
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hmacBytes);
        } catch (Exception e) {
            throw new AiBusinessException(AiErrorCode.CONFIGURATION_ERROR, e);
        }
    }
}
