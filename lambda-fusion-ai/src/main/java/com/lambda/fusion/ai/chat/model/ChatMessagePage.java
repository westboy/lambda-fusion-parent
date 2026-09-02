package com.lambda.fusion.ai.chat.model;

import java.util.List;
import lombok.Data;

/**
 * 会话消息历史游标分页结果。消息按 ID 升序返回（即时间正序）；{@code hasMore} 为 true 时，
 * 以 {@code nextCursor}（当前页最早一条消息的 ID）作为下一次请求的 {@code before} 游标继续向前翻页。
 *
 * @author Jin
 */
@Data
public class ChatMessagePage {

    /** 当前页消息，按消息 ID 升序，含各消息附件视图。 */
    private List<ChatMessageView> messages;

    /** 是否仍存在更早的消息。 */
    private boolean hasMore;

    /** 下一页游标；{@code hasMore} 为 false 时为空。 */
    private Long nextCursor;
}
