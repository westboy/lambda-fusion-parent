package com.lambda.fusion.message.api;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.Data;

/**
 * 远程消息发送命令（跨服务/Dubbo 传输对象）。
 *
 * <p>对齐消息模块内部的 {@code MessageSendCommand}，但显式携带 {@code tenantId} 与 {@code sender}：
 * 后台任务（如定时任务）在 Quartz 线程执行，无登录上下文，发送人必须由调用方显式传入。
 * {@code tenantId} 允许为空：无租户上下文时按无租户/平台级消息投递。
 * 仅承载契约字段，不含任何实现逻辑。
 *
 * @author zx
 */
@Data
public class RemoteMessageCommand implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 归属租户ID（可空；为空则按无租户/平台级消息投递，站内信按用户名匹配收件人）。 */
    private String tenantId;

    /** 发送人标识（必填；后台任务无登录上下文，由调用方传入，如任务创建人）。 */
    private String sender;

    /** 收件人用户名列表（必填，非空）。 */
    private List<String> receivers;

    /** 标题（必填）。 */
    private String title;

    /** 正文。 */
    private String content;

    /** 消息分类：system/user/biz，默认 system。 */
    private String category;

    /** 级别：info/important/urgent，默认 info。 */
    private String level;

    /** 投递渠道：inbox/email/sms，空则仅站内信。 */
    private List<String> channels;

    /** 来源业务类型（如 SCHEDULED_TASK）。 */
    private String bizType;

    /** 来源业务单据ID（如任务ID）。 */
    private String bizId;
}
