package com.lambda.fusion.ai.chat.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.RunContext;
import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunService;
import com.lambda.fusion.ai.chat.service.ChatRunStepService;
import com.lambda.fusion.ai.chat.service.ChatService;
import com.lambda.fusion.ai.chat.service.ChatSessionService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** confirm/stop 端点的异步编排测试：归属装载在请求线程完成，业务重活经 WebAsyncTask 异步执行。 */
class ChatControllerAsyncTest {

    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);
    private final ChatService chatService = mock(ChatService.class);
    private final ChatRunService chatRunService = mock(ChatRunService.class);
    private final AiProperties properties = new AiProperties();

    private MockMvc mockMvc() {
        ChatController controller = new ChatController(
                chatSessionService,
                chatMessageService,
                chatService,
                chatRunService,
                mock(ChatRunStepService.class),
                properties,
                new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldRunConfirmOffRequestThreadAndStreamSse() throws Exception {
        RunContext context = new RunContext(run(), session());
        when(chatRunService.loadOwned("session-1", "run-1")).thenReturn(context);
        when(chatService.confirmLoaded(eq(context), any())).thenAnswer(invocation -> {
            SseEmitter emitter = new SseEmitter(10_000L);
            emitter.complete();
            return emitter;
        });
        MockMvc mockMvc = mockMvc();

        MvcResult result = mockMvc.perform(post("/v1/ai/sessions/session-1/runs/run-1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phaseNo\":2,\"decisions\":[{\"toolCallId\":\"call_1\",\"confirmed\":true}]}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        verify(chatRunService).loadOwned("session-1", "run-1");
        verify(chatService).confirmLoaded(eq(context), any());
    }

    @Test
    void shouldRunStopOffRequestThread() throws Exception {
        RunContext context = new RunContext(run(), session());
        when(chatRunService.loadOwned("session-1", "run-1")).thenReturn(context);
        MockMvc mockMvc = mockMvc();

        MvcResult result = mockMvc.perform(post("/v1/ai/sessions/session-1/runs/run-1/stop"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        verify(chatService).stopLoaded(context);
    }

    private static ChatRunEntity run() {
        ChatRunEntity run = new ChatRunEntity();
        run.setId("run-1");
        run.setSessionId("session-1");
        run.setPhaseNo(2);
        return run;
    }

    private static ChatSessionEntity session() {
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        return session;
    }
}
