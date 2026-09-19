package com.lambda.fusion.ai.chat.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_chat_attachment")
@Schema(description = "对话附件")
public class ChatAttachmentEntity {

    @TableId("id")
    @Schema(description = "主键(雪花ID)")
    private String id;

    @TableField("tenant_id")
    @Schema(description = "租户ID")
    private String tenantId;

    @TableField("session_id")
    @Schema(description = "归属会话ID")
    private String sessionId;

    @TableField("message_id")
    @Schema(description = "归属消息ID(发送后回填; NULL=已上传未发送)")
    private Long messageId;

    @TableField("file_name")
    @Schema(description = "原始文件名")
    private String fileName;

    @TableField("file_type")
    @Schema(description = "扩展名(小写)")
    private String fileType;

    @TableField("mime_type")
    @Schema(description = "MIME 类型")
    private String mimeType;

    @TableField("size_bytes")
    @Schema(description = "文件大小(字节)")
    private Long sizeBytes;

    @TableField("category")
    @Schema(description = "类别: IMAGE/DOCUMENT/AUDIO/VIDEO/FILE")
    private String category;

    @TableField("source")
    @Schema(description = "来源: uploaded=用户上传 / generated=工具生成")
    private String source;

    @TableField("storage_type")
    @Schema(description = "存储类型: LOCAL/OSS")
    private String storageType;

    @TableField("storage_path")
    @Schema(description = "存储路径")
    private String storagePath;

    @TableField("preview_storage_path")
    @Schema(description = "派生 PDF 预览存储路径(可空，与原件使用相同存储后端)")
    private String previewStoragePath;

    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
