package com.lambda.fusion.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

/**
 * AI 模块运行参数。模型提供方和模型连接信息保存在 {@code ai_llm_provider}、{@code ai_llm_model} 表中，
 * {@code AiModelResolver} 根据 modelId 加载对应模型；API Key 以密文存储。
 *
 * @author Jin
 */
@Data
@Slf4j
@Validated
@ConfigurationProperties(prefix = "lambda.fusion.ai")
public class AiProperties {

    /** Agent 运行参数。 */
    @Valid
    @NestedConfigurationProperty
    private Runtime runtime = new Runtime();

    /** WORKSPACE 应用的工作区参数。 */
    @NestedConfigurationProperty
    private Workspace workspace = new Workspace();

    /** WORKSPACE 自演化应用的长期记忆参数。 */
    @Valid
    @NestedConfigurationProperty
    private Memory memory = new Memory();

    /** WORKSPACE 应用的沙箱参数。 */
    @NestedConfigurationProperty
    private Sandbox sandbox = new Sandbox();

    /** 应用可见性使用的 B/C 端角色映射。 */
    @NestedConfigurationProperty
    private Audience audience = new Audience();

    /** AgentScope Harness Gateway 与外部通道参数。 */
    @NestedConfigurationProperty
    private Gateway gateway = new Gateway();

    /** 多轮对话状态的存储参数。 */
    @NestedConfigurationProperty
    private StateStore stateStore = new StateStore();

    /** 技能仓库参数。 */
    @NestedConfigurationProperty
    private Skill skill = new Skill();

    /** 独立 Agent 工具参数。 */
    @Valid
    @NestedConfigurationProperty
    private Tools tools = new Tools();

    /** 知识库检索与向量存储参数。 */
    @Valid
    @NestedConfigurationProperty
    private Rag rag = new Rag();

    /** 对话、附件和后台 Run 参数。 */
    @Valid
    @NestedConfigurationProperty
    private Chat chat = new Chat();

    /** 集群部署参数。 */
    @NestedConfigurationProperty
    private Cluster cluster = new Cluster();

    /** 定时 Agent 任务调度参数。 */
    @Valid
    @NestedConfigurationProperty
    private Schedule schedule = new Schedule();

    /** 定时 Agent 任务调度参数。 */
    @Data
    public static class Schedule {

        /** 是否启用定时任务调度。 */
        private boolean enabled = false;

        /** Quartz 调度器实例标识(同 JVM 多调度器时区分;经 QuartzAgentSchedulerRegistry 关联)。 */
        private String schedulerId = "lambda-fusion-ai-scheduler";

        /** Quartz worker 线程数(并发执行 Agent 的上限),映射 spring.quartz 的 threadPool.threadCount。 */
        @Min(1)
        @Max(64)
        private int threadPoolSize = 4;

        /** Quartz 调度持久化所用的 dynamic-datasource 数据源名(默认主库 master)。 */
        private String datasource = "master";
    }

    /** 对话参数。 */
    @Data
    public static class Chat {

        /** 附件参数。 */
        @Valid
        private Attachment attachment = new Attachment();

        /** 后台对话 Run 的执行参数。 */
        @Valid
        private Run run = new Run();

        @Data
        public static class Run {

            /** SSE 连接超时时间，单位秒。 */
            @Min(1)
            private long connectionTimeoutSeconds = 300;

            /**
             * SSE 心跳间隔，单位秒；每个订阅连接周期性发送 SSE 注释行，防止代理/负载均衡
             * 因连接空闲误断。设为 0 关闭心跳。
             */
            @Min(0)
            private long heartbeatIntervalSeconds = 15;

            /** 单次 Agent 执行的最长时间，单位秒。 */
            @Min(1)
            private long maxRunDurationSeconds = 1800;

            /** 强制停止前等待协作式停止完成的时间，单位秒。 */
            @Min(1)
            @Max(300)
            private long stopGraceSeconds = 30;

            /** Run 结束后内存事件的保留时间，单位秒。 */
            @Min(1)
            private long terminalTtlSeconds = 600;

            /** 单个 Run 最多保留的事件数。 */
            @Min(64)
            private int maxEvents = 4096;

            /** 单个 Run 最多保留的事件字节数。 */
            @Min(65536)
            private long maxBytes = 8_388_608;

            /** 单个实例允许同时执行的 Run 数。 */
            @Min(1)
            private int maxActiveRuns = 200;

            /** 同一租户用户允许同时执行的 Run 数。 */
            @Min(1)
            private int maxActiveRunsPerUser = 4;

            /** 单个订阅者的事件队列容量。 */
            @Min(1)
            private int subscriberQueueSize = 256;

            /** 定时写入快照的间隔，单位秒。 */
            @Min(1)
            private long snapshotIntervalSeconds = 15;

            /** 是否向前端推送 Agent 推理过程(ThinkingBlock)事件。 */
            private boolean enableReasoning = true;

            /** Run 对账参数：孤儿运行与 HITL 等待超时的周期收敛。 */
            @Valid
            private Reconcile reconcile = new Reconcile();

            /** 孤儿判定阈值必须大于最大执行时长，否则活跃但长时间无事件的运行会被误收敛。 */
            @AssertTrue(
                    message =
                            "lambda.fusion.ai.chat.run.reconcile.orphan-timeout-seconds 必须大于 max-run-duration-seconds")
            public boolean isOrphanTimeoutAfterMaxRunDuration() {
                return reconcile.getOrphanTimeoutSeconds() > maxRunDurationSeconds;
            }

            /** Run 对账参数。 */
            @Data
            public static class Reconcile {

                /** 是否启用 Run 对账。 */
                private boolean enabled = true;

                /** 对账间隔，单位秒。 */
                @Min(10)
                private long intervalSeconds = 60;

                /** 单轮最多收敛的 Run 数。 */
                @Min(1)
                @Max(500)
                private int batchSize = 50;

                /**
                 * 孤儿运行判定阈值，单位秒：无待交互投影且快照超过该时长未刷新（活跃运行由周期
                 * 检查点持续刷新 updated_at，且交互超时会先自我终结，故该阈值须大于最大执行时长）。
                 */
                @Min(60)
                private long orphanTimeoutSeconds = 3600;

                /** HITL 待交互（待确认/待输入）最长等待时长，超时自动终结，单位秒。 */
                @Min(60)
                private long awaitInteractionTimeoutSeconds = 86_400;
            }
        }

        /**
         * 对话附件参数。原文件使用 {@code rag.document-storage} 指定的存储后端。
         */
        @Data
        public static class Attachment {

            /** 单个附件的大小上限，单位 MB。须与 {@code spring.servlet.multipart.max-file-size} 保持一致。 */
            private long maxFileSizeMb = 10;

            /** 每条消息允许上传的附件数。 */
            private int maxCount = 5;

            /** 每条消息允许完整内联的附件正文总字符预算；单个文档放不下时改由工具按需读取。 */
            @Min(1)
            @Max(65536)
            private int maxInlineChars = 8000;

            /** 附件读取工具单次允许返回的正文字符数。 */
            @Min(256)
            @Max(16384)
            private int maxToolReadChars = 4000;

            /** 支持上传的图片扩展名，须使用小写。 */
            private List<String> imageTypes = List.of("jpg", "jpeg", "png", "gif", "webp");

            /** 支持上传的文档扩展名，须使用小写。 */
            private List<String> docTypes = List.of("pdf", "doc", "docx", "txt", "md");

            /** 图片预览链接参数。 */
            private Preview preview = new Preview();

            /** 工具结果媒体物化参数。 */
            @Valid
            private GeneratedMedia generatedMedia = new GeneratedMedia();

            /** 工具结果中的图片、音频、视频和通用文件物化参数。 */
            @Data
            public static class GeneratedMedia {

                /** 单个生成文件的大小上限，单位 MB。 */
                @Min(1)
                @Max(1024)
                private long maxFileSizeMb = 100;

                /** 远程媒体连接超时，单位秒。 */
                @Min(1)
                private long connectTimeoutSeconds = 10;

                /** 单次远程媒体请求总超时，单位秒。 */
                @Min(1)
                private long requestTimeoutSeconds = 120;

                /** 远程媒体允许跟随的最大重定向次数。 */
                @Min(0)
                @Max(10)
                private int maxRedirects = 3;
            }

            /** 孤儿附件定时回收参数。 */
            @Valid
            private OrphanCleanup orphanCleanup = new OrphanCleanup();

            /**
             * 孤儿附件回收参数。孤儿指 {@code message_id IS NULL} 的附件：用户上传后未发送、
             * 工具生成后未绑定消息或落库与绑定之间发生失败的记录。回收按创建时间判定，先删
             * 存储文件后删数据库记录，失败留待下一轮重试。
             */
            @Data
            public static class OrphanCleanup {

                /** 是否启用定时回收。 */
                private boolean enabled = true;

                /** 未绑定附件保留时长，单位小时；超期后由定时任务回收。默认 7 天。 */
                @Min(1)
                private int retentionHours = 168;

                /** 单轮回收的最大附件数，防止单轮占用过长。 */
                @Min(1)
                private int batchSize = 200;

                /** 相邻两轮回收的间隔，单位分钟。 */
                @Min(5)
                private int intervalMinutes = 60;
            }

            /**
             * 图片预览链接参数。预览签名绑定附件 ID，并在到期后失效。
             */
            @Data
            public static class Preview {

                /**
                 * HMAC-SHA256 签名密钥。生产环境通过 {@code AI_ATTACHMENT_PREVIEW_SECRET} 注入；未配置时不生成附件预览和下载直链。
                 */
                private String secret;

                /** 预览签名的有效期，单位秒。 */
                @Min(1)
                private long ttlSeconds = 3600;
            }
        }
    }

    @Data
    public static class Runtime {

        /** 应用未设置 maxIters 时，ReAct Agent 使用的最大迭代次数。 */
        @Min(1)
        private int defaultMaxIters = 10;
    }

    /** WORKSPACE 自演化应用的长期记忆参数，仅在 {@code selfEvolve=true} 时生效。 */
    @Data
    public static class Memory {

        @Valid
        private Flush flush = new Flush();

        @Data
        public static class Flush {

            /** 记忆提取模式：ALWAYS / THROTTLED / NEVER。 */
            @NotNull
            private Mode mode = Mode.THROTTLED;

            /** THROTTLED 模式下两次记忆提取的最小间隔。 */
            @NotNull
            private Duration minGap = Duration.ofMinutes(10);

            @AssertTrue(message = "lambda.fusion.ai.memory.flush.min-gap 必须大于0")
            public boolean isMinGapValid() {
                return minGap != null && !minGap.isNegative() && !minGap.isZero();
            }

            public enum Mode {
                ALWAYS,
                THROTTLED,
                NEVER
            }
        }
    }

    @Data
    public static class Workspace {

        /**
         * 工作区根目录，默认使用 {@code ${user.home}/.agentscope/fusion}。本地应用保存在
         * {@code {root}/tenants/{tenantId}/apps/{appId}/}，远程存储的初始化模板保存在
         * {@code {root}/.remote-templates/{type}/tenants/{tenantId}/apps/{appId}/}。
         */
        private String root;

        /** 工作区存储参数，配置对所有应用生效。 */
        @Valid
        private Storage storage = new Storage();

        @Data
        public static class Storage {

            /** 存储类型：LOCAL / JDBC。 */
            private String type = "LOCAL";

            @Valid
            private AiProperties.Workspace.Storage.Jdbc jdbc = new Jdbc();

            @Data
            public static class Jdbc {

                /** dynamic-datasource 数据源名。 */
                private String datasource = "master";
            }
        }
    }

    @Data
    public static class Sandbox {

        /** 隔离范围：AGENT（应用级共享）/ USER / SESSION / GLOBAL。 */
        private String isolationScope = "AGENT";

        private Docker docker = new Docker();

        private Kubernetes kubernetes = new Kubernetes();

        private E2b e2b = new E2b();

        private Daytona daytona = new Daytona();

        private AgentRun agentRun = new AgentRun();

        @Data
        public static class Docker {
            private String image = "agentscope/python-sandbox:py311-slim";
            private String network = "none";
            private Long cpuCount;
            private Long memorySizeBytes;
            private String workspaceRoot = "/workspace";
        }

        @Data
        public static class Kubernetes {
            private String masterUrl;
            private String namespace = "default";
            private String image;
            private String workspaceRoot = "/workspace";
            private String serviceAccount;
            private String token;
        }

        @Data
        public static class E2b {
            private String apiKey;
            private String templateId;
            private String domain;
            private String workspaceRoot = "/workspace";
        }

        @Data
        public static class Daytona {
            private String apiKey;
            private String apiUrl;
            private String workspaceRoot = "/workspace";
        }

        @Data
        public static class AgentRun {
            private String apiKey;
            private String apiUrl;
            private String workspaceRoot = "/workspace";
        }
    }

    /** 用于区分 B 端和 C 端用户的角色配置。 */
    @Data
    public static class Audience {

        /** 用于判定 B 端用户的角色名。 */
        private List<String> bRoles = new ArrayList<>();

        /** 用于判定 C 端用户的角色名。 */
        private List<String> cRoles = new ArrayList<>();
    }

    @Data
    public static class Gateway {

        /** 是否通过 {@code HarnessGateway} 路由对话流。关闭后直接调用 {@code HarnessAgent#streamEvents}。 */
        private boolean enabled = true;
    }

    /**
     * Agent 状态存储。MEMORY 和 FILE 适用于单节点；MYSQL、POSTGRES、REDIS、OSS、COS
     * 可供多实例共享。选择扩展后端时，必须提供对应依赖和客户端配置。
     */
    @Data
    public static class StateStore {

        /** 存储类型：MEMORY / FILE / MYSQL / POSTGRES / REDIS / OSS / COS。 */
        private String type = "MEMORY";

        /** FILE 模式的状态目录。未设置时使用 {@code workspace.root/state}。 */
        private String root;

        @NestedConfigurationProperty
        private Mysql mysql = new Mysql();

        @NestedConfigurationProperty
        private Postgres postgres = new Postgres();

        @NestedConfigurationProperty
        private Redis redis = new Redis();

        private Oss oss = new Oss();

        private Cos cos = new Cos();

        /** MySQL 状态存储参数。 */
        @Data
        public static class Mysql {

            /** dynamic-datasource 数据源名。 */
            private String datasource = "master";

            /** 数据库名。 */
            private String database = "agentscope";

            /** 会话表名。 */
            private String table = "agentscope_sessions";

            /** 是否自动创建不存在的数据库和表。 */
            private boolean createIfNotExist = true;
        }

        /** PostgreSQL 状态存储参数。 */
        @Data
        public static class Postgres {

            /** dynamic-datasource 数据源名。 */
            private String datasource = "ai-postgres";

            /** Schema 名。 */
            private String schema = "agentscope";

            /** 会话表名。 */
            private String table = "agentscope_sessions";

            /** 是否自动创建不存在的 Schema 和表。 */
            private boolean createIfNotExist = true;
        }

        /** Redis 状态存储，使用 Spring 容器中的 {@code RedissonClient}。 */
        @Data
        public static class Redis {

            /** Redis key 前缀。 */
            private String keyPrefix = "agentscope:session:";
        }

        /** 阿里云 OSS 状态存储。 */
        @Data
        public static class Oss {

            private String endpoint;

            private String accessKeyId;

            private String accessKeySecret;

            private String bucketName;

            /** 对象 key 前缀。 */
            private String keyPrefix = "agentscope/state/";
        }

        /** 腾讯云 COS 状态存储。 */
        @Data
        public static class Cos {

            /** COS 地域，例如 {@code ap-guangzhou}。 */
            private String region;

            private String secretId;

            private String secretKey;

            private String bucketName;

            /** 对象 key 前缀。 */
            private String keyPrefix = "agentscope/state/";
        }
    }

    /** 与应用、技能和模型表无关的独立 Agent 工具。 */
    @Data
    public static class Tools {

        @Valid
        private QwenImage qwenImage = new QwenImage();

        /** Qwen Image 普通工具配置。 */
        @Data
        public static class QwenImage {

            /** 是否向对话 Agent 注册 qwen_text_to_image。 */
            private boolean enabled = false;

            /** DashScope API Key，推荐通过环境变量注入。 */
            private String apiKey;

            /** DashScope API 基础地址，也可直接填写完整的多模态生成地址。 */
            private String baseUrl;

            /** 该工具固定使用的 Qwen Image 提供方模型名。 */
            private String modelName;

            /** 建立 DashScope 连接的超时时间，单位秒。 */
            @Min(1)
            @Max(120)
            private long connectTimeoutSeconds = 10;

            /** 单次 DashScope 请求的总超时时间，单位秒。 */
            @Min(1)
            @Max(1800)
            private long requestTimeoutSeconds = 120;

            @AssertTrue(
                    message = "启用 Qwen Image 工具时必须配置 lambda.fusion.ai.tools.qwen-image.api-key、base-url 和 model-name")
            public boolean isConfigurationValid() {
                return !enabled
                        || (apiKey != null
                                && !apiKey.isBlank()
                                && baseUrl != null
                                && !baseUrl.isBlank()
                                && modelName != null
                                && !modelName.isBlank());
            }
        }
    }

    /**
     * 技能市场的仓库参数。仓库类型为 NONE 或所选后端不可用时，不启用技能市场。
     */
    @Data
    public static class Skill {

        private Repository repository = new Repository();

        @Data
        public static class Repository {

            /** 仓库类型：MYSQL / POSTGRES / GIT / NACOS / NONE。 */
            private String type = "MYSQL";

            @NestedConfigurationProperty
            private Mysql mysql = new Mysql();

            @NestedConfigurationProperty
            private Postgres postgres = new Postgres();

            private Git git = new Git();

            private Nacos nacos = new Nacos();

            /** MySQL 技能仓库。 */
            @Data
            public static class Mysql {

                /** dynamic-datasource 数据源名。 */
                private String datasource = "master";

                /** 是否自动创建不存在的数据库和表。 */
                private boolean createIfNotExist = true;

                /** 是否允许管理端写入。 */
                private boolean writeable = true;
            }

            /** PostgreSQL 技能仓库。 */
            @Data
            public static class Postgres {

                /** dynamic-datasource 数据源名。 */
                private String datasource = "ai-postgres";

                /** 是否自动创建不存在的 Schema 和表。 */
                private boolean createIfNotExist = true;

                /** 是否允许管理端写入。 */
                private boolean writeable = true;
            }

            /** 只读的 Git 技能仓库。 */
            @Data
            public static class Git {

                private String remoteUrl;

                private String branch;

                /** 本地克隆目录。未设置时使用 {@code ${workspace.root}/skill-git}。 */
                private String localPath;

                private String source;
            }

            /** Nacos 技能仓库。 */
            @Data
            public static class Nacos {

                private String serverAddr;

                private String namespaceId;

                private String accessKey;

                private String secretKey;
            }
        }
    }

    /**
     * 知识库检索参数。向量存储可使用进程内存、pgvector 或 OceanBase 向量存储。
     */
    @Data
    public static class Rag {

        /** 是否为对话启用知识库检索。 */
        private boolean enabled = false;

        /** 新建知识库未指定检索策略时使用的值：FULL_TEXT / VECTOR / HYBRID。 */
        private String defaultRetrievalMode = "VECTOR";

        /** 知识库未指定检索条数时使用的值。 */
        private int defaultLimit = 5;

        /** 知识库未指定分数阈值时使用的值。 */
        private double defaultScoreThreshold = 0.5;

        /** 每轮对话可注入的检索文本上限，单位字符。 */
        private int maxInjectChars = 4000;

        /** 文档切块参数。 */
        @Valid
        @NestedConfigurationProperty
        private Chunking chunking = new Chunking();

        /** 向量存储参数。 */
        private Store store = new Store();

        /** 全文索引参数。 */
        @NestedConfigurationProperty
        private FullText fullText = new FullText();

        /** 知识库文档和对话附件共用的原文件存储。 */
        @NestedConfigurationProperty
        private DocumentStorage documentStorage = new DocumentStorage();

        @AssertTrue(message = "lambda.fusion.ai.rag.default-retrieval-mode 必须为 FULL_TEXT、VECTOR 或 HYBRID")
        public boolean isDefaultRetrievalModeValid() {
            return AiConstants.KnowledgeRetrievalMode.of(defaultRetrievalMode) != null;
        }

        @AssertTrue(message = "lambda.fusion.ai.rag.full-text.type 必须为 LUCENE 或 OCEANBASE")
        public boolean isFullTextTypeValid() {
            return AiConstants.FullTextStoreType.of(fullText.getType()) != null;
        }

        /** 全文索引类型：LUCENE / OCEANBASE。 */
        @Data
        public static class FullText {

            private String type = "LUCENE";

            /** OceanBase 全文索引共享表名，所有知识库共用并按 kbId 隔离。 */
            private String tableName = "ai_kb_full_text_chunk";
        }

        /** 文档切块的全局参数。 */
        @Data
        public static class Chunking {

            /** 目标块大小；TOKEN 策略下表示近似 token 数，其余策略下表示字符数。 */
            @Min(1)
            private int chunkSize = 512;

            /** 相邻块的重叠大小；TOKEN 策略下表示近似 token 数，其余策略下表示字符数。 */
            @Min(0)
            private int overlapSize = 50;

            /** AUTO 策略整篇保留的字符上限。 */
            @Min(1)
            private int wholeDocumentMaxChars = 2000;

            @AssertTrue(message = "lambda.fusion.ai.rag.chunking.overlap-size 必须小于 chunk-size")
            public boolean isOverlapSizeValid() {
                return overlapSize < chunkSize;
            }
        }

        /** 向量存储类型：MEMORY / PGVECTOR / OCEANBASE。 */
        @Data
        public static class Store {

            private String type = "MEMORY";

            @NestedConfigurationProperty
            private PgVector pgVector = new PgVector();

            @NestedConfigurationProperty
            private OceanBase oceanBase = new OceanBase();
        }

        /** 文档原文件存储。 */
        @Data
        public static class DocumentStorage {

            /** 存储类型：LOCAL / OSS。 */
            private String type = "LOCAL";

            @NestedConfigurationProperty
            private Local local = new Local();

            @NestedConfigurationProperty
            private Oss oss = new Oss();

            @Data
            public static class Local {

                /**
                 * 原文件根目录。未设置时使用 {@code {workspace.root}/knowledge-files}；工作区根目录也未设置时，
                 * 使用 {@code ~/.agentscope/fusion/knowledge-files}。
                 */
                private String root;
            }

            @Data
            public static class Oss {

                /** {@code OssClientManager} 中的客户端名。未设置时使用默认客户端。 */
                private String clientName;

                /** 对象 key 前缀。 */
                private String keyPrefix = "ai/knowledge/";
            }
        }

        /** pgvector 连接参数，应通过环境变量注入。 */
        @Data
        public static class PgVector {

            private String jdbcUrl;

            private String username;

            private String password;

            /** Schema 名。 */
            private String schema = "public";
        }

        /** OceanBase MySQL 模式向量存储连接参数。 */
        @Data
        public static class OceanBase {

            private String jdbcUrl;

            private String username;

            private String password;
        }
    }

    @Data
    public static class Cluster {

        /** 是否广播 Agent 缓存失效事件。多实例部署应开启；Dubbo 不可用时仅发布本地事件。 */
        private boolean invalidationBroadcast = true;
    }
}
