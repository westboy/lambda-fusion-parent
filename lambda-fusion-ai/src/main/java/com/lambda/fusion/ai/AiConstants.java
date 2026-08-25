package com.lambda.fusion.ai;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.lambda.fusion.core.annotation.DictMapper;
import com.lambda.fusion.core.dict.DictEnum;
import java.util.List;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Getter;

public interface AiConstants {

    @Getter
    @AllArgsConstructor
    @DictMapper(dictName = "LLM_MODEL_TYPE", dictUsage = 0, dictDesc = "模型类型")
    enum ModelType implements DictEnum<Integer> {
        CHAT(1, "对话模型"),

        EMBEDDING(2, "嵌入模型"),

        IMAGE_GEN(3, "图像生成模型"),

        VIDEO_GEN(4, "视频生成模型"),

        AUDIO_GEN(5, "音频生成模型");

        @EnumValue
        private final Integer code;

        private final String label;
    }

    /**
     * AgentScope 提供方及本系统已接通的模型能力。该集合同时用于配置校验和管理端模型类型选项，避免各层重复维护
     * provider/modelType 组合。
     *
     * @author Jin
     */
    @Getter
    @AllArgsConstructor
    enum ProviderType {
        DASHSCOPE(
                "dashscope",
                "通义千问 DashScope",
                Set.of(
                        ModelType.CHAT,
                        ModelType.EMBEDDING,
                        ModelType.IMAGE_GEN,
                        ModelType.VIDEO_GEN,
                        ModelType.AUDIO_GEN)),

        OPENAI("openai", "OpenAI", Set.of(ModelType.CHAT, ModelType.EMBEDDING, ModelType.IMAGE_GEN)),

        OLLAMA("ollama", "Ollama 本地部署", Set.of(ModelType.CHAT, ModelType.EMBEDDING)),

        ANTHROPIC("anthropic", "Anthropic", Set.of(ModelType.CHAT)),

        GEMINI("gemini", "Google Gemini", Set.of(ModelType.CHAT)),

        DEEPSEEK("deepseek", "DeepSeek", Set.of(ModelType.CHAT)),

        GLM("glm", "智谱 GLM", Set.of(ModelType.CHAT)),

        KIMI("kimi", "月之暗面 Kimi", Set.of(ModelType.CHAT)),

        MINIMAX("minimax", "MiniMax", Set.of(ModelType.CHAT));

        private final String code;

        private final String label;

        private final Set<ModelType> supportedModelTypes;

        public boolean supports(ModelType modelType) {
            return modelType != null && supportedModelTypes.contains(modelType);
        }

        public static ProviderType of(String code) {
            if (code == null) {
                return null;
            }
            for (ProviderType type : values()) {
                if (type.code.equalsIgnoreCase(code)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * 智能应用类型，决定 Agent 的构建方式和可用能力。CHAT 仅使用数据库配置，不启用工作区；
     * WORKSPACE 为每个应用提供独立工作区，并支持技能、子代理、记忆、沙箱和自演化能力。
     */
    @Getter
    @AllArgsConstructor
    @DictMapper(dictName = "AI_APP_TYPE", dictUsage = 0, dictDesc = "智能应用类型")
    enum AppType implements DictEnum<String> {
        CHAT("CHAT", "聊天型"),

        WORKSPACE("WORKSPACE", "工作空间型");

        @EnumValue
        private final String code;

        private final String label;

        public static AppType of(String code) {
            if (code == null) {
                return null;
            }
            for (AppType type : values()) {
                if (type.code.equalsIgnoreCase(code)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * 工作区应用使用的沙箱后端。HOST 直接使用宿主文件系统，不提供 Shell 或沙箱隔离；
     * 其他类型使用对应的 AgentScope 沙箱后端，并启用隔离环境中的 Shell 工具。
     */
    @Getter
    @AllArgsConstructor
    enum SandboxBackend {
        HOST("HOST", "宿主文件系统"),

        DOCKER("DOCKER", "Docker 沙箱"),

        KUBERNETES("KUBERNETES", "Kubernetes 沙箱"),

        E2B("E2B", "E2B 云沙箱"),

        DAYTONA("DAYTONA", "Daytona 云沙箱"),

        AGENTRUN("AGENTRUN", "AgentRun 云沙箱");

        private final String code;

        private final String label;

        public static SandboxBackend of(String code) {
            if (code == null) {
                return null;
            }
            for (SandboxBackend backend : values()) {
                if (backend.code.equalsIgnoreCase(code)) {
                    return backend;
                }
            }
            return null;
        }
    }

    /**
     * 子代理工作区模式。ISOLATED 在父工作区的独立子目录中执行；SHARED 与主 Agent 共用工作区。
     */
    @Getter
    @AllArgsConstructor
    enum SubAgentWorkspaceMode {
        ISOLATED("ISOLATED", "独立工作区"),

        SHARED("SHARED", "共享工作区");

        private final String code;

        private final String label;

        public static SubAgentWorkspaceMode of(String code) {
            if (code == null) {
                return null;
            }
            for (SubAgentWorkspaceMode mode : values()) {
                if (mode.code.equalsIgnoreCase(code)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /**
     * {@code ai_sub_agent.category} 的取值。SUB_AGENT 可由主 Agent 路由调用；SCHEDULED_TASK
     * 仅由调度器触发，不参与主 Agent 路由。
     */
    @Getter
    @AllArgsConstructor
    enum SubAgentCategory {
        SUB_AGENT("SUB_AGENT", "专家子代理"),

        SCHEDULED_TASK("SCHEDULED_TASK", "定时任务");

        private final String code;

        private final String label;

        public static SubAgentCategory of(String code) {
            if (code == null) {
                return null;
            }
            for (SubAgentCategory category : values()) {
                if (category.code.equalsIgnoreCase(code)) {
                    return category;
                }
            }
            return null;
        }
    }

    /**
     * 定时任务调度模式（{@code ai_sub_agent.schedule_mode}），字面量对齐 AgentScope
     * {@code io.agentscope.extensions.scheduler.config.ScheduleMode}。
     */
    enum ScheduleMode {
        NONE,
        CRON,
        FIXED_RATE,
        FIXED_DELAY;

        public static ScheduleMode of(String code) {
            if (code == null) {
                return null;
            }
            for (ScheduleMode mode : values()) {
                if (mode.name().equalsIgnoreCase(code)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** 定时任务执行触发方式（{@code ai_scheduled_task_log.trigger_type}）。 */
    enum TaskTriggerType {
        /** 定时到点触发 */
        SCHEDULED,
        /** 手动触发 */
        MANUAL
    }

    /** 定时任务执行状态（{@code ai_scheduled_task_log.status}）。 */
    enum TaskExecStatus {
        SUCCESS,
        FAILED
    }

    /**
     * 知识库检索模式（应用级，{@code ai_app.rag_mode}）。GENERIC 中间件自动检索注入（稳定保底）；
     * AGENTIC 注册 {@code retrieve_knowledge} 工具由模型自主检索（省 token）；BOTH 两者兼得。
     * 空值按 GENERIC 处理（向后兼容）。
     */
    @Getter
    @AllArgsConstructor
    enum RagMode {
        GENERIC("GENERIC", "自动注入"),

        AGENTIC("AGENTIC", "工具自主检索"),

        BOTH("BOTH", "注入+工具");

        private final String code;

        private final String label;

        public static RagMode of(String code) {
            if (code == null) {
                return null;
            }
            for (RagMode mode : values()) {
                if (mode.code.equalsIgnoreCase(code)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /**
     * 应用受众。决定哪些已登录用户可使用应用：B/C 分别匹配 {@code AiProperties.audience.bRoles/cRoles}，
     * ALL 对所有已登录用户可见。创建/更新入口必须经 {@link #of(String)} 硬校验，非法值一律拒绝，
     * 不得静默落入某一分支。
     */
    @Getter
    @AllArgsConstructor
    enum AppAudience {
        B("B", "B端角色"),

        C("C", "C端角色"),

        ALL("ALL", "全部登录用户");

        private final String code;

        private final String label;

        public static AppAudience of(String code) {
            if (code == null) {
                return null;
            }
            for (AppAudience audience : values()) {
                if (audience.code.equalsIgnoreCase(code)) {
                    return audience;
                }
            }
            return null;
        }
    }

    /**
     * 应用发布状态。与运行开关 {@code enabled}、受众授权相互独立（见发布设计三态分离）；
     * 仅控制独立发布 URL 是否可解析展示。发布代码在下线时保留，重新发布不换链接。
     */
    @Getter
    @AllArgsConstructor
    enum PublishStatus {
        UNPUBLISHED("UNPUBLISHED", "未发布"),

        PUBLISHED("PUBLISHED", "已发布");

        private final String code;

        private final String label;

        public static PublishStatus of(String code) {
            if (code == null) {
                return null;
            }
            for (PublishStatus status : values()) {
                if (status.code.equalsIgnoreCase(code)) {
                    return status;
                }
            }
            return null;
        }
    }

    /**
     * 知识库文档入库状态。PENDING 已落库待入库；READY 解析切块入库完成；FAILED 入库失败（原因见 error_msg）。
     */
    @Getter
    @AllArgsConstructor
    enum DocumentStatus {
        PENDING("PENDING", "待入库"),

        READY("READY", "已入库"),

        FAILED("FAILED", "入库失败");

        private final String code;

        private final String label;
    }

    /**
     * 知识库文档切割策略。AUTO 自动（短文档整篇、按章节、其余按段落）；WHOLE 整篇不切；
     * HEADING 按标题层级；PARAGRAPH 按段落；TOKEN 按近似 token 数。
     */
    @Getter
    @AllArgsConstructor
    public enum DocumentChunkStrategy {
        AUTO("AUTO", "自动"),

        WHOLE("WHOLE", "整篇"),

        HEADING("HEADING", "按章节"),

        PARAGRAPH("PARAGRAPH", "按段落"),

        TOKEN("TOKEN", "按Token");

        private final String code;

        private final String label;

        public static DocumentChunkStrategy of(String code) {
            if (code == null) {
                return null;
            }
            for (DocumentChunkStrategy strategy : values()) {
                if (strategy.code.equalsIgnoreCase(code.trim())) {
                    return strategy;
                }
            }
            return null;
        }
    }

    /**
     * 知识库向量库后端类型。MEMORY 进程内存（零配置、重启丢失、单节点）；PGVECTOR PostgreSQL pgvector（生产推荐、多副本共享）。
     */
    @Getter
    @AllArgsConstructor
    enum VectorStoreType {
        MEMORY("MEMORY", "进程内存(重启丢失)"),

        PGVECTOR("PGVECTOR", "PostgreSQL pgvector");

        private final String code;

        private final String label;

        public static VectorStoreType of(String code) {
            if (code == null) {
                return null;
            }
            for (VectorStoreType type : values()) {
                if (type.code.equalsIgnoreCase(code)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * Agent 多轮状态的存储类型。MEMORY 和 FILE 适用于单节点；MYSQL、POSTGRES 和 REDIS
     * 支持多副本共享；OSS 和 COS 使用对象存储持久化。
     */
    @Getter
    @AllArgsConstructor
    enum StateStoreType {
        MEMORY("MEMORY", "进程内（重启丢失）"),

        FILE("FILE", "JSON 落盘（单节点，重启不丢）"),

        MYSQL("MYSQL", "MySQL（分布式）"),

        POSTGRES("POSTGRES", "PostgreSQL（分布式）"),

        REDIS("REDIS", "Redis（分布式）"),

        OSS("OSS", "对象存储 OSS/S3"),

        COS("COS", "腾讯云 COS");

        private final String code;

        private final String label;

        public static StateStoreType of(String code) {
            if (code == null) {
                return null;
            }
            for (StateStoreType type : values()) {
                if (type.code.equalsIgnoreCase(code)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * AgentScope 工作区的部署级存储类型。LOCAL 使用当前节点的文件系统，仅适用于单节点部署；
     * MYSQL 和 POSTGRES 通过 AgentScope 的分布式存储与远程文件系统支持多节点共享。
     */
    @Getter
    @AllArgsConstructor
    enum WorkspaceStorageType {
        LOCAL("LOCAL", "本地文件系统（单节点）"),

        MYSQL("MYSQL", "MySQL（分布式）"),

        POSTGRES("POSTGRES", "PostgreSQL（分布式）");

        private final String code;

        private final String label;

        public static WorkspaceStorageType of(String code) {
            if (code == null) {
                return null;
            }
            for (WorkspaceStorageType type : values()) {
                if (type.code.equalsIgnoreCase(code.trim())) {
                    return type;
                }
            }
            return null;
        }
    }

    /** 对话业务 Run 状态。 */
    public enum ChatRunStatus {
        RUNNING,
        COMPLETED,
        STOPPED,
        FAILED;

        private static final Set<ChatRunStatus> TERMINAL = Set.of(COMPLETED, STOPPED, FAILED);

        private static final List<String> TERMINAL_NAMES =
                TERMINAL.stream().map(Enum::name).toList();

        public boolean isTerminal() {
            return TERMINAL.contains(this);
        }

        public static boolean isTerminal(String status) {
            if (status == null) {
                return false;
            }
            try {
                return valueOf(status).isTerminal();
            } catch (IllegalArgumentException invalidStatus) {
                return false;
            }
        }

        /** 终态状态名列表，供 SQL in/notIn 条件复用，避免各处手写终态三元组。 */
        public static List<String> terminalNames() {
            return TERMINAL_NAMES;
        }
    }

    /** 对话运行结束原因，持久化时写入 finish_reason 列。 */
    public enum ChatRunFinishReason {
        SUCCESS,
        ERROR,
        USER_STOP
    }

    /** 对话运行失败码，持久化时写入 error_code 列。 */
    public enum ChatRunFailureCode {
        ERROR,
        START_FAILED,
        STATE_CONFLICT,
        AWAIT_CONFIRM_FAILED,
        INTERRUPTED
    }

    /** 工具调用状态，持久化字面量为小写 code。 */
    @Getter
    @AllArgsConstructor
    enum ChatRunToolStatus {
        RUNNING("running"),
        COMPLETE("complete"),
        ASKING("asking"),
        AWAITING_INPUT("awaiting_input"),
        UNKNOWN("unknown");

        private final String code;
    }
}
