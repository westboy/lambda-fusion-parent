package com.lambda.fusion.ai.chat.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.logger.annotation.OperationLog;
import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.model.ChatMessagePage;
import com.lambda.fusion.ai.chat.model.ChatRun;
import com.lambda.fusion.ai.chat.model.ChatSessionPage;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
import com.lambda.fusion.ai.chat.model.CreateSession;
import com.lambda.fusion.ai.chat.model.RunContext;
import com.lambda.fusion.ai.chat.model.SendMessage;
import com.lambda.fusion.ai.chat.model.SubmitToolInput;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.chat.service.ChatMessageService;
import com.lambda.fusion.ai.chat.service.ChatRunService;
import com.lambda.fusion.ai.chat.service.ChatService;
import com.lambda.fusion.ai.chat.service.ChatSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "对话会话")
@RestController
@RequestMapping("/v1/ai/sessions")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final ChatService chatService;
    private final ChatRunService chatRunService;
    private final AiProperties properties;
    private final AsyncTaskExecutor chatWebAsyncExecutor;

    @SaCheckPermission(value = "ai:chat-session:page")
    @Operation(summary = "分页查询当前用户的会话")
    @GetMapping("/page")
    public Page<ChatSessionEntity> page(@Valid ChatSessionPage query) {
        return chatSessionService.page(query);
    }

    @SaCheckPermission(value = "ai:chat-session:get")
    @Operation(summary = "查询会话详情")
    @GetMapping("/{id}")
    public ChatSessionEntity get(@Parameter(description = "会话ID", required = true) @PathVariable String id) {
        return chatSessionService.get(id);
    }

    @SaCheckPermission(value = "ai:chat-session:add")
    @Operation(summary = "创建会话")
    @PostMapping
    public ChatSessionEntity create(@RequestBody @Valid CreateSession dto) {
        return chatSessionService.create(dto);
    }

    @SaCheckPermission(value = "ai:chat-session:delete")
    @OperationLog(module = "AI", value = "删除会话")
    @Operation(summary = "删除会话")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "会话ID", required = true) @PathVariable String id) {
        chatSessionService.delete(id);
    }

    @SaCheckPermission(value = "ai:chat-session:message-page")
    @Operation(summary = "游标分页查询会话消息历史")
    @GetMapping("/{id}/messages")
    public ChatMessagePage messages(
            @Parameter(description = "会话ID", required = true) @PathVariable String id,
            @Parameter(description = "翻页游标：返回早于该消息 ID 的记录；不传则返回最新一页") @RequestParam(name = "before", required = false)
                    Long before,
            @Parameter(description = "页大小，默认 50，最大 200") @RequestParam(name = "limit", required = false)
                    Integer limit) {
        return chatMessageService.listBySession(id, before, limit);
    }

    @SaCheckPermission(value = "ai:chat-session:chat")
    @Operation(summary = "流式对话（SSE）")
    @PostMapping(value = "/{id}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(
            @Parameter(description = "会话ID", required = true) @PathVariable String id,
            @RequestBody @Valid SendMessage dto,
            HttpServletResponse response) {
        prepareSseResponse(response);
        return chatService.streamChat(id, dto);
    }

    @SaCheckPermission(value = "ai:chat-session:active-run")
    @Operation(summary = "查询会话当前活跃Run")
    @GetMapping("/{id}/runs/active")
    public ChatRun activeRun(@Parameter(description = "会话ID", required = true) @PathVariable String id) {
        return chatService.activeRun(id).orElse(null);
    }

    @SaCheckPermission(value = "ai:chat-session:run-get")
    @Operation(summary = "查询对话Run详情")
    @GetMapping("/{id}/runs/{runId}")
    public ChatRun run(
            @Parameter(description = "会话ID", required = true) @PathVariable String id,
            @Parameter(description = "Run ID", required = true) @PathVariable String runId) {
        return chatService.getRun(id, runId);
    }

    @SaCheckPermission(value = "ai:chat-session:run-events")
    @Operation(summary = "续看对话Run事件(SSE)")
    @GetMapping(value = "/{id}/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @Parameter(description = "会话ID", required = true) @PathVariable String id,
            @Parameter(description = "Run ID", required = true) @PathVariable String runId,
            HttpServletResponse response) {
        prepareSseResponse(response);
        return chatService.resume(id, runId);
    }

    @SaCheckPermission(value = "ai:chat-session:run-confirm")
    @Operation(summary = "确认指定Run的工具调用(HITL, SSE)")
    @PostMapping(value = "/{id}/runs/{runId}/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public WebAsyncTask<SseEmitter> confirmRun(
            @Parameter(description = "会话ID", required = true) @PathVariable String id,
            @Parameter(description = "Run ID", required = true) @PathVariable String runId,
            @RequestBody @Valid ConfirmToolCall dto,
            HttpServletResponse response) {
        prepareSseResponse(response);
        // 归属装载依赖 Sa-Token 请求上下文（ThreadLocal），必须留在请求线程；
        // 确认重活（Agent 状态读取、CAS 推进、阶段启动）在共享虚拟线程执行器上异步执行。
        RunContext context = chatRunService.loadOwned(id, runId);
        return newWebAsyncTask(() -> chatService.confirmLoaded(context, dto));
    }

    @SaCheckPermission(value = "ai:chat-session:run-input-submit")
    @Operation(summary = "提交指定Run的挂起工具输入(HITL, SSE)")
    @PostMapping(value = "/{id}/runs/{runId}/input", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public WebAsyncTask<SseEmitter> submitInputRun(
            @Parameter(description = "会话ID", required = true) @PathVariable String id,
            @Parameter(description = "Run ID", required = true) @PathVariable String runId,
            @RequestBody @Valid SubmitToolInput dto,
            HttpServletResponse response) {
        prepareSseResponse(response);
        RunContext context = chatRunService.loadOwned(id, runId);
        return newWebAsyncTask(() -> chatService.submitInputLoaded(context, dto));
    }

    @SaCheckPermission(value = "ai:chat-session:run-stop")
    @Operation(summary = "停止指定对话Run")
    @PostMapping("/{id}/runs/{runId}/stop")
    public WebAsyncTask<Void> stop(
            @Parameter(description = "会话ID", required = true) @PathVariable String id,
            @Parameter(description = "Run ID", required = true) @PathVariable String runId) {
        RunContext context = chatRunService.loadOwned(id, runId);
        return newWebAsyncTask(() -> {
            chatService.stopLoaded(context);
            return null;
        });
    }

    /** 包装异步任务：超时与 SSE 连接预算一致（callable 只做编排，实际很快）。 */
    private <T> WebAsyncTask<T> newWebAsyncTask(Callable<T> action) {
        return new WebAsyncTask<>(
                properties.getChat().getRun().getConnectionTimeoutSeconds() * 1000, chatWebAsyncExecutor, action);
    }

    private static void prepareSseResponse(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
    }
}
