package com.lambda.fusion.ai.chat.attachment;

import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/** 对话附件可内联预览类型的单一安全策略。 */
public final class ChatAttachmentPreviewPolicy {

    public static final String PDF_MIME_TYPE = "application/pdf";

    private static final Set<String> INLINE_CATEGORIES = Set.of("IMAGE", "AUDIO", "VIDEO");
    private static final Set<String> SAFE_INLINE_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/avif",
            "image/bmp",
            "audio/mpeg",
            "audio/mp4",
            "audio/wav",
            "audio/ogg",
            "audio/webm",
            "audio/aac",
            "audio/flac",
            "video/mp4",
            "video/webm",
            "video/quicktime",
            "video/ogg",
            "video/mpeg");

    private ChatAttachmentPreviewPolicy() {}

    public static boolean canPreview(ChatAttachmentEntity attachment) {
        return hasDerivedPreview(attachment) || isSafeOriginalPreview(attachment);
    }

    public static boolean hasDerivedPreview(ChatAttachmentEntity attachment) {
        return attachment != null && StringUtils.isNotBlank(attachment.getPreviewStoragePath());
    }

    public static boolean isSafeOriginalPreview(ChatAttachmentEntity attachment) {
        if (attachment == null) {
            return false;
        }
        String mimeType = normalizedMimeType(attachment.getMimeType());
        return PDF_MIME_TYPE.equals(mimeType)
                || (INLINE_CATEGORIES.contains(attachment.getCategory()) && SAFE_INLINE_MIME_TYPES.contains(mimeType));
    }

    public static String previewMimeType(ChatAttachmentEntity attachment) {
        if (hasDerivedPreview(attachment)) {
            return PDF_MIME_TYPE;
        }
        return isSafeOriginalPreview(attachment) ? normalizedMimeType(attachment.getMimeType()) : null;
    }

    private static String normalizedMimeType(String mimeType) {
        return StringUtils.substringBefore(StringUtils.defaultString(mimeType), ";")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
