package com.lambda.fusion.ai;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.lambda.fusion.core.annotation.DictMapper;
import com.lambda.fusion.core.dict.DictEnum;
import java.util.HashSet;
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
     * 平台当前编排的媒体创作操作。模型类型只描述输出数据类型，不能代替操作语义：同为视频模型，
     * 文生视频、单图生视频和首尾帧生视频需要调用 AgentScope 的不同官方工具。
     */
    @Getter
    enum MediaGenerationOperation {
        TEXT_TO_IMAGE(ModelType.IMAGE_GEN, "文生图", MediaToolName.TEXT_TO_IMAGE),

        TEXT_TO_VIDEO(ModelType.VIDEO_GEN, "文生视频", MediaToolName.TEXT_TO_VIDEO),

        IMAGE_TO_VIDEO(ModelType.VIDEO_GEN, "图生视频", MediaToolName.IMAGE_TO_VIDEO),

        FIRST_LAST_FRAME_TO_VIDEO(ModelType.VIDEO_GEN, "首尾帧生视频", MediaToolName.FIRST_LAST_FRAME_TO_VIDEO),

        TEXT_TO_SPEECH(ModelType.AUDIO_GEN, "文字转语音", MediaToolName.TEXT_TO_SPEECH);

        /** 数据库存储枚举名称；显式声明后不受全局 {@code EnumOrdinalTypeHandler} 影响。 */
        @EnumValue
        private final String code;

        private final ModelType modelType;

        private final String label;

        private final String toolName;

        MediaGenerationOperation(ModelType modelType, String label, String toolName) {
            this.code = name();
            this.modelType = modelType;
            this.label = label;
            this.toolName = toolName;
        }

        public String getKind() {
            return switch (modelType) {
                case IMAGE_GEN -> "image";
                case VIDEO_GEN -> "video";
                case AUDIO_GEN -> "audio";
                default -> throw new IllegalStateException("媒体创作操作不能使用非媒体模型类型: " + modelType);
            };
        }

        public static MediaGenerationOperation of(String code) {
            if (code == null) {
                return null;
            }
            for (MediaGenerationOperation operation : values()) {
                if (operation.name().equalsIgnoreCase(code.trim())) {
                    return operation;
                }
            }
            return null;
        }
    }

    /** {@link io.agentscope.core.tool.Tool} 注解需要编译期常量，工具名统一放在此处。 */
    interface MediaToolName {
        String TEXT_TO_IMAGE = "text_to_image";
        String TEXT_TO_VIDEO = "text_to_video";
        String IMAGE_TO_VIDEO = "image_to_video";
        String FIRST_LAST_FRAME_TO_VIDEO = "first_last_frame_to_video";
        String TEXT_TO_SPEECH = "text_to_audio";
    }

    /**
     * AgentScope 提供方。{@code supportedModelTypes} 表示厂商可配置的模型类型，供模型管理使用；
     * {@code runtimeModelTypes} 表示当前 AgentScope 运行时已经接通的模型类型，供应用绑定与工具装配使用。
     * 两者语义不同，不得用当前运行时实现范围裁剪厂商模型目录。
     *
     * @author Jin
     */
    @Getter
    enum ProviderType {
        DASHSCOPE(
                "dashscope",
                "通义千问 DashScope",
                Set.of(ModelType.CHAT, ModelType.EMBEDDING),
                Set.of(ModelType.CHAT, ModelType.EMBEDDING),
                Set.of(MediaGenerationOperation.values()),
                Set.of(MediaGenerationOperation.values())),

        OPENAI(
                "openai",
                "OpenAI",
                Set.of(ModelType.CHAT, ModelType.EMBEDDING),
                Set.of(ModelType.CHAT, ModelType.EMBEDDING),
                Set.of(MediaGenerationOperation.TEXT_TO_IMAGE),
                Set.of(MediaGenerationOperation.TEXT_TO_IMAGE)),

        OLLAMA(
                "ollama",
                "Ollama 本地部署",
                Set.of(ModelType.CHAT, ModelType.EMBEDDING),
                Set.of(ModelType.CHAT, ModelType.EMBEDDING),
                Set.of(),
                Set.of()),

        ANTHROPIC("anthropic", "Anthropic", Set.of(ModelType.CHAT), Set.of(ModelType.CHAT), Set.of(), Set.of()),

        GEMINI("gemini", "Google Gemini", Set.of(ModelType.CHAT), Set.of(ModelType.CHAT), Set.of(), Set.of()),

        DEEPSEEK("deepseek", "DeepSeek", Set.of(ModelType.CHAT), Set.of(ModelType.CHAT), Set.of(), Set.of()),

        GLM("glm", "智谱 GLM", Set.of(ModelType.CHAT), Set.of(ModelType.CHAT), Set.of(), Set.of()),

        KIMI("kimi", "月之暗面 Kimi", Set.of(ModelType.CHAT), Set.of(ModelType.CHAT), Set.of(), Set.of()),

        MINIMAX(
                "minimax",
                "MiniMax",
                Set.of(ModelType.CHAT),
                Set.of(ModelType.CHAT),
                Set.of(
                        MediaGenerationOperation.TEXT_TO_IMAGE,
                        MediaGenerationOperation.TEXT_TO_VIDEO,
                        MediaGenerationOperation.IMAGE_TO_VIDEO,
                        MediaGenerationOperation.TEXT_TO_SPEECH),
                Set.of());

        private final String code;

        private final String label;

        private final Set<ModelType> supportedCoreModelTypes;

        private final Set<ModelType> runtimeCoreModelTypes;

        private final Set<MediaGenerationOperation> supportedMediaOperations;

        private final Set<MediaGenerationOperation> runtimeMediaOperations;

        ProviderType(
                String code,
                String label,
                Set<ModelType> supportedCoreModelTypes,
                Set<ModelType> runtimeCoreModelTypes,
                Set<MediaGenerationOperation> supportedMediaOperations,
                Set<MediaGenerationOperation> runtimeMediaOperations) {
            this.code = code;
            this.label = label;
            this.supportedCoreModelTypes = supportedCoreModelTypes;
            this.runtimeCoreModelTypes = runtimeCoreModelTypes;
            this.supportedMediaOperations = supportedMediaOperations;
            this.runtimeMediaOperations = runtimeMediaOperations;
        }

        public boolean supports(ModelType modelType) {
            return modelType != null && getSupportedModelTypes().contains(modelType);
        }

        public boolean supportsRuntime(ModelType modelType) {
            return modelType != null && getRuntimeModelTypes().contains(modelType);
        }

        public boolean supports(MediaGenerationOperation operation) {
            return operation != null && supportedMediaOperations.contains(operation);
        }

        public boolean supportsRuntime(MediaGenerationOperation operation) {
            return operation != null && runtimeMediaOperations.contains(operation);
        }

        public Set<ModelType> getRuntimeModelTypes() {
            return modelTypes(runtimeCoreModelTypes, runtimeMediaOperations);
        }

        public Set<ModelType> getSupportedModelTypes() {
            return modelTypes(supportedCoreModelTypes, supportedMediaOperations);
        }

        private static Set<ModelType> modelTypes(
                Set<ModelType> coreTypes, Set<MediaGenerationOperation> mediaOperations) {
            HashSet<ModelType> types = new HashSet<>(coreTypes);
            mediaOperations.stream().map(MediaGenerationOperation::getModelType).forEach(types::add);
            return Set.copyOf(types);
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
     * 工作区应用使用的执行后端。HOST 使用 AgentScope 宿主文件系统；本地工作区可直接在宿主机执行 Shell，
     * 但不提供沙箱隔离。其他类型使用对应的 AgentScope 沙箱后端，并在隔离环境中执行 Shell。
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
     * 知识库向量库后端类型。MEMORY 进程内存（零配置、重启丢失、单节点）；PGVECTOR PostgreSQL pgvector；
     * OCEANBASE OceanBase MySQL 模式向量存储。
     */
    @Getter
    @AllArgsConstructor
    enum VectorStoreType {
        MEMORY("MEMORY", "进程内存(重启丢失)"),

        PGVECTOR("PGVECTOR", "PostgreSQL pgvector"),

        OCEANBASE("OCEANBASE", "OceanBase 向量存储");

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
