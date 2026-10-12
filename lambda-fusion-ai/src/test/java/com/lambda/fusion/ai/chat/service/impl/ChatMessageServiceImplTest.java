package com.lambda.fusion.ai.chat.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lambda.fusion.ai.chat.mapper.ChatMessageMapper;
import com.lambda.fusion.ai.chat.mapper.ChatRunMapper;
import com.lambda.fusion.ai.chat.mapper.ChatSessionMapper;
import com.lambda.fusion.ai.chat.model.ChatMessagePage;
import com.lambda.fusion.ai.chat.model.ChatMessageView;
import com.lambda.fusion.ai.chat.model.entity.ChatMessageEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.exception.AiBusinessException;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** 会话消息历史游标分页测试。 */
class ChatMessageServiceImplTest {

    @BeforeAll
    static void initMybatisLambdaCache() {
        // 纯 Mockito 环境没有 MyBatis-Plus 启动过程，Lambda wrapper 依赖的实体元数据
        // 缓存需显式初始化，否则 ChatRunEntity::assistantMessageId 之类的列解析会抛异常。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ChatMessageEntity.class);
        TableInfoHelper.initTableInfo(assistant, ChatRunEntity.class);
    }

    private ChatMessageMapper messageMapper;
    private ChatSessionMapper sessionMapper;
    private ChatAttachmentService attachmentService;
    private ChatRunMapper runMapper;
    private ChatMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(ChatMessageMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        attachmentService = mock(ChatAttachmentService.class);
        runMapper = mock(ChatRunMapper.class);
        service = new ChatMessageServiceImpl(messageMapper, sessionMapper, attachmentService, runMapper);

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("zhangsan");
        when(sessionMapper.selectOwned("session-1", "zhangsan")).thenReturn(session);
        when(attachmentService.listByMessageIds(anyList())).thenReturn(List.of());
    }

    /** 在登录上下文内执行查询；AuthUtils 依赖 Sa-Token 上下文，单测中静态模拟。 */
    private <T> T withAuth(Supplier<T> action) {
        UserDetails user = new UserDetails();
        user.setUsername("zhangsan");
        try (MockedStatic<AuthUtils> auth = Mockito.mockStatic(AuthUtils.class)) {
            auth.when(AuthUtils::getUser).thenReturn(user);
            return action.get();
        }
    }

    @Test
    void shouldReturnLatestPageAscendingWithCursorWhenMoreExists() {
        // 模拟倒序取回 limit+1 条：最新页为 id 6..2，id 1 触发 hasMore
        when(messageMapper.selectList(any())).thenReturn(messages(6, 5, 4, 3, 2, 1));

        ChatMessagePage page = withAuth(() -> service.listBySession("session-1", null, 5));

        assertThat(page.getMessages()).extracting(ChatMessageView::getId).containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(page.getMessages().get(0).getSessionId()).isEqualTo("session-1");
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(2L);
    }

    @Test
    void shouldReturnNoMoreWhenPageNotFull() {
        when(messageMapper.selectList(any())).thenReturn(messages(3, 2, 1));

        ChatMessagePage page = withAuth(() -> service.listBySession("session-1", null, 5));

        assertThat(page.getMessages()).extracting(ChatMessageView::getId).containsExactly(1L, 2L, 3L);
        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getNextCursor()).isNull();
    }

    @Test
    void shouldUseCursorToPageOlderMessages() {
        // 模拟 before=5 的库端过滤（id<5，limit+1=4 条，倒序）
        when(messageMapper.selectList(any())).thenReturn(messages(4, 3, 2, 1));

        ChatMessagePage page = withAuth(() -> service.listBySession("session-1", 5L, 3));

        assertThat(page.getMessages()).extracting(ChatMessageView::getId).containsExactly(2L, 3L, 4L);
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getNextCursor()).isEqualTo(2L);
    }

    @Test
    void shouldNormalizeOutOfRangePageSize() {
        when(messageMapper.selectList(any())).thenReturn(messages(3, 2, 1));

        ChatMessagePage page = withAuth(() -> service.listBySession("session-1", null, 500));

        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getMessages()).hasSize(3);
    }

    @Test
    void shouldThrowWhenSessionNotOwned() {
        when(sessionMapper.selectOwned("session-1", "zhangsan")).thenReturn(null);

        assertThatThrownBy(() -> withAuth(() -> service.listBySession("session-1", null, 50)))
                .isInstanceOf(AiBusinessException.class);
    }

    @Test
    void shouldAssignSnowflakeBeforeSavingMessages() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        List<Long> inserted = new ArrayList<>();
        when(messageMapper.insert(any(ChatMessageEntity.class))).thenAnswer(call -> {
            ChatMessageEntity message = call.getArgument(0);
            assertThat(message.getId()).isGreaterThan(1_000_000_000_000_000_000L);
            inserted.add(message.getId());
            return 1;
        });
        ChatMessageEntity user = service.saveUserMessage(session, "question");
        ChatMessageEntity assistant = service.saveAssistantMessage(session, "answer", "tool-result");
        assertThat(inserted).containsExactly(user.getId(), assistant.getId()).doesNotHaveDuplicates();
        assertThat(user.getRole()).isEqualTo("user");
        assertThat(assistant.getToolCall()).isEqualTo("tool-result");
    }

    @Test
    void shouldEnrichAssistantMessagesWithRunTimes() {
        ChatMessageEntity user = message(1L, "user");
        ChatMessageEntity assistant = message(2L, "assistant");
        when(messageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(assistant, user)));
        ChatRunEntity run = new ChatRunEntity();
        run.setAssistantMessageId(2L);
        run.setStartedAt(LocalDateTime.of(2026, 10, 12, 10, 0, 0));
        run.setFinishedAt(LocalDateTime.of(2026, 10, 12, 10, 0, 12));
        when(runMapper.selectList(any())).thenReturn(List.of(run));

        ChatMessagePage page = withAuth(() -> service.listBySession("session-1", null, 5));

        ChatMessageView assistantView = viewById(page, 2L);
        assertThat(assistantView.getRunStartedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 0));
        assertThat(assistantView.getRunFinishedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 0, 12));
        assertThat(viewById(page, 1L).getRunStartedAt()).isNull();
    }

    private static ChatMessageView viewById(ChatMessagePage page, long id) {
        return page.getMessages().stream()
                .filter(view -> view.getId() == id)
                .findFirst()
                .orElseThrow();
    }

    /** 按 ID 倒序构造消息列表，模拟 orderByDesc 查询结果。 */
    private static List<ChatMessageEntity> messages(long... idsDesc) {
        List<ChatMessageEntity> list = new ArrayList<>();
        for (long id : idsDesc) {
            list.add(message(id, "user"));
        }
        return list;
    }

    private static ChatMessageEntity message(long id, String role) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(id);
        message.setSessionId("session-1");
        message.setRole(role);
        message.setContent("m" + id);
        return message;
    }
}
