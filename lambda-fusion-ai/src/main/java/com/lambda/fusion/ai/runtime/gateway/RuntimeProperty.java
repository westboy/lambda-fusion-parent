package com.lambda.fusion.ai.runtime.gateway;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.gateway.MsgContext;

public final class RuntimeProperty {

    public static final String MSG_CONTEXT_KEY = "msgContext";
    public static final String KEY_AGENT_ID = "agentId";
    public static final String KEY_RUN_ID = "runId";
    public static final String KEY_TENANT_ID = "tenantId";
    public static final String KEY_APP_ID = "appId";
    public static final String KEY_LF_SESSION_ID = "_sessionId";

    private RuntimeProperty() {}

    public static MsgContext msgContext(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        return ctx.get(MSG_CONTEXT_KEY);
    }

    public static String tenantId(RuntimeContext ctx) {
        return extra(ctx, KEY_TENANT_ID);
    }

    public static String runId(RuntimeContext ctx) {
        return extra(ctx, KEY_RUN_ID);
    }

    public static String appId(RuntimeContext ctx) {
        return extra(ctx, KEY_APP_ID);
    }

    public static String _sessionId(RuntimeContext ctx) {
        return extra(ctx, KEY_LF_SESSION_ID);
    }

    private static String extra(RuntimeContext ctx, String key) {
        MsgContext mc = msgContext(ctx);
        String value = mc != null ? mc.extra().get(key) : null;
        return value != null || ctx == null ? value : ctx.get(key, String.class);
    }
}
