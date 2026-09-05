package com.lambda.fusion.message.api;

/**
 * 远程消息发送服务：供其他服务（如 ai）经 Dubbo 调用，向指定用户发送站内信等消息。
 *
 * <p>message 侧经 Dubbo 暴露实现；其他服务通过 {@code message-api} 引用消费，不得绕过本接口
 * 直接跨服务引用消息模块实现类（对齐 authority-api 的 {@code RemoteUserService} 模式）。
 *
 * <p>实现侧在命令携带租户时按其建立租户上下文后落库与投递；租户为空（如定时任务无租户上下文）时
 * 按无租户/平台级消息直接投递，不因租户缺失而拒绝发送。
 *
 * @author zx
 */
public interface RemoteMessageService {

    /**
     * 发送消息。
     *
     * @param command 发送命令（须含 sender、receivers、title；tenantId 可空）
     * @return 是否成功受理；远程不可用或参数非法时实现侧返回 {@code false} 并记录日志，不抛业务异常
     */
    boolean send(RemoteMessageCommand command);

    /**
     * 判重：指定业务类型 + 业务单据ID 在近 {@code withinDays} 天内是否已发送过消息。
     * 供调用方实现「N 天一次」类提醒的去重（如密码过期 7 天一条）。
     *
     * @param bizType    业务类型
     * @param bizId      业务单据ID（幂等键）
     * @param withinDays 窗口天数；{@code <=0} 表示不判重（恒返回 {@code false}）
     * @return 窗口内已存在则 {@code true}；远程不可用时实现侧返回 {@code false}（宁可重发不漏判）
     */
    boolean existsRecent(String bizType, String bizId, int withinDays);
}
