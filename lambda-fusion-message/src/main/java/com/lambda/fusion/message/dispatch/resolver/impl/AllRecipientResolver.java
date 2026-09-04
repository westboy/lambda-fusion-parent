package com.lambda.fusion.message.dispatch.resolver.impl;

import com.lambda.fusion.message.MessageConstants;
import com.lambda.fusion.message.dispatch.resolver.MessageRecipientResolver;
import org.springframework.stereotype.Component;

/**
 * 全员范围解析：当前租户内所有用户均命中
 *
 * @author jin
 */
@Component
public class AllRecipientResolver implements MessageRecipientResolver {

    @Override
    public String scopeType() {
        return MessageConstants.SCOPE_ALL;
    }

    @Override
    public boolean contains(String scopeTargets, String username) {
        return true;
    }
}
