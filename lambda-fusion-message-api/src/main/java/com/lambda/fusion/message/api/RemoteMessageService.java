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
     * @param command 发送命令（须含 tenantId、sender、receivers、title）
     * @return 是否成功受理；远程不可用或参数非法时实现侧返回 {@code false} 并记录日志，不抛业务异常
     */
    boolean send(RemoteMessageCommand command);
}
