package com.lambda.fusion.authority.user.listener;

import com.lambda.cloud.logger.model.OperationLogRecord;
import com.lambda.cloud.logger.service.OperationService;
import com.lambda.security.web.event.LoginFailureEvent;
import com.lambda.security.web.event.LoginSuccessEvent;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 登录日志监听：消费 security starter 发布的登录成功/失败事件，转为模块「登录」的操作日志
 * 经 {@link OperationService} 异步落库（事件已携带 username/IP/loginType/租户，无需读请求上下文）。
 *
 * @author zx
 */
@Slf4j
@Component
public class UserLoginListener {

    /** 操作日志模块标识：登录 */
    private static final String MODULE_LOGIN = "登录";

    private final OperationService operationService;

    public UserLoginListener(OperationService operationService) {
        this.operationService = operationService;
    }

    @Async("operationLogExecutor")
    @EventListener
    public void onLoginSuccess(LoginSuccessEvent event) {
        OperationLogRecord record = base(event.getUsername(), event.getIp(), event.getLoginType(), event.getTenantId());
        record.setDescription("登录成功");
        saveSafely(record);
    }

    @Async("operationLogExecutor")
    @EventListener
    public void onLoginFailure(LoginFailureEvent event) {
        OperationLogRecord record = base(event.getUsername(), event.getIp(), event.getLoginType(), null);
        record.setDescription("登录失败");
        record.setDetail("失败原因: " + event.getErrorMessage());
        saveSafely(record);
    }

    private OperationLogRecord base(String username, String ip, String loginType, String tenantId) {
        OperationLogRecord record = new OperationLogRecord();
        record.setModule(MODULE_LOGIN);
        record.setMethod("login:" + loginType);
        record.setHttpMethod("POST");
        record.setOperator(username);
        record.setOperatorId(username);
        record.setTenantId(tenantId);
        record.setIpAddress(ip);
        record.setTime(new Date());
        return record;
    }

    private void saveSafely(OperationLogRecord record) {
        try {
            operationService.save(record);
        } catch (Exception e) {
            log.warn("登录日志落库失败: username={}, method={}", record.getOperator(), record.getMethod(), e);
        }
    }
}
