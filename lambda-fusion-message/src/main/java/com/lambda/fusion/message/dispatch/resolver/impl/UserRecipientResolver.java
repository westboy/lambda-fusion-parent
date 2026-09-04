package com.lambda.fusion.message.dispatch.resolver.impl;

import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.dispatch.resolver.MessageRecipientResolver;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 指定用户范围解析：用户名在范围目标 JSON 数组内即命中
 *
 * @author jin
 */
@Slf4j
@SuppressFBWarnings("EI_EXPOSE_REP2")
@Component
public class UserRecipientResolver implements MessageRecipientResolver {

    private final ObjectMapper objectMapper;

    public UserRecipientResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String scopeType() {
        return MessageConstants.SCOPE_USER;
    }

    @Override
    public boolean contains(String scopeTargets, String username) {
        if (scopeTargets == null || scopeTargets.isBlank() || username == null) {
            return false;
        }
        try {
            List<String> targets = objectMapper.readValue(scopeTargets, new TypeReference<>() {});
            return targets != null && targets.contains(username);
        } catch (Exception e) {
            log.warn("scopeTargets 解析失败，按不命中处理: {}", e.getMessage());
            return false;
        }
    }
}
