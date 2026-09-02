package com.lambda.fusion.ai.chat.model;

import com.lambda.fusion.ai.chat.model.entity.ChatRunEntity;
import com.lambda.fusion.ai.chat.model.entity.ChatSessionEntity;

/** HTTP 编排面返回的 Run 与会话，{@code created} 用于区分本次新建和幂等加载。 */
public record RunContext(ChatRunEntity run, ChatSessionEntity session, boolean created) {

    /** 构造只读加载结果；兼容不需要启动语义的调用方。 */
    public RunContext(ChatRunEntity run, ChatSessionEntity session) {
        this(run, session, false);
    }
}
