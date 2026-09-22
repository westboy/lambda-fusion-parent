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
 * 办理人权限桥：把 fusion 登录身份翻译为引擎权限标识
 *
 * <p>引擎的权限模型是「权限标识串精确匹配」：节点 permission_flag 与 flow_user.processed_by
 * 按标识串等值比对，因此桥接的全部工作是产出当前用户的标识集合：
 * {@code user:{username}}、每个角色 {@code role:{roleCode}}、组织 {@code org:{orgId}}
 * （前缀约定见 {@link WorkflowConstants}）。
 *
 * <p>handler（办理人唯一标识）使用 username，与 authority 的登录与远程查询口径一致，
 * flow_his_task.approver 可直接展示与追溯。无登录上下文（后台线程）时降级为匿名、返回空权限集合，
 * 不抛异常（对齐 AuthUtils.getSafeUsername 的语义）。
 *
 * <p>容器内只应存在一个 PermissionHandler Bean；下游需要「角色展开为用户」等自定义语义时
 * 覆盖本 Bean 并标注 @Primary，convertPermissions 默认透传聚合标识。
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
