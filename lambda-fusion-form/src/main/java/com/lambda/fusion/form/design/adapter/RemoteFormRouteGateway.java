package com.lambda.fusion.form.design.adapter;

import com.lambda.cloud.core.utils.StpLogicUtils;
import com.lambda.fusion.authority.api.RemoteCreateResource;
import com.lambda.fusion.authority.api.RemoteMoveResource;
import com.lambda.fusion.authority.api.RemoteResource;
import com.lambda.fusion.authority.api.RemoteResourceService;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.design.entity.FormDefEntity;
import com.lambda.fusion.form.design.model.FormRouteCommand;
import com.lambda.fusion.form.design.model.FormRouteInfo;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.form.runtime.support.FormAction;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 经 authority 远程契约（{@code RemoteResourceService}）管理本表单的菜单、操作资源与授权，
 * 不直接写资源或授权表（工程契约 §7.3）。策略逻辑（归属标记、防环、路径保留）与远程适配前一致。
 */
@RequiredArgsConstructor
@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "共享 Spring ObjectMapper，不修改其配置")
public class RemoteFormRouteGateway implements FormRouteGateway {
    private final RemoteResourceService resources;
    private final ObjectMapper json;

    @Override
    public FormRouteInfo read(FormDefEntity form) {
        RemoteResource menu = menu(form);
        if (menu == null) return new FormRouteInfo(true, false, null, null, null, null, null, null, List.of());
        Map<FormAction, RemoteResource> buttons = buttons(form, menu);
        List<FormRouteInfo.Action> actions = new ArrayList<>();
        for (FormAction action : FormAction.values()) {
            RemoteResource button = buttons.get(action);
            if (button != null)
                actions.add(new FormRouteInfo.Action(
                        action.getCode(), action.getLabel(), action.permission(form.getFormKey()), button.getId()));
        }
        return new FormRouteInfo(
                true,
                true,
                menu.getId(),
                menu.getParentId(),
                menu.getResName(),
                menu.getResPath(),
                menu.getIcon(),
                menu.getOrderNo(),
                actions);
    }

    @Override
    public String save(FormDefEntity form, FormRouteCommand command) {
        checkManager();
        resources.lockResourceTree();
        RemoteResource current = menu(form);
        AuthUtils.checkPermission(current == null ? "authority:resource:add" : "authority:resource:update");
        AuthUtils.checkPermission("authority:resource:api-permission-bind");
        RemoteResource parent = resources.getResourceById(command.parentId());
        if (parent == null
                || !Integer.valueOf(1).equals(parent.getResType())
                || !Integer.valueOf(0).equals(parent.getResMode())) {
            throw conflict("请选择已有的管理端父菜单");
        }
        if (current != null
                && (Objects.equals(parent.getId(), current.getId())
                        || parent.getParentKeys() != null
                                && List.of(parent.getParentKeys().split(FusionConstants.SEPARATOR0))
                                        .contains(current.getId()))) {
            throw conflict("不能挂载到自身或其下级菜单");
        }
        String path = command.path().trim().replaceAll("/+$", "");
        if (path.equals("/form/designer") || path.startsWith("/auth/") || path.startsWith("/_core/")) {
            throw conflict("该路径为系统入口保留路径");
        }
        String expand = json.writeValueAsString(Map.of(
                "component",
                "/form/runtime/index",
                "meta",
                Map.of("formKey", form.getFormKey(), "formId", form.getId())));
        if (current == null) {
            RemoteCreateResource create = new RemoteCreateResource();
            create.setParentId(parent.getId());
            create.setResName(command.name().trim());
            create.setResPath(path);
            create.setResType(1);
            create.setResMode(0);
            create.setApplication(parent.getApplication());
            create.setIcon(command.icon());
            create.setExpand(expand);
            create.setBusinessExpand(marker(form, "menu"));
            current = resources.addResource(create);
        } else {
            buttons(form, current); // 有非本表单子资源时拒绝改写整个挂载结构。
            if (!Objects.equals(current.getParentId(), parent.getId())) {
                AuthUtils.checkPermission("authority:resource:move");
                RemoteMoveResource move = new RemoteMoveResource();
                move.setId(current.getId());
                move.setTid(parent.getId());
                move.setType(0);
                resources.moveResource(move);
                current = resources.getResourceById(current.getId());
            }
        }
        current.setResName(command.name().trim());
        current.setResPath(path);
        current.setIcon(command.icon());
        current.setOrderNo(command.orderNo());
        current.setExpand(expand);
        current.setKeepAlive(false);
        current.setApplication(parent.getApplication());
        resources.updateResource(current);
        Map<FormAction, RemoteResource> existing = buttons(form, current);
        for (FormAction action : FormAction.values()) {
            RemoteResource button = existing.get(action);
            if (button == null) {
                AuthUtils.checkPermission("authority:resource:add");
                RemoteCreateResource create = new RemoteCreateResource();
                create.setParentId(current.getId());
                create.setResName(action.getLabel());
                create.setResPath(action.resourceCode(form.getFormKey()));
                create.setResType(3);
                create.setResMode(0);
                create.setApplication(parent.getApplication());
                create.setBusinessExpand(marker(form, action.getCode()));
                button = resources.addResource(create);
            }
            resources.bindPermission(button.getId(), action.permission(form.getFormKey()));
        }
        return current.getId();
    }

    @Override
    public void remove(FormDefEntity form) {
        checkManager();
        AuthUtils.checkPermission("authority:resource:delete");
        AuthUtils.checkPermission("authority:resource:api-permission-unbind");
        resources.lockResourceTree();
        RemoteResource menu = menu(form);
        if (menu == null) return;
        buttons(form, menu); // 只允许删除本功能拥有的完整资源子树。
        resources.deleteResource(menu.getId());
    }

    private RemoteResource menu(FormDefEntity form) {
        if (form.getRouteResourceId() == null) return null;
        RemoteResource resource = resources.getResourceById(form.getRouteResourceId());
        if (resource != null
                && (!owned(resource, form, "menu") || !Integer.valueOf(1).equals(resource.getResType()))) {
            throw conflict("菜单绑定已被其它功能修改，请检查资源归属");
        }
        return resource;
    }

    private Map<FormAction, RemoteResource> buttons(FormDefEntity form, RemoteResource menu) {
        Map<FormAction, RemoteResource> result = new EnumMap<>(FormAction.class);
        // queryAvailableChildren 返回树节点（无归属标记字段），逐个按 id 取全量资源校验
        for (RemoteResource child : resources.queryAvailableChildren(menu.getId())) {
            RemoteResource resource = resources.getResourceById(child.getId());
            boolean matched = false;
            for (FormAction action : FormAction.values()) {
                if (Objects.equals(resource.getParentId(), menu.getId())
                        && Integer.valueOf(3).equals(resource.getResType())
                        && owned(resource, form, action.getCode())
                        && action.resourceCode(form.getFormKey()).equals(resource.getResPath())) {
                    if (result.put(action, resource) != null) throw conflict("存在重复的表单操作资源");
                    matched = true;
                    break;
                }
            }
            if (!matched) throw conflict("菜单包含非本表单管理的资源，不能覆盖或级联删除");
        }
        return result;
    }

    private String marker(FormDefEntity form, String action) {
        return json.writeValueAsString(Map.of("kind", "dynamic-form", "formId", form.getId(), "action", action));
    }

    private boolean owned(RemoteResource resource, FormDefEntity form, String action) {
        if (resource.getBusinessExpand() == null) return false;
        try {
            var marker = json.readTree(resource.getBusinessExpand());
            return marker.isObject()
                    && "dynamic-form".equals(marker.path("kind").asText())
                    && form.getId().equals(marker.path("formId").asText())
                    && action.equals(marker.path("action").asText());
        } catch (JacksonException error) {
            return false;
        }
    }

    private void checkManager() {
        StpLogicUtils.getActiveStpLogic()
                .checkRoleOr(FusionConstants.ROLE_ADMIN, FusionConstants.ROLE_SYSTEM, FusionConstants.ROLE_DEV);
    }

    private FormBusinessException conflict(String message) {
        return new FormBusinessException(FormErrorCode.FORM_ROUTE_CONFLICT, message);
    }
}
