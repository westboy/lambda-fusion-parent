package com.lambda.fusion.ai.chat.service;

import com.lambda.fusion.ai.chat.model.ChatAttachmentView;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.storage.AiFileResource;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理对话附件的上传、查询、下载、删除和消息绑定。附件通过 {@code session_id} 关联会话，
 * 访问同时受租户隔离和会话 {@code user_id} 所有权校验约束，防止同一租户内的用户相互访问附件。
 *
 * @author Jin
 */
public interface ChatAttachmentService {

    /**
     * 校验会话所有权、文件类型和大小后上传原文件并创建附件记录。新记录的 {@code message_id} 为空，
     * 发送消息时由 {@link #bindToMessage} 回填；文件存储失败时回滚已创建的记录。
     */
    ChatAttachmentEntity upload(String sessionId, MultipartFile file);

    /** 将可信工具输出物化为未绑定的对话附件；调用方负责删除传入的临时文件。 */
    ChatAttachmentEntity storeGenerated(
            String tenantId, String sessionId, Path source, String fileName, String mimeType);

    /** 通过所属会话校验附件归属；附件不存在或不属于当前用户时抛出 {@code ATTACHMENT_NOT_FOUND}。 */
    ChatAttachmentEntity loadOwned(String attachmentId);

    /**
     * 将已上传且未被占用的附件绑定到消息。任一附件不存在、不属于目标会话或已绑定时，
     * 整体抛出 {@code ATTACHMENT_NOT_FOUND}。
     */
    List<ChatAttachmentEntity> bindToMessage(ChatSessionEntity session, List<String> attachmentIds, Long messageId);

    /** 按消息 ID 批量查询用于历史消息展示的附件。 */
    List<ChatAttachmentEntity> listByMessageIds(Collection<Long> messageIds);

    /** 转换为对外视图；图片附件附带签名预览地址，内部存储路径和租户信息不对外暴露。 */
    ChatAttachmentView toView(ChatAttachmentEntity entity);

    /** 根据附件记录的存储类型，将原文件写入输出流。 */
    void download(String attachmentId, OutputStream out);

    /**
     * 仅供签名预览端点按 ID 查询附件，不执行租户或用户归属校验。预览请求没有登录上下文，
     * 调用方必须先校验签名令牌，不能将该方法用于普通下载接口。
     */
    ChatAttachmentEntity loadByIdForPreview(String attachmentId);

    /** 按实体的存储类型写出原文件；不查询数据库也不校验归属，仅供已完成鉴权的调用方复用。 */
    void writeTo(ChatAttachmentEntity entity, OutputStream out);

    /** 打开附件内容，调用方必须关闭返回句柄。 */
    AiFileResource openContent(ChatAttachmentEntity entity);

    /** 为支持直读的后端生成临时地址；LOCAL 返回空。 */
    Optional<String> temporaryReadUrl(ChatAttachmentEntity entity, Duration ttl);

    /** 删除尚未绑定消息的附件；已发送附件只能随会话级联删除。 */
    void delete(String attachmentId);

    /** 删除会话的全部附件文件和记录；单个文件删除失败仅记录告警，不阻断记录清理。 */
    void deleteBySession(String sessionId);

    /** 清理由当前运行创建但未绑定消息的生成附件。 */
    void discardGenerated(String sessionId, Collection<String> attachmentIds);
}
