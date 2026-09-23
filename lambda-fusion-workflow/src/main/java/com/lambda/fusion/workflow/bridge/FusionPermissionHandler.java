package com.lambda.fusion.workflow.bridge;

import cn.hutool.core.util.StrUtil;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.workflow.WorkflowConstants;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.dromara.warm.flow.core.handler.PermissionHandler;
import org.springframework.stereotype.Component;

/**
 * 办理人权限桥：把 fusion 登录身份翻译为引擎权限标识。
 *
 * <p>引擎按标识串精确匹配节点 permission_flag 与 flow_user.processed_by，标识约定见
 * {@link WorkflowConstants}。handler 使用 username，无登录上下文时返回空集合、不抛异常。
 * 容器内只应存在一个 PermissionHandler Bean，下游覆盖用 @Primary。
 *
 * @author jin
 */
@Slf4j
@Component
public class FusionPermissionHandler implements PermissionHandler {

    @Override
    public List<String> permissions() {
        UserDetails user = safeUser();
        if (user == null) {
            return List.of();
        }
        List<String> flags = new ArrayList<>();
        if (StrUtil.isNotBlank(user.getUsername())) {
            flags.add(WorkflowConstants.PERMISSION_PREFIX_USER + user.getUsername());
        }
        if (StrUtil.isNotBlank(user.getOrgId())) {
            flags.add(WorkflowConstants.PERMISSION_PREFIX_ORG + user.getOrgId());
        }
        for (String role : user.getRoles()) {
            if (StrUtil.isNotBlank(role)) {
                flags.add(WorkflowConstants.PERMISSION_PREFIX_ROLE + role);
            }
        }
        return flags;
    }

    @Override
    public String getHandler() {
        return AuthUtils.getSafeUsername();
    }

    private UserDetails safeUser() {
        try {
            return AuthUtils.getUser();
        } catch (Exception e) {
            log.debug("无登录上下文，返回空权限标识集合");
            return null;
        }
    }
}
