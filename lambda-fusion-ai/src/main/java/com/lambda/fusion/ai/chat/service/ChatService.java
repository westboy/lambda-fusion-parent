package com.lambda.fusion.ai.chat.service;

import com.lambda.fusion.ai.chat.model.ChatRun;
import com.lambda.fusion.ai.chat.model.ConfirmToolCall;
import com.lambda.fusion.ai.chat.model.RunContext;
import com.lambda.fusion.ai.chat.model.SendMessage;
import com.lambda.fusion.ai.chat.model.SubmitToolInput;
import java.util.Optional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话入口：创建后台 Run、订阅事件、恢复、确认、输入提交和停止。
 *
 * <p>确认/输入/停止拆为「请求线程装载归属」+「异步线程执行重活」两段：装载（含 Sa-Token 归属校验）
 * 由 Controller 在请求线程调用 {@link ChatRunService#loadOwned} 完成；{@code *Loaded} 方法只依赖
 * 已校验的领域对象（租户经 {@code TenantUtils} 显式传播），可在任意线程执行，不占用 Tomcat 工作线程。
 */
public interface ChatService {

    SseEmitter streamChat(String sessionId, SendMessage message);

    Optional<ChatRun> activeRun(String sessionId);

    ChatRun getRun(String sessionId, String runId);

    SseEmitter resume(String sessionId, String runId);

    /** 对已装载的归属上下文执行确认并推进下一阶段（可在异步线程调用）。 */
    SseEmitter confirmLoaded(RunContext context, ConfirmToolCall command);

    /** 对已装载的归属上下文提交挂起工具输入并推进下一阶段（可在异步线程调用）。 */
    SseEmitter submitInputLoaded(RunContext context, SubmitToolInput command);

    /** 对已装载的归属上下文请求停止运行（可在异步线程调用）。 */
    void stopLoaded(RunContext context);
}
