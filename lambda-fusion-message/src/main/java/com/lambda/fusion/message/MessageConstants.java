package com.lambda.fusion.message;

/**
 * 消息中心模块常量
 *
 * @author jin
 */
public final class MessageConstants {

    /** SSE 推送事件名：客户端收到后重新拉取未读数与消息列表（正文不走路）。 */
    public static final String SSE_EVENT_NOTIFICATION = "notification";

    /** SSE 广播事件名：公告发布/撤回后通知客户端刷新公告未读数。 */
    public static final String SSE_EVENT_ANNOUNCEMENT = "announcement";

    /** 系统消息发送者标识。 */
    public static final String SENDER_SYSTEM = "system";

    /** 站内渠道标识。 */
    public static final String CHANNEL_INBOX = "inbox";

    /** 邮件渠道标识。 */
    public static final String CHANNEL_EMAIL = "email";

    /** 短信渠道标识。 */
    public static final String CHANNEL_SMS = "sms";

    /** 机器人 webhook 渠道标识。 */
    public static final String CHANNEL_BOT = "bot";

    /** 消息分类：系统消息。 */
    public static final String CATEGORY_SYSTEM = "system";

    /** 消息分类：用户消息。 */
    public static final String CATEGORY_USER = "user";

    /** 消息分类：业务通知。 */
    public static final String CATEGORY_BIZ = "biz";

    /** 级别：普通。 */
    public static final String LEVEL_INFO = "info";

    /** 级别：重要。 */
    public static final String LEVEL_IMPORTANT = "important";

    /** 级别：紧急。 */
    public static final String LEVEL_URGENT = "urgent";

    /** 正文类型：纯文本。 */
    public static final String CONTENT_TYPE_TEXT = "text";

    /** 正文类型：HTML。 */
    public static final String CONTENT_TYPE_HTML = "html";

    /** 公告范围：全员（当前租户内）。 */
    public static final String SCOPE_ALL = "all";

    /** 公告范围：指定用户。 */
    public static final String SCOPE_USER = "user";

    /** 公告范围：指定组织（经 authority-api 解析用户所属组织）。 */
    public static final String SCOPE_ORG = "org";

    /** 公告范围：指定角色（经 authority-api 解析用户角色）。 */
    public static final String SCOPE_ROLE = "role";

    /** 公告发布状态：草稿。 */
    public static final int PUBLISH_STATUS_DRAFT = 0;

    /** 公告发布状态：已发布。 */
    public static final int PUBLISH_STATUS_PUBLISHED = 1;

    /** 公告发布状态：已撤回。 */
    public static final int PUBLISH_STATUS_REVOKED = 2;

    private MessageConstants() {}
}
