package com.lambda.fusion.authority.authentication.adapter;

import cn.hutool.core.util.StrUtil;
import com.lambda.fusion.authority.api.RemoteOrg;
import com.lambda.fusion.authority.api.RemoteOrganizationService;
import com.lambda.fusion.authority.organization.model.Organization;
import com.lambda.fusion.authority.organization.model.OrganizationQuery;
import com.lambda.fusion.authority.organization.service.OrganizationService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link RemoteOrganizationService} 实现：基于本地 {@link OrganizationService} 列举组织，经 Dubbo 暴露给其他服务。
 *
 * @author jin
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class RemoteOrganizationServiceAdapter implements RemoteOrganizationService {

    private final OrganizationService organizationService;

    @Override
    public List<RemoteOrg> listOrganizations(String tenantId, String keyword) {
        OrganizationQuery query = new OrganizationQuery();
        if (tenantId != null) {
            query.setTenantId(tenantId);
        }
        return organizationService.selectAll(query).stream()
                .filter(org -> matchKeyword(org, keyword))
                .map(org -> new RemoteOrg(
                        org.getId(), StrUtil.isNotBlank(org.getAlias()) ? org.getAlias() : org.getName(), null))
                .toList();
    }

    private boolean matchKeyword(Organization org, String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return true;
        }
        return StrUtil.containsIgnoreCase(org.getName(), keyword)
                || StrUtil.containsIgnoreCase(org.getAlias(), keyword);
    }
}
