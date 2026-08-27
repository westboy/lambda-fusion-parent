package com.lambda.fusion.ai.chat.attachment;

import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.URLSource;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/** 把平台附件映射为 AgentScope 官方 {@link DataBlock} 引用。 */
@Component
@RequiredArgsConstructor
public class ChatAttachmentBlockFactory {

    public static final String ATTACHMENT_ID_PREFIX = "attachment:";
    private static final String AUTHENTICATED_DOWNLOAD_PATH = "/v1/ai/chat/attachments/%s/download";
    private static final Set<String> MEDIA_CATEGORIES = Set.of("IMAGE", "AUDIO", "VIDEO");

    private final ChatAttachmentPreviewTokenService previewTokenService;

    public DataBlock create(ChatAttachmentEntity attachment) {
        String url = previewTokenService.generateDownloadUrl(attachment);
        if (isMedia(attachment)) {
            url = StringUtils.defaultIfBlank(previewTokenService.generatePreviewUrl(attachment), url);
        }
        url = StringUtils.defaultIfBlank(url, AUTHENTICATED_DOWNLOAD_PATH.formatted(attachment.getId()));
        return DataBlock.builder()
                .id(ATTACHMENT_ID_PREFIX + attachment.getId())
                .name(attachment.getFileName())
                .source(new URLSource(url, attachment.getMimeType()))
                .build();
    }

    private static boolean isMedia(ChatAttachmentEntity attachment) {
        return MEDIA_CATEGORIES.contains(attachment.getCategory());
    }
}
