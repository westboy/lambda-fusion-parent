package com.lambda.fusion.ai.chat.model;

import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 对话附件视图：平铺 {@link ChatAttachmentEntity} 对外字段并携带媒体签名预览直链。不含 storageType/storagePath/
 * tenantId 等存储与租户内部字段（收口在后端），新增 {@link #previewUrl} 供前端 {@code <img src>} 直连预览；与
 * {@link ChatMessageView} 同为 chat 子域视图，沿用 {@code of} 静态工厂手写转换的既有模式。
 *
 * @author Jin
 */
@Data
@Schema(description = "对话附件(含预览直链)")
public class ChatAttachmentView {

    @Schema(description = "主键(雪花ID)")
    private String id;

    @Schema(description = "归属会话ID")
    private String sessionId;

    @Schema(description = "归属消息ID(发送后回填; NULL=已上传未发送)")
    private Long messageId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "扩展名(小写)")
    private String fileType;

    @Schema(description = "MIME 类型")
    private String mimeType;

    @Schema(description = "文件大小(字节)")
    private Long sizeBytes;

    @Schema(description = "类别: IMAGE/DOCUMENT/AUDIO/VIDEO/FILE")
    private String category;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /** 附件的签名预览直链（相对路径，前端拼 {@code apiURL}），由预览签名服务签发、preview 端点靠签名 token 鉴权。 */
    @Schema(description = "附件预览直链(相对路径，前端拼接 apiURL)")
    private String previewUrl;

    public static ChatAttachmentView of(ChatAttachmentEntity entity, String previewUrl) {
        ChatAttachmentView view = new ChatAttachmentView();
        view.setId(entity.getId());
        view.setSessionId(entity.getSessionId());
        view.setMessageId(entity.getMessageId());
        view.setFileName(entity.getFileName());
        view.setFileType(entity.getFileType());
        view.setMimeType(entity.getMimeType());
        view.setSizeBytes(entity.getSizeBytes());
        view.setCategory(entity.getCategory());
        view.setCreatedAt(entity.getCreatedAt());
        view.setPreviewUrl(previewUrl);
        return view;
    }
}
