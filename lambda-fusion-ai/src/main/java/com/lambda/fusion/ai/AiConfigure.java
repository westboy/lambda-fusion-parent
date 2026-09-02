package com.lambda.fusion.ai;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.exception.NacosException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.lambda.cloud.lucene.manager.LuceneManagerFactory;
import com.lambda.cloud.oss.manager.OssClientManager;
import com.lambda.fusion.ai.AiConstants.WorkspaceStorageType;
import com.lambda.fusion.ai.apps.model.entity.AppEntity;
import com.lambda.fusion.ai.channel.service.ChannelConfigService;
import com.lambda.fusion.ai.chat.attachment.ChatAttachmentOrphanCleaner;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import com.lambda.fusion.ai.rag.mapper.KnowledgeDocumentMapper;
import com.lambda.fusion.ai.rag.runtime.SimpleKnowledgeAdapter;
import com.lambda.fusion.ai.rag.service.DocumentIngestionService;
import com.lambda.fusion.ai.rag.service.KnowledgeBaseService;
import com.lambda.fusion.ai.rag.store.KnowledgeFullTextIndex;
import com.lambda.fusion.ai.rag.store.LuceneKnowledgeIndex;
import com.lambda.fusion.ai.rag.store.OceanBaseFullTextIndex;
import com.lambda.fusion.ai.runtime.AgentFactory;
import com.lambda.fusion.ai.runtime.EmbeddingModelResolver;
import com.lambda.fusion.ai.runtime.event.ConfigChangedEvent;
import com.lambda.fusion.ai.runtime.event.DubboConfigInvalidationBroadcaster;
import com.lambda.fusion.ai.runtime.event.RemoteAgentCacheInvalidationService;
import com.lambda.fusion.ai.runtime.gateway.ChannelAgentResolver;
import com.lambda.fusion.ai.runtime.gateway.ChannelBootstrap;
import com.lambda.fusion.ai.runtime.gateway.ChannelConfigApplier;
import com.lambda.fusion.ai.runtime.gateway.ChannelLifecycle;
import com.lambda.fusion.ai.runtime.gateway.FusionSubagentGateway;
import com.lambda.fusion.ai.runtime.sandbox.SandboxBackendProvider;
import com.lambda.fusion.ai.runtime.sandbox.SandboxSpecResolver;
import com.lambda.fusion.ai.runtime.state.StateStoreDataSources;
import com.lambda.fusion.ai.runtime.state.StateStoreProvider;
import com.lambda.fusion.ai.runtime.tools.QwenImageGenerationTool;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceDistributedStoreProvider;
import com.lambda.fusion.ai.runtime.workspace.WorkspaceStorage;
import com.lambda.fusion.ai.schedule.AgentExecutionJobListener;
import com.lambda.fusion.ai.skill.SkillRepositoryProvider;
import com.lambda.fusion.ai.storage.AiFileStorage;
import com.lambda.fusion.ai.storage.AiFileStorageResolver;
import com.lambda.fusion.ai.storage.LocalAiFileStorage;
import com.lambda.fusion.ai.storage.OssAiFileStorage;
import com.lambda.fusion.authority.api.RemoteUserService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.region.Region;
import io.agentscope.core.nacos.skill.NacosSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;
import io.agentscope.core.skill.repository.postgresql.PostgresSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.channel.feishu.FeishuCallbackController;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.extensions.channel.wecom.WeComCallbackController;
import io.agentscope.extensions.channel.wecom.WeComChannel;
import io.agentscope.extensions.cos.CosAgentStateStore;
import io.agentscope.extensions.mysql.MysqlDistributedStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.oss.OssAgentStateStore;
import io.agentscope.extensions.postgresql.PostgresDistributedStore;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.sandbox.agentrun.AgentRunFilesystemSpec;
import io.agentscope.extensions.sandbox.daytona.DaytonaFilesystemSpec;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.extensions.sandbox.kubernetes.KubernetesFilesystemSpec;
import io.agentscope.extensions.scheduler.quartz.QuartzAgentScheduler;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.InMemorySubagentRegistry;
import io.agentscope.harness.agent.gateway.StoreBackedSubagentRegistry;
import io.agentscope.harness.agent.gateway.SubagentRegistry;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelFactory;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.annotation.DubboService;
import org.jspecify.annotations.NonNull;
import org.mybatis.spring.annotation.MapperScan;
import org.quartz.Scheduler;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

@Slf4j
@EnableAsync
@Configuration
@MapperScan(basePackages = {"com.lambda.fusion.ai.**.mapper"})
@ComponentScan(basePackageClasses = AiConfigure.class)
@EnableConfigurationProperties(AiProperties.class)
public class AiConfigure {

    /** 独立 Qwen Image 工具；是否存在仅由自身配置决定。 */
    @Configuration
    @ConditionalOnProperty(prefix = "lambda.fusion.ai.tools.qwen-image", name = "enabled", havingValue = "true")
    public static class QwenImageToolConfiguration {

        @Bean
        @ConditionalOnMissingBean(QwenImageGenerationTool.class)
        public QwenImageGenerationTool qwenImageGenerationTool(AiProperties properties) {
            AiProperties.Tools.QwenImage config = properties.getTools().getQwenImage();
            return QwenImageGenerationTool.create(
                    config.getApiKey(),
                    config.getBaseUrl(),
                    config.getModelName(),
                    Duration.ofSeconds(config.getConnectTimeoutSeconds()),
                    Duration.ofSeconds(config.getRequestTimeoutSeconds()));
        }
    }

    /** 孤儿对话附件定时回收；默认开启，可用 {@code orphan-cleanup.enabled} 关闭。 */
    @Configuration
    @ConditionalOnProperty(
            prefix = "lambda.fusion.ai.chat.attachment.orphan-cleanup",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public static class AttachmentOrphanCleanupConfiguration {

        @Bean
        public ChatAttachmentOrphanCleaner chatAttachmentOrphanCleaner(
                ChatAttachmentService attachmentService, AiProperties properties) {
            ChatAttachmentOrphanCleaner cleaner = new ChatAttachmentOrphanCleaner(attachmentService, properties);
            cleaner.start();
            return cleaner;
        }
    }

    @Configuration
    @ConditionalOnProperty(
            prefix = "lambda.fusion.ai.gateway",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public static class GatewayConfiguration {

        @Bean
        public ChannelManager channelManager() {
            return new ChannelManager();
        }

        @Bean
        public SubagentRegistry fusionSubagentRegistry(WorkspaceStorage workspaceStorage) {
            return workspaceStorage
                    .distributedStore()
                    .<SubagentRegistry>map(store -> new StoreBackedSubagentRegistry(store.baseStore()))
                    .orElseGet(InMemorySubagentRegistry::new);
        }

        @Bean
        public FusionSubagentGateway fusionSubagentGateway(
                ObjectProvider<AgentFactory> agentFactoryProvider,
                WorkspaceStorage workspaceStorage,
                SubagentRegistry subagentRegistry) {
            return new FusionSubagentGateway(agentFactoryProvider, workspaceStorage, subagentRegistry);
        }

        @Bean
        public HarnessGateway fusionGateway(ChannelManager channelManager) {
            return HarnessGateway.create(channelManager);
        }

        @Bean
        public ChannelLifecycle channelLifecycle(
                ChannelManager channelManager,
                HarnessGateway gateway,
                List<Channel> channels,
                ChannelConfigApplier channelConfigApplier) {
            return new ChannelLifecycle(channelManager, gateway, channels, channelConfigApplier);
        }

        @Bean
        public ChannelBootstrap channelBootstrap(
                ChannelManager channelManager,
                HarnessGateway gateway,
                ChannelConfigService channelConfigService,
                Map<String, ChannelFactory> factories,
                ChannelAgentResolver channelAgentResolver) {
            return new ChannelBootstrap(channelManager, gateway, channelConfigService, factories, channelAgentResolver);
        }
    }

    @Configuration
    public static class SandboxConfig {

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.sandbox.agentrun.AgentRunFilesystemSpec")
        @RequiredArgsConstructor
        public static class AgentRunSandboxConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public SandboxBackendProvider agentRunSandboxProvider() {
                return new SandboxBackendProvider() {
                    @Override
                    public AiConstants.SandboxBackend backend() {
                        return AiConstants.SandboxBackend.AGENTRUN;
                    }

                    @Override
                    public SandboxFilesystemSpec create(AppEntity app, Path hostWorkspace) {
                        AiProperties.Sandbox.AgentRun cfg =
                                aiProperties.getSandbox().getAgentRun();
                        return new AgentRunFilesystemSpec()
                                .apiKey(cfg.getApiKey())
                                .workspaceRoot(cfg.getWorkspaceRoot())
                                .isolationScope(SandboxSpecResolver.parseIsolationScope(aiProperties));
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.sandbox.daytona.DaytonaFilesystemSpec")
        @RequiredArgsConstructor
        public static class DaytonaSandboxConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public SandboxBackendProvider daytonaSandboxProvider() {
                return new SandboxBackendProvider() {
                    @Override
                    public AiConstants.SandboxBackend backend() {
                        return AiConstants.SandboxBackend.DAYTONA;
                    }

                    @Override
                    public SandboxFilesystemSpec create(AppEntity app, Path hostWorkspace) {
                        AiProperties.Sandbox.Daytona cfg =
                                aiProperties.getSandbox().getDaytona();
                        return new DaytonaFilesystemSpec()
                                .apiKey(cfg.getApiKey())
                                .workspaceRoot(cfg.getWorkspaceRoot())
                                .isolationScope(SandboxSpecResolver.parseIsolationScope(aiProperties));
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec")
        @RequiredArgsConstructor
        public static class DockerSandboxConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public SandboxBackendProvider dockerSandboxProvider() {
                return new SandboxBackendProvider() {
                    @Override
                    public AiConstants.SandboxBackend backend() {
                        return AiConstants.SandboxBackend.DOCKER;
                    }

                    @Override
                    public SandboxFilesystemSpec create(AppEntity app, Path hostWorkspace) {
                        AiProperties.Sandbox.Docker cfg =
                                aiProperties.getSandbox().getDocker();
                        DockerFilesystemSpec spec = new DockerFilesystemSpec()
                                .image(cfg.getImage())
                                .workspaceRoot(cfg.getWorkspaceRoot())
                                .network(cfg.getNetwork());
                        if (cfg.getCpuCount() != null) {
                            spec.cpuCount(cfg.getCpuCount());
                        }
                        if (cfg.getMemorySizeBytes() != null) {
                            spec.memorySizeBytes(cfg.getMemorySizeBytes());
                        }
                        return spec.isolationScope(SandboxSpecResolver.parseIsolationScope(aiProperties));
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec")
        @RequiredArgsConstructor
        public static class E2bSandboxConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public SandboxBackendProvider e2bSandboxProvider() {
                return new SandboxBackendProvider() {
                    @Override
                    public AiConstants.SandboxBackend backend() {
                        return AiConstants.SandboxBackend.E2B;
                    }

                    @Override
                    public SandboxFilesystemSpec create(AppEntity app, Path hostWorkspace) {
                        AiProperties.Sandbox.E2b cfg = aiProperties.getSandbox().getE2b();
                        return new E2bFilesystemSpec()
                                .apiKey(cfg.getApiKey())
                                .templateId(cfg.getTemplateId())
                                .domain(cfg.getDomain())
                                .workspaceRoot(cfg.getWorkspaceRoot())
                                .isolationScope(SandboxSpecResolver.parseIsolationScope(aiProperties));
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.sandbox.kubernetes.KubernetesFilesystemSpec")
        @RequiredArgsConstructor
        public static class KubernetesSandboxConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public SandboxBackendProvider kubernetesSandboxProvider() {
                return new SandboxBackendProvider() {
                    @Override
                    public AiConstants.SandboxBackend backend() {
                        return AiConstants.SandboxBackend.KUBERNETES;
                    }

                    @Override
                    public SandboxFilesystemSpec create(AppEntity app, Path hostWorkspace) {
                        AiProperties.Sandbox.Kubernetes cfg =
                                aiProperties.getSandbox().getKubernetes();
                        return new KubernetesFilesystemSpec()
                                .namespace(cfg.getNamespace())
                                .workspaceRoot(cfg.getWorkspaceRoot())
                                .kubernetesClient(new KubernetesClientBuilder().build())
                                .isolationScope(SandboxSpecResolver.parseIsolationScope(aiProperties));
                    }
                };
            }
        }
    }

    /** Workspace 分布式存储后端装配。LOCAL 模式不创建外部存储连接。 */
    @Configuration
    public static class WorkspaceStorageConfig {

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.mysql.MysqlDistributedStore")
        @ConditionalOnBean(DataSource.class)
        @RequiredArgsConstructor
        public static class MysqlWorkspaceStorageConfiguration {

            private final AiProperties aiProperties;
            private final DataSource dataSource;

            @Bean
            public WorkspaceDistributedStoreProvider mysqlWorkspaceDistributedStoreProvider() {
                return new WorkspaceDistributedStoreProvider() {
                    @Override
                    public WorkspaceStorageType type() {
                        return WorkspaceStorageType.MYSQL;
                    }

                    @Override
                    public io.agentscope.harness.agent.DistributedStore create() {
                        AiProperties.Workspace.Storage.Mysql cfg =
                                aiProperties.getWorkspace().getStorage().getMysql();
                        DataSource ds = StateStoreDataSources.resolveNamed(dataSource, cfg.getDatasource());
                        return MysqlDistributedStore.create(ds);
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.postgresql.PostgresDistributedStore")
        @ConditionalOnBean(DataSource.class)
        @RequiredArgsConstructor
        public static class PostgresWorkspaceStorageConfiguration {

            private final AiProperties aiProperties;
            private final DataSource dataSource;

            @Bean
            public WorkspaceDistributedStoreProvider postgresWorkspaceDistributedStoreProvider() {
                return new WorkspaceDistributedStoreProvider() {
                    @Override
                    public WorkspaceStorageType type() {
                        return WorkspaceStorageType.POSTGRES;
                    }

                    @Override
                    public io.agentscope.harness.agent.DistributedStore create() {
                        AiProperties.Workspace.Storage.Postgres cfg =
                                aiProperties.getWorkspace().getStorage().getPostgres();
                        DataSource ds = StateStoreDataSources.resolveNamed(dataSource, cfg.getDatasource());
                        return PostgresDistributedStore.create(ds);
                    }
                };
            }
        }
    }

    /**
     * 分布式状态存储后端装配
     */
    @Configuration
    public static class StateStoreConfig {

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.postgresql.state.PostgresAgentStateStore")
        @ConditionalOnBean(DataSource.class)
        @RequiredArgsConstructor
        public static class PostgresStateStoreConfiguration {

            private final AiProperties aiProperties;
            private final DataSource dataSource;

            @Bean
            public StateStoreProvider postgresStateStoreProvider() {
                return new StateStoreProvider() {
                    @Override
                    public AiConstants.StateStoreType type() {
                        return AiConstants.StateStoreType.POSTGRES;
                    }

                    @Override
                    public AgentStateStore create() {
                        AiProperties.StateStore.Postgres cfg =
                                aiProperties.getStateStore().getPostgres();
                        DataSource ds = StateStoreDataSources.resolveNamed(dataSource, cfg.getDatasource());
                        return new PostgresAgentStateStore(
                                ds, cfg.getSchema(), cfg.getTable(), cfg.isCreateIfNotExist());
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.mysql.state.MysqlAgentStateStore")
        @ConditionalOnBean(DataSource.class)
        @RequiredArgsConstructor
        public static class MysqlStateStoreConfiguration {

            private final AiProperties aiProperties;
            private final DataSource dataSource;

            @Bean
            public StateStoreProvider mysqlStateStoreProvider() {
                return new StateStoreProvider() {
                    @Override
                    public AiConstants.StateStoreType type() {
                        return AiConstants.StateStoreType.MYSQL;
                    }

                    @Override
                    public AgentStateStore create() {
                        AiProperties.StateStore.Mysql cfg =
                                aiProperties.getStateStore().getMysql();
                        DataSource ds = StateStoreDataSources.resolveNamed(dataSource, cfg.getDatasource());
                        return new MysqlAgentStateStore(
                                ds, cfg.getDatabase(), cfg.getTable(), cfg.isCreateIfNotExist());
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.redis.state.RedisAgentStateStore")
        @ConditionalOnBean(RedissonClient.class)
        @RequiredArgsConstructor
        public static class RedisStateStoreConfiguration {

            private final AiProperties aiProperties;
            private final ObjectProvider<RedissonClient> redissonClientProvider;

            @Bean
            public StateStoreProvider redisStateStoreProvider() {
                return new StateStoreProvider() {
                    @Override
                    public AiConstants.StateStoreType type() {
                        return AiConstants.StateStoreType.REDIS;
                    }

                    @Override
                    public AgentStateStore create() {
                        RedissonClient client = redissonClientProvider.getIfAvailable();
                        if (client == null) {
                            throw new IllegalStateException("RedissonClient 未配置，无法构建 Redis 状态存储");
                        }
                        AiProperties.StateStore.Redis cfg =
                                aiProperties.getStateStore().getRedis();
                        return RedisAgentStateStore.builder()
                                .redissonClient(client)
                                .keyPrefix(cfg.getKeyPrefix())
                                .build();
                    }
                };
            }
        }

        // OssAgentStateStore 被 shade 进 agentscope.jar 恒在场，真正决定能否构建的是阿里云 OSS SDK
        @Configuration
        @ConditionalOnClass(name = {"io.agentscope.extensions.oss.OssAgentStateStore", "com.aliyun.oss.OSS"})
        @RequiredArgsConstructor
        public static class OssStateStoreConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public StateStoreProvider ossStateStoreProvider() {
                return new StateStoreProvider() {
                    @Override
                    public AiConstants.StateStoreType type() {
                        return AiConstants.StateStoreType.OSS;
                    }

                    @Override
                    public AgentStateStore create() {
                        AiProperties.StateStore.Oss cfg =
                                aiProperties.getStateStore().getOss();
                        OSS ossClient = new OSSClientBuilder()
                                .build(cfg.getEndpoint(), cfg.getAccessKeyId(), cfg.getAccessKeySecret());
                        return OssAgentStateStore.builder()
                                .ossClient(ossClient)
                                .bucketName(cfg.getBucketName())
                                .keyPrefix(cfg.getKeyPrefix())
                                .build();
                    }
                };
            }
        }

        // CosAgentStateStore 被 shade 进 agentscope.jar 恒在场，真正决定能否构建的是腾讯 COS SDK
        @Configuration
        @ConditionalOnClass(name = {"io.agentscope.extensions.cos.CosAgentStateStore", "com.qcloud.cos.COSClient"})
        @RequiredArgsConstructor
        public static class CosStateStoreConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public StateStoreProvider cosStateStoreProvider() {
                return new StateStoreProvider() {
                    @Override
                    public AiConstants.StateStoreType type() {
                        return AiConstants.StateStoreType.COS;
                    }

                    @Override
                    public AgentStateStore create() {
                        AiProperties.StateStore.Cos cfg =
                                aiProperties.getStateStore().getCos();
                        BasicCOSCredentials cred = new BasicCOSCredentials(cfg.getSecretId(), cfg.getSecretKey());
                        ClientConfig clientConfig = new ClientConfig(new Region(cfg.getRegion()));
                        COSClient cosClient = new COSClient(cred, clientConfig);
                        return CosAgentStateStore.builder()
                                .cosClient(cosClient)
                                .bucketName(cfg.getBucketName())
                                .keyPrefix(cfg.getKeyPrefix())
                                .build();
                    }
                };
            }
        }
    }

    /**
     * 装配消息渠道适配器。{@link ChannelFactory} 的 Bean 名必须与 {@code ai_channel_config.type} 一致，
     * 供 {@code ChannelBootstrap} 按类型查找。飞书和企业微信还需注册回调控制器以接收入站 Webhook。
     */
    @Configuration
    public static class ChannelAdapterConfig {

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.channel.dingtalk.DingTalkChannel")
        public static class DingTalkFactoryConfiguration {

            @Bean(DingTalkChannel.TYPE)
            public ChannelFactory dingTalkChannelFactory() {
                return DingTalkChannel::fromProperties;
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.channel.feishu.FeishuChannel")
        public static class FeishuFactoryConfiguration {

            @Bean(FeishuChannel.TYPE)
            public ChannelFactory feishuChannelFactory() {
                return FeishuChannel::fromProperties;
            }

            // 提供飞书 Webhook 入站端点；渠道启动时会自行注册到 FeishuChannelRegistry。
            @Bean
            public FeishuCallbackController feishuCallbackController() {
                return new FeishuCallbackController();
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.extensions.channel.wecom.WeComChannel")
        public static class WeComFactoryConfiguration {

            @Bean(WeComChannel.TYPE)
            public ChannelFactory wecomChannelFactory() {
                return WeComChannel::fromProperties;
            }

            // 提供企业微信 Webhook 入站端点；渠道启动时会自行注册到 WeComChannelRegistry。
            @Bean
            public WeComCallbackController wecomCallbackController() {
                return new WeComCallbackController();
            }
        }
    }

    /**
     * 装配知识库文档与对话附件共用的原文件存储。该配置不依赖 {@code rag.enabled}，确保关闭 RAG 后仍可访问附件。
     * {@link OssAiFileStorage} 延迟获取 OSS 客户端，业务服务根据 {@link AiFileStorage#type()} 选择后端。
     */
    @Configuration
    public static class FileStorageConfiguration {

        @Bean
        public LocalAiFileStorage localAiFileStorage(AiProperties aiProperties) {
            return new LocalAiFileStorage(aiProperties);
        }

        @Bean
        public OssAiFileStorage ossAiFileStorage(
                ObjectProvider<OssClientManager> ossClientManagerProvider, AiProperties aiProperties) {
            return new OssAiFileStorage(ossClientManagerProvider, aiProperties);
        }
    }

    /**
     * 知识库（RAG）运行时装配：仅 {@code lambda.fusion.ai.rag.enabled=true} 时注册检索适配器与
     * 文档入库管线 Bean；未启用时 {@code AgentFactory} 判空跳过中间件挂载，管理 CRUD 不受影响。
     * 原文件存储后端已上移至 {@link FileStorageConfiguration} 无条件注册，此处不再重复。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "lambda.fusion.ai.rag", name = "enabled", havingValue = "true")
    public static class RagConfiguration {

        /** 默认 LUCENE 后端；缺少 {@code LuceneManagerFactory} 时不注册，检索适配器判空降级。 */
        @Bean
        @ConditionalOnBean(LuceneManagerFactory.class)
        @ConditionalOnProperty(
                prefix = "lambda.fusion.ai.rag.full-text",
                name = "type",
                havingValue = "LUCENE",
                matchIfMissing = true)
        public KnowledgeFullTextIndex luceneKnowledgeIndex(LuceneManagerFactory managerFactory) {
            return new LuceneKnowledgeIndex(managerFactory);
        }

        /** OceanBase 全文索引后端；连接参数复用向量存储的 OceanBase 配置。 */
        @Bean
        @ConditionalOnProperty(prefix = "lambda.fusion.ai.rag.full-text", name = "type", havingValue = "OCEANBASE")
        public KnowledgeFullTextIndex oceanBaseFullTextIndex(AiProperties aiProperties) {
            AiProperties.Rag rag = aiProperties.getRag();
            return new OceanBaseFullTextIndex(
                    rag.getStore().getOceanBase().getJdbcUrl(),
                    rag.getStore().getOceanBase().getUsername(),
                    rag.getStore().getOceanBase().getPassword(),
                    rag.getFullText().getTableName());
        }

        @Bean
        public SimpleKnowledgeAdapter simpleKnowledgeAdapter(
                KnowledgeBaseService knowledgeBaseService,
                EmbeddingModelResolver embeddingModelResolver,
                AiProperties aiProperties,
                ObjectProvider<KnowledgeFullTextIndex> fullTextIndexProvider) {
            return new SimpleKnowledgeAdapter(
                    knowledgeBaseService, embeddingModelResolver, aiProperties, fullTextIndexProvider.getIfAvailable());
        }

        @Bean
        public DocumentIngestionService documentIngestionService(
                KnowledgeDocumentMapper knowledgeDocumentMapper,
                SimpleKnowledgeAdapter simpleKnowledgeAdapter,
                AiFileStorageResolver storageResolver,
                AiProperties aiProperties) {
            return new DocumentIngestionService(
                    knowledgeDocumentMapper, simpleKnowledgeAdapter, storageResolver, aiProperties);
        }
    }

    /**
     * 按可用依赖装配技能仓库提供者。每种后端委托 AgentScope 对应实现；扩展未引入时不注册提供者，
     * {@code SkillRepositoryResolver} 会将该类型解析为未启用。
     */
    @Configuration
    public static class SkillRepositoryConfig {

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.core.skill.repository.mysql.MysqlSkillRepository")
        @ConditionalOnBean(DataSource.class)
        @RequiredArgsConstructor
        public static class MysqlSkillRepositoryConfiguration {

            private final AiProperties aiProperties;
            private final DataSource dataSource;

            @Bean
            public SkillRepositoryProvider mysqlSkillRepositoryProvider() {
                return new SkillRepositoryProvider() {
                    @Override
                    public String type() {
                        return "MYSQL";
                    }

                    @Override
                    public AgentSkillRepository create() {
                        AiProperties.Skill.Repository.Mysql cfg =
                                aiProperties.getSkill().getRepository().getMysql();
                        DataSource ds = StateStoreDataSources.resolveNamed(dataSource, cfg.getDatasource());
                        return new MysqlSkillRepository(ds, cfg.isCreateIfNotExist(), cfg.isWriteable());
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.core.skill.repository.postgresql.PostgresSkillRepository")
        @ConditionalOnBean(DataSource.class)
        @RequiredArgsConstructor
        public static class PostgresSkillRepositoryConfiguration {

            private final AiProperties aiProperties;
            private final DataSource dataSource;

            @Bean
            public SkillRepositoryProvider postgresSkillRepositoryProvider() {
                return new SkillRepositoryProvider() {
                    @Override
                    public String type() {
                        return "POSTGRES";
                    }

                    @Override
                    public AgentSkillRepository create() {
                        AiProperties.Skill.Repository.Postgres cfg =
                                aiProperties.getSkill().getRepository().getPostgres();
                        DataSource ds = StateStoreDataSources.resolveNamed(dataSource, cfg.getDatasource());
                        return new PostgresSkillRepository(ds, cfg.isCreateIfNotExist(), cfg.isWriteable());
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.core.skill.repository.GitSkillRepository")
        @RequiredArgsConstructor
        public static class GitSkillRepositoryConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public SkillRepositoryProvider gitSkillRepositoryProvider() {
                return new SkillRepositoryProvider() {
                    @Override
                    public String type() {
                        return "GIT";
                    }

                    @Override
                    public AgentSkillRepository create() {
                        AiProperties.Skill.Repository.Git cfg =
                                aiProperties.getSkill().getRepository().getGit();
                        Path localPath =
                                StringUtils.isNotBlank(cfg.getLocalPath()) ? Path.of(cfg.getLocalPath()) : null;
                        return new GitSkillRepository(cfg.getRemoteUrl(), cfg.getBranch(), localPath, cfg.getSource());
                    }
                };
            }
        }

        @Configuration
        @ConditionalOnClass(name = "io.agentscope.core.nacos.skill.NacosSkillRepository")
        @RequiredArgsConstructor
        public static class NacosSkillRepositoryConfiguration {

            private final AiProperties aiProperties;

            @Bean
            public SkillRepositoryProvider nacosSkillRepositoryProvider() {
                return new SkillRepositoryProvider() {
                    @Override
                    public String type() {
                        return "NACOS";
                    }

                    @Override
                    public AgentSkillRepository create() {
                        AiProperties.Skill.Repository.Nacos cfg =
                                aiProperties.getSkill().getRepository().getNacos();
                        Properties props = new Properties();
                        props.setProperty(PropertyKeyConst.SERVER_ADDR, cfg.getServerAddr());
                        if (StringUtils.isNotBlank(cfg.getNamespaceId())) {
                            props.setProperty(PropertyKeyConst.NAMESPACE, cfg.getNamespaceId());
                        }
                        if (StringUtils.isNotBlank(cfg.getAccessKey())) {
                            props.setProperty(PropertyKeyConst.ACCESS_KEY, cfg.getAccessKey());
                        }
                        if (StringUtils.isNotBlank(cfg.getSecretKey())) {
                            props.setProperty(PropertyKeyConst.SECRET_KEY, cfg.getSecretKey());
                        }
                        AiService aiService;
                        try {
                            aiService = AiFactory.createAiService(props);
                        } catch (NacosException e) {
                            throw new IllegalStateException("Nacos AiService 创建失败: " + e.getMessage(), e);
                        }
                        return new NacosSkillRepository(aiService, cfg.getNamespaceId());
                    }
                };
            }
        }
    }

    /**
     * 装配基于 Dubbo 的跨实例 Agent 缓存失效广播。每个实例既接收远程失效请求，也向其他实例广播本地配置变更。
     * Dubbo 不可用或广播开关关闭时不装配，配置变更仅通过本地事件生效。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ServiceBean")
    @ConditionalOnProperty(prefix = "lambda.fusion.ai.cluster", name = "invalidation-broadcast", matchIfMissing = true)
    public static class DubboInvalidationConfiguration {

        @Bean
        @DubboService(interfaceClass = RemoteAgentCacheInvalidationService.class)
        public RemoteAgentCacheInvalidationService remoteAgentCacheInvalidationService(
                ApplicationEventPublisher publisher) {
            // 将远端请求转换为本地 remote 事件；来源标记可阻止监听器再次广播形成回环。
            return appId -> publisher.publishEvent(ConfigChangedEvent.remote(appId));
        }

        @Bean
        public DubboConfigInvalidationBroadcaster dubboConfigInvalidationBroadcaster() {
            // broadcast 模式向所有提供者发送失效请求；关闭启动检查，避免未就绪实例阻塞本地启动。
            ReferenceConfig<RemoteAgentCacheInvalidationService> reference = new ReferenceConfig<>();
            reference.setInterface(RemoteAgentCacheInvalidationService.class);
            reference.setCluster("broadcast");
            reference.setCheck(false);
            return new DubboConfigInvalidationBroadcaster(reference.get());
        }
    }

    /**
     * 装配 Authority 用户服务的 Dubbo 引用，供 {@code CurrentUserQueryTool} 查询昵称、组织、角色和账户状态。
     * Dubbo 不可用时不装配，工具仅返回对话上下文已有的基础身份。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.apache.dubbo.config.spring.ServiceBean")
    public static class DubboRemoteUserConfiguration {

        @Bean
        public RemoteUserService remoteUserService() {
            // 关闭启动检查；Authority 未就绪时由工具在实际调用阶段降级处理。
            ReferenceConfig<RemoteUserService> reference = new ReferenceConfig<>();
            reference.setInterface(RemoteUserService.class);
            reference.setCheck(false);
            return reference.get();
        }
    }

    /**
     * 装配定时 Agent 任务调度。仅在功能启用且 Quartz 扩展可用时注册，并复用 Spring 管理的
     * {@link Scheduler}。Quartz 使用 JDBC JobStore 保存触发器，业务任务定义仍以 {@code ai_sub_agent} 为准，
     * 应用启动时由 {@code AgentTaskBootstrap} 重新注册。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.agentscope.extensions.scheduler.quartz.QuartzAgentScheduler")
    @RequiredArgsConstructor
    public static class ScheduleConfiguration implements SchedulerFactoryBeanCustomizer {

        private final DataSource dataSource;
        private final AiProperties aiProperties;

        /**
         * 为 Quartz 显式设置底层数据源。项目主数据源是动态路由数据源，后台线程直连时无法可靠选择路由，
         * 因此通过 {@link StateStoreDataSources#resolveNamed} 解析配置的数据源名称，默认使用主库。
         * 这里直接定制 {@link SchedulerFactoryBean}，避免额外声明 Quartz 数据源后形成多个候选项。
         */
        @Override
        public void customize(@NonNull SchedulerFactoryBean schedulerFactoryBean) {
            String dsName = aiProperties.getSchedule().getDatasource();
            DataSource resolved = StateStoreDataSources.resolveNamed(dataSource, dsName);
            if (resolved == null) {
                throw new IllegalStateException("Quartz 数据源解析失败: schedule.datasource=" + dsName);
            }
            schedulerFactoryBean.setDataSource(resolved);
        }

        /**
         * 复用 Spring 装配的共享 {@link Scheduler}:autoStart(false) 因 Spring 自启；destroyMethod=""
         * 不调用其 shutdown()（内部会误关共享 Scheduler）。
         */
        @Bean(destroyMethod = "")
        public QuartzAgentScheduler quartzAgentScheduler(Scheduler scheduler) {
            return QuartzAgentScheduler.builder()
                    .scheduler(scheduler)
                    .schedulerId(aiProperties.getSchedule().getSchedulerId())
                    .autoStart(false)
                    .build();
        }

        /**
         * 注册定时任务执行监听器到共享 Scheduler：覆盖定时触发路径的租户恢复与执行记录落库
         * （手动触发路径由 {@code AgentTaskScheduler.runOnce} 自行记录）。注册失败仅告警，不阻塞启动。
         */
        @Bean
        public Boolean agentExecutionJobListenerRegistration(Scheduler scheduler, AgentExecutionJobListener listener) {
            try {
                scheduler.getListenerManager().addJobListener(listener);
                return Boolean.TRUE;
            } catch (Exception e) {
                log.error("注册定时任务执行监听器失败", e);
                return Boolean.FALSE;
            }
        }
    }
}
