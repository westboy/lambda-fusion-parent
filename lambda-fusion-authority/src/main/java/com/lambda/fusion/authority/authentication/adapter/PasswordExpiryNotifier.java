package com.lambda.fusion.authority.authentication.adapter;

import com.lambda.fusion.authority.user.model.PasswordExpiry;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.message.api.RemoteMessageCommand;
import com.lambda.fusion.message.api.RemoteMessageService;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 密码即将过期提醒通知器：登录后按剩余天数经 message-api 发站内信，走 SSE 实时到铃铛。
 *
 * <p>发送频控（去重由 message 侧按 bizId 窗口判重实现）：
 * <ul>
 *   <li>剩余 {@code <3} 天：每次登录都提醒（临近到期，高频强提醒）；</li>
 *   <li>剩余 3~7 天：每 7 天至多一条；</li>
 *   <li>剩余 {@code >=7} 天 / 无剩余天数（未启用策略/豁免角色/窗口外）：不提醒。</li>
 * </ul>
 *
 * <p>幂等键 {@code bizId = username:密码到期日}（到期日 = 今天 + 剩余天数，作为固定锚点），
 * 同一到期周期内重复发送由消息侧按窗口判重拦截。
 *
 * @author zx
 */
@Slf4j
@Component
public class PasswordExpiryNotifier {

    /** 业务类型：密码过期提醒。 */
    public static final String BIZ_TYPE = "PASSWORD_EXPIRY";

    /** 高频提醒阈值：剩余天数小于该值时每次登录都提醒。 */
    private static final int URGENT_DAYS = 3;

    /** 去重窗口天数：3~7 天区间按此窗口（7 天）至多一条。 */
    private static final int DEDUP_WINDOW_DAYS = 7;

    private final ObjectProvider<RemoteMessageService> remoteMessageService;

    public PasswordExpiryNotifier(ObjectProvider<RemoteMessageService> remoteMessageService) {
        this.remoteMessageService = remoteMessageService;
    }

    /** 登录后调用：按剩余天数与去重窗口决定是否发送密码过期提醒。 */
    public void notifyIfNeeded(UserDetails user, PasswordExpiry expiry) {
        if (user == null || expiry == null || expiry.isResetRequired()) {
            return;
        }
        int remainDays = expiry.getRemainDays();
        if (remainDays < 0) {
            return;
        }
        RemoteMessageService service = remoteMessageService.getIfAvailable();
        if (service == null) {
            return;
        }
        String username = user.getUsername();
        LocalDate expiryDate = LocalDate.now().plusDays(remainDays);
        String bizId = username + ":" + expiryDate;
        // 3~7 天区间按 7 天窗口去重；<3 天传 0 表示不去重（每次登录都发）。
        int windowDays = remainDays < URGENT_DAYS ? 0 : DEDUP_WINDOW_DAYS;
        try {
            if (windowDays > 0 && service.existsRecent(BIZ_TYPE, bizId, windowDays)) {
                return;
            }
            RemoteMessageCommand command = new RemoteMessageCommand();
            command.setTenantId(user.getTenantId());
            command.setSender(username);
            command.setReceivers(List.of(username));
            command.setCategory("user");
            command.setLevel(remainDays < URGENT_DAYS ? "important" : "info");
            command.setChannels(List.of("inbox"));
            command.setTitle("密码即将过期提醒");
            command.setContent("您的密码将于 " + remainDays + " 天后过期（" + expiryDate + "），请及时前往个人中心修改密码。");
            command.setBizType(BIZ_TYPE);
            command.setBizId(bizId);
            service.send(command);
        } catch (Exception e) {
            log.warn("密码过期提醒发送失败: username={}, remainDays={}", username, remainDays, e);
        }
    }
}
