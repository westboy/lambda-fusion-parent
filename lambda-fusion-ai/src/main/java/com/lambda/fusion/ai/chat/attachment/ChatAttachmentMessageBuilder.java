package com.lambda.fusion.ai.chat.attachment;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.ai.exception.AiErrorCode;
import com.lambda.fusion.ai.llm.service.LlmModelService;
import com.lambda.fusion.ai.runtime.document.DocumentTextExtractor;
import com.lambda.fusion.ai.storage.AiFileStorage;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 对话用户消息组装器：把「文本 + 附件」组装为 AgentScope 多模态 {@link Msg}。降级：图片 + 视觉模型 →
 * {@link ImageBlock}（base64 内联，OSS 私有读不暴露签名 URL）；图片 + 非视觉模型 → 抛
 * {@link AiErrorCode#ATTACHMENT_VISION_NOT_SUPPORTED}；文档 → 抽取文本拼进 {@link TextBlock}（截断至
 * maxExtractChars）；无附件 → 走纯文本路径。base64 体积约原图 1.37 倍且随 ReAct 历史重复发送，由上传侧
 * maxFileSizeMb 兜底。
 *
 * @author Jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class ChatAttachmentMessageBuilder {

    private static final String CATEGORY_IMAGE = "IMAGE";

    private final LlmModelService llmModelService;
    private final AiFileStorageResolver storageResolver;
    private final AiProperties aiProperties;

    /**
     * 组装用户消息。attachments 为已通过 {@code ChatAttachmentService.bindToMessage} 校验并绑定的附件。
     *
     * @param session 会话
     * @param app 应用（用于解析绑定模型的视觉能力）
     * @param content 用户文本（纯附件消息可为空）
     * @param attachments 已绑定附件（可为空）
     * @return 多模态或纯文本用户消息
     */
    public Msg buildUserMsg(
            ChatSessionEntity session, AppEntity app, String content, List<ChatAttachmentEntity> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(StringUtils.defaultString(content))
                    .build();
        }

        boolean vision = supportsVision(app);
        int maxExtractChars = aiProperties.getChat().getAttachment().getMaxExtractChars();
        StringBuilder text = new StringBuilder(StringUtils.defaultString(content));
        List<ContentBlock> blocks = new ArrayList<>();

        for (ChatAttachmentEntity attachment : attachments) {
            if (CATEGORY_IMAGE.equals(attachment.getCategory())) {
                if (!vision) {
                    throw new AiBusinessException(
                            AiErrorCode.ATTACHMENT_VISION_NOT_SUPPORTED, attachment.getFileName());
                }
                blocks.add(new ImageBlock(
                        new Base64Source(resolveImageMimeType(attachment), encodeImageBase64(attachment))));
            } else {
                appendDocumentText(text, attachment, maxExtractChars);
            }
        }

        // 文本块置首（视觉模型惯例：先文本后图片），图片块随后
        blocks.add(0, TextBlock.builder().text(text.toString()).build());
        return Msg.builder().role(MsgRole.USER).content(blocks).build();
    }

    private boolean supportsVision(AppEntity app) {
        try {
            return Boolean.TRUE.equals(
                    llmModelService.loadById(app.getModelId()).getSupportsVision());
        } catch (RuntimeException e) {
            // 模型被删等异常按非视觉处理（安全降级），避免阻断纯文本/文档对话
            log.warn("解析应用绑定模型视觉能力失败，按非视觉处理: appId={}, modelId={}", app.getId(), app.getModelId());
            return false;
        }
    }

    private void appendDocumentText(StringBuilder text, ChatAttachmentEntity attachment, int maxExtractChars) {
        try {
            String extracted = extractDocumentText(attachment);
            text.append("\n\n[附件「")
                    .append(attachment.getFileName())
                    .append("」内容]\n")
                    .append(StringUtils.left(extracted, maxExtractChars))
                    .append("\n[/附件]");
        } catch (Exception e) {
            // 解析失败降级为占位说明，不阻断发送
            log.error("文档附件解析失败，注入占位说明: att={}, file={}", attachment.getId(), attachment.getFileName(), e);
            text.append("\n\n[附件「").append(attachment.getFileName()).append("」解析失败，已忽略]");
        }
    }

    private String extractDocumentText(ChatAttachmentEntity attachment) throws IOException {
        AiFileStorage storage = storageResolver.resolve(attachment.getStorageType());
        Path tempFile = Files.createTempFile("chat-doc-", "." + StringUtils.defaultString(attachment.getFileType()));
        try {
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                storage.download(attachment.getStoragePath(), out);
            }
            return DocumentTextExtractor.extractText(attachment.getFileType(), tempFile);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("删除文档附件临时文件失败: {}", tempFile, e.getMessage());
            }
        }
    }

    private String encodeImageBase64(ChatAttachmentEntity attachment) {
        AiFileStorage storage = storageResolver.resolve(attachment.getStorageType());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        storage.download(attachment.getStoragePath(), buffer);
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    private static String resolveImageMimeType(ChatAttachmentEntity attachment) {
        return StringUtils.defaultIfBlank(attachment.getMimeType(), "image/" + attachment.getFileType());
    }
}
