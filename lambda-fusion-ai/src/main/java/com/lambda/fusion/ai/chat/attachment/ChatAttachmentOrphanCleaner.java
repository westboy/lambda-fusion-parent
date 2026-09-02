package com.lambda.fusion.ai.chat.attachment;

import com.lambda.fusion.ai.AiProperties;
import com.lambda.fusion.ai.chat.service.ChatAttachmentService;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 孤儿对话附件定时回收任务：周期调用 {@link ChatAttachmentService#purgeOrphans}，删除超过保留时长
 * 且未绑定消息的附件。多节点同时运行时按记录级删除幂等收敛，不引入分布式锁；单轮失败仅告警，
 * 留待下一轮重试。
 *
 * <p>调度使用自管单线程守护执行器，配置统一来自 {@link AiProperties.Chat.Attachment.OrphanCleanup}
 * （开关由 {@code lambda.fusion.ai.chat.attachment.orphan-cleanup.enabled} 决定 Bean 是否注册），
 * 不依赖 Spring {@code @Scheduled} 占位符，避免配置来源分裂。首轮延迟一个间隔，避开启动高峰。
 *
 * @author Jin
 */
@Slf4j
@RequiredArgsConstructor
public class ChatAttachmentOrphanCleaner {

    private final ChatAttachmentService attachmentService;
    private final AiProperties aiProperties;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-attachment-orphan-cleaner");
        thread.setDaemon(true);
        return thread;
    });

    /** 启动周期回收；间隔与批量来自配置。 */
    public void start() {
        AiProperties.Chat.Attachment.OrphanCleanup cfg =
                aiProperties.getChat().getAttachment().getOrphanCleanup();
        scheduler.scheduleWithFixedDelay(
                this::runSafely, cfg.getIntervalMinutes(), cfg.getIntervalMinutes(), TimeUnit.MINUTES);
        log.info(
                "孤儿附件回收任务已启动: intervalMinutes={}, retentionHours={}, batchSize={}",
                cfg.getIntervalMinutes(),
                cfg.getRetentionHours(),
                cfg.getBatchSize());
    }

    /** 单轮回收：按保留时长计算截止时间并执行删除；异常只告警不影响下一轮调度。 */
    void runSafely() {
        AiProperties.Chat.Attachment.OrphanCleanup cfg =
                aiProperties.getChat().getAttachment().getOrphanCleanup();
        LocalDateTime cutoff = LocalDateTime.now().minusHours(cfg.getRetentionHours());
        try {
            int purged = attachmentService.purgeOrphans(cutoff, cfg.getBatchSize());
            if (log.isDebugEnabled()) {
                log.debug("孤儿附件回收本轮结束: purged={}, cutoff={}", purged, cutoff);
            }
        } catch (RuntimeException e) {
            log.warn("孤儿附件回收本轮失败，等待下一轮重试: cutoff={}, reason={}", cutoff, e.getMessage(), e);
        }
    }

    /** 停机时中断调度线程池；已提交的单轮任务允许自然结束。 */
    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }
}
