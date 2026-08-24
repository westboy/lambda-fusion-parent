package com.lambda.fusion.ai.exception;

import com.lambda.cloud.core.exception.model.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 模块错误码枚举，错误码占用 30000-39999 段
 */
@Getter
@AllArgsConstructor
public enum AiErrorCode implements ErrorCode {
    SYSTEM_ERROR(30900, "系统内部错误"),

    CONFIGURATION_ERROR(30901, "系统配置错误"),

    OPERATION_NOT_SUPPORTED(30902, "不支持的操作"),

    INVALID_PARAMETER(30903, "参数无效"),

    CONCURRENT_UPDATE_FAILED(30904, "并发更新失败，请重试"),

    RATE_LIMITED(30905, "请求过于频繁，请稍后重试"),

    MCP_SERVER_NOT_FOUND(30950, "MCP服务器不存在"),

    MCP_SERVER_DISABLED(30951, "MCP服务器已禁用"),

    MCP_SERVER_CONNECTION_FAILED(30952, "MCP服务器连接失败"),

    MCP_TOOL_EXECUTION_FAILED(30953, "MCP工具执行失败"),

    MCP_TOOL_NOT_FOUND(30954, "MCP工具不存在"),

    MCP_TRANSPORT_NOT_SUPPORTED(30955, "不支持的MCP传输类型"),

    MCP_SERVER_NAME_EXISTS(30956, "MCP服务器名称已存在"),

    CHANNEL_CONFIG_NOT_FOUND(30960, "通道路由配置不存在"),

    CHANNEL_CONFIG_CHANNEL_ID_EXISTS(30961, "通道路由配置的 channelId 已存在"),

    SKILL_NOT_FOUND(30970, "技能不存在"),

    SKILL_NAME_EXISTS(30971, "技能名称已存在"),

    LLM_PROVIDER_NOT_FOUND(30401, "LLM提供方不存在"),

    LLM_PROVIDER_NAME_EXISTS(30402, "LLM提供方名称已存在"),

    LLM_PROVIDER_TYPE_NOT_SUPPORTED(30403, "不支持的LLM提供方类型"),

    LLM_PROVIDER_DISABLED(30404, "LLM提供方已禁用"),

    LLM_MODEL_NOT_FOUND(30411, "LLM模型不存在"),

    LLM_MODEL_NAME_EXISTS(30412, "LLM模型名称已存在"),

    LLM_MODEL_DISABLED(30413, "LLM模型已禁用"),

    LLM_MODEL_TYPE_NOT_SUPPORTED(30414, "不支持的模型类型"),

    LLM_ENCRYPTION_KEY_NOT_CONFIGURED(30421, "未配置API Key加密密钥(lambda.fusion.ai.security.encryption-key)"),

    LLM_API_KEY_DECRYPT_FAILED(30422, "API Key解密失败"),

    APP_NOT_FOUND(30751, "智能应用不存在"),

    APP_NAME_EXISTS(30752, "智能应用名称已存在"),

    APP_DISABLED(30753, "智能应用已禁用"),

    APP_RAG_MODE_INVALID(30754, "非法的知识库检索模式(GENERIC/AGENTIC/BOTH)"),

    APP_AUDIENCE_INVALID(30755, "非法的应用受众(B/C/ALL)"),

    APP_PUBLISH_CODE_CONFLICT(30756, "应用发布代码生成冲突,请重试"),

    APP_PUBLICATION_NOT_FOUND(30757, "发布链接无效或应用不存在"),

    APP_UNPUBLISHED(30758, "应用已下线"),

    KB_NOT_FOUND(30801, "知识库不存在"),

    KB_NAME_EXISTS(30802, "知识库名称已存在"),

    KB_DISABLED(30803, "知识库已禁用"),

    KB_EMBEDDING_MODEL_INVALID(30804, "知识库嵌入模型无效(需为已启用的EMBEDDING类型模型)"),

    KB_RAG_NOT_ENABLED(30805, "知识库检索功能未启用(lambda.fusion.ai.rag.enabled)"),

    KB_VECTOR_STORE_NOT_CONFIGURED(30806, "pgvector连接未配置(lambda.fusion.ai.rag.pgvector.jdbc-url)"),

    DOCUMENT_NOT_FOUND(30811, "知识库文档不存在"),

    DOCUMENT_TYPE_NOT_SUPPORTED(30812, "不支持的文档类型"),

    DOCUMENT_PARSE_FAILED(30813, "文档解析入库失败"),

    DOCUMENT_STORAGE_ERROR(30814, "文档原文件存储失败"),

    DOCUMENT_STORAGE_NOT_SUPPORTED(30815, "不支持的文档存储类型"),

    DOCUMENT_CHUNK_STRATEGY_INVALID(30816, "不支持的文档切割策略(AUTO/WHOLE/HEADING/PARAGRAPH/TOKEN)"),

    SUB_AGENT_NOT_FOUND(30821, "子代理不存在"),

    SUB_AGENT_NAME_EXISTS(30822, "子代理名称已存在"),

    SUB_AGENT_MODEL_INVALID(30823, "子代理绑定模型无效(不存在或未启用)"),

    SUB_AGENT_DISABLED(30824, "子代理已禁用"),

    SUB_AGENT_SESSION_UNAVAILABLE(30825, "子代理会话不存在或无权访问"),

    SUB_AGENT_RECOVERY_FAILED(30826, "子代理会话恢复失败"),

    SCHEDULED_TASK_NOT_FOUND(30830, "定时任务不存在"),

    SCHEDULED_TASK_NAME_EXISTS(30831, "定时任务名称已存在"),

    SCHEDULED_TASK_CONFIG_INVALID(30832, "定时任务调度配置无效"),

    CHAT_SESSION_NOT_FOUND(30201, "对话会话不存在"),

    CHAT_MESSAGE_NOT_FOUND(30202, "对话消息不存在"),

    CHAT_RUN_NOT_FOUND(30203, "对话运行不存在"),

    CHAT_RUN_REQUEST_CONFLICT(30204, "对话请求幂等键冲突"),

    CHAT_RUN_ALREADY_ACTIVE(30205, "会话已有正在运行的对话"),

    CHAT_RUN_EVENTS_EXPIRED(30207, "对话运行事件已过期"),

    CHAT_RUN_STATE_CONFLICT(30208, "对话运行状态冲突"),

    CHAT_RUN_CONFIRM_CONTEXT_UNAVAILABLE(30209, "确认工具上下文不可用"),

    CHAT_RUN_CONFIRM_CONTEXT_MISMATCH(30210, "确认工具上下文不一致"),

    ATTACHMENT_NOT_FOUND(30211, "对话附件不存在或已被使用"),

    ATTACHMENT_TYPE_NOT_SUPPORTED(30212, "不支持的附件类型"),

    ATTACHMENT_SIZE_EXCEEDED(30213, "附件大小超限"),

    ATTACHMENT_VISION_NOT_SUPPORTED(30214, "当前模型不支持图片输入"),

    ATTACHMENT_STORAGE_ERROR(30215, "附件存储失败"),

    ATTACHMENT_PREVIEW_TOKEN_INVALID(30216, "附件预览链接无效或已过期"),

    CHAT_RUN_AWAIT_CONFIRM_FAILED(30217, "对话运行进入待确认失败"),

    TOOL_RESULT_MEDIA_MATERIALIZATION_FAILED(30218, "工具结果媒体物化失败");

    private final Integer code;

    private final String message;
}
