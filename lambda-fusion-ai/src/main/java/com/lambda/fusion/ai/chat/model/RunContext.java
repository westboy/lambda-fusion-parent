package com.lambda.fusion.ai.chat.model;

import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;
import com.lambda.fusion.ai.runtime.media.MediaGenerationInvocation;

/** HTTP 编排面返回的 Run 与会话，{@code created} 用于区分本次新建和幂等加载。 */
public record RunContext(
        ChatRunEntity run, ChatSessionEntity session, boolean created, MediaGenerationInvocation mediaGeneration) {

    /** 构造不携带媒体命令的运行上下文。 */
    public RunContext(ChatRunEntity run, ChatSessionEntity session, boolean created) {
        this(run, session, created, null);
    }

    /** 构造只读加载结果；兼容不需要启动语义的调用方。 */
    public RunContext(ChatRunEntity run, ChatSessionEntity session) {
        this(run, session, false, null);
    }
}
