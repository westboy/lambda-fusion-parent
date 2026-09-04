package com.lambda.fusion.authority.user.listener;

import com.lambda.cloud.sse.listener.SseEventListener;
import com.lambda.fusion.authority.user.service.UserOnlineLogService;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class UserSseEventListener implements SseEventListener {

    private final UserOnlineLogService userOnlineLogService;

    @Override
    public void onDisconnect(String clientId) {
        if (clientId != null) {
            userOnlineLogService.offline(clientId, null);
        }
    }

    @Override
    public void onConnect(String clientId) {
        if (clientId != null) {
            userOnlineLogService.online(clientId, null);
        }
    }
}
