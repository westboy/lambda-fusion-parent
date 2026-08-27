package com.lambda.fusion.ai.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lambda.fusion.ai.chat.model.entity.ChatAttachmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatAttachmentMapper extends BaseMapper<ChatAttachmentEntity> {

    /** 单次查询校验附件、会话、发送状态及会话所属用户，避免工具并行调用重复读取会话。 */
    @Select(
            """
            SELECT a.*
            FROM ai_chat_attachment a
            INNER JOIN ai_chat_session s ON s.id = a.session_id
            WHERE a.id = #{attachmentId}
              AND a.session_id = #{sessionId}
              AND a.message_id IS NOT NULL
              AND s.user_id = #{userId}
            LIMIT 1
            """)
    ChatAttachmentEntity selectBoundOwned(
            @Param("attachmentId") String attachmentId,
            @Param("sessionId") String sessionId,
            @Param("userId") String userId);
}
