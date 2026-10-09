package com.lambda.fusion.authority.authentication.adapter;

import com.lambda.fusion.authority.api.RemoteCreateResource;
import com.lambda.fusion.authority.api.RemoteMoveResource;
import com.lambda.fusion.authority.api.RemoteResource;
import com.lambda.fusion.authority.api.RemoteResourceService;
import com.lambda.fusion.authority.resource.model.CreateResource;
import com.lambda.fusion.authority.resource.model.MoveResource;
import com.lambda.fusion.authority.resource.model.Resource;
import com.lambda.fusion.authority.resource.service.PermissionService;
import com.lambda.fusion.authority.resource.service.ResourceService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link RemoteResourceService} 实现：委托本地 {@link ResourceService}/{@link PermissionService}，
 * 经 Dubbo 暴露给外部功能挂载菜单（动态表单路由等）。
 *
 * <p>更新走「重读 + 拷贝契约字段」：契约未携带的字段保留 authority 原值，防止远程载荷缺字段清空数据。
 *
 * @author zx
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class RemoteResourceServiceAdapter implements RemoteResourceService {

    private final ResourceService resourceService;
    private final PermissionService permissionService;

    @Override
    public RemoteResource getResourceById(String id) {
        return toRemote(resourceService.getResourceById(id));
    }

    @Override
    public List<RemoteResource> queryAvailableChildren(String parentId) {
        Resource parent = resourceService.getResourceById(parentId);
        if (parent == null) {
            return List.of();
        }
        return resourceService.queryAvailableChildren(parent).stream()
                .map(this::toRemote)
                .toList();
    }

    @Override
    public RemoteResource addResource(RemoteCreateResource create) {
        CreateResource domain = new CreateResource();
        domain.setParentId(create.getParentId());
        domain.setResName(create.getResName());
        domain.setResPath(create.getResPath());
        domain.setResType(create.getResType());
        domain.setResMode(create.getResMode());
        domain.setApplication(create.getApplication());
        domain.setIcon(create.getIcon());
        domain.setExpand(create.getExpand());
        domain.setBusinessExpand(create.getBusinessExpand());
        return toRemote(resourceService.addResource(domain));
    }

    @Override
    public void updateResource(RemoteResource resource) {
        Resource current = resourceService.getResourceById(resource.getId());
        if (current == null) {
            return;
        }
        current.setResName(resource.getResName());
        current.setResPath(resource.getResPath());
        current.setResType(resource.getResType());
        current.setResMode(resource.getResMode());
        current.setIcon(resource.getIcon());
        current.setOrderNo(resource.getOrderNo());
        current.setExpand(resource.getExpand());
        current.setKeepAlive(resource.getKeepAlive());
        current.setApplication(resource.getApplication());
        current.setBusinessExpand(resource.getBusinessExpand());
        resourceService.updateResource(current);
    }

    @Override
    public void moveResource(RemoteMoveResource move) {
        MoveResource domain = new MoveResource();
        domain.setId(move.getId());
        domain.setTid(move.getTid());
        domain.setType(move.getType());
        resourceService.move(domain);
    }

    @Override
    public void deleteResource(String id) {
        resourceService.deleteResource(id);
    }

    @Override
    public void lockResourceTree() {
        resourceService.lockResourceTree();
    }

    @Override
    public void bindPermission(String resourceId, String permissionId) {
        permissionService.bind(resourceId, permissionId);
    }

    private RemoteResource toRemote(Resource resource) {
        if (resource == null) {
            return null;
        }
        RemoteResource remote = new RemoteResource();
        remote.setId(resource.getId());
        remote.setParentId(resource.getParentId());
        remote.setResName(resource.getResName());
        remote.setResPath(resource.getResPath());
        remote.setResType(resource.getResType());
        remote.setResMode(resource.getResMode());
        remote.setIcon(resource.getIcon());
        remote.setOrderNo(resource.getOrderNo());
        remote.setExpand(resource.getExpand());
        remote.setKeepAlive(resource.getKeepAlive());
        remote.setApplication(resource.getApplication());
        remote.setParentKeys(resource.getParentKeys());
        remote.setBusinessExpand(resource.getBusinessExpand());
        return remote;
    }
}
