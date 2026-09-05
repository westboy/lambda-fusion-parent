package com.lambda.fusion.authority.user.assembler;

import static com.lambda.fusion.core.utils.SqlParamUtils.fuzzyQuery;

import cn.hutool.json.JSONUtil;
import com.google.common.collect.Sets;
import com.lambda.fusion.authority.organization.service.OrganizationService;
import com.lambda.fusion.authority.user.model.UserQuery;
import com.lambda.fusion.authority.user.model.UserQueryContext;
import com.lambda.fusion.authority.user.model.entity.UserFieldsEntity;
import com.lambda.fusion.authority.user.service.UserService;
import com.lambda.fusion.authority.utils.UserInfoConverter;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.utils.AuthUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@SuppressFBWarnings("EI_EXPOSE_REP2")
@Slf4j
@Component
@RequiredArgsConstructor
public class UserQueryAssembler {

    private final OrganizationService organizationService;
    private final UserService userService;

    public UserQueryContext buildUserQueryContext(UserQuery userQuery) {
        UserQueryContext userQueryContext = new UserQueryContext();
        UserDetails userDetails = AuthUtils.getUser();
        String tenantId = AuthUtils.getTenantId();

        // 处理 username (支持逗号分隔)
        if (StringUtils.isNotBlank(userQuery.getUsername())) {
            String[] split = userQuery.getUsername().split(FusionConstants.DELIMITER);
            userQueryContext.setUsernames(Arrays.asList(split));
        }

        userQueryContext.setDev(userDetails.isDev());
        userQueryContext.setAdmin(userDetails.isAdmin());
        userQueryContext.setUsername(userDetails.getName());

        if (StringUtils.isNotBlank(userQuery.getEmail())) {
            userQueryContext.setEmail(fuzzyQuery(userQuery.getEmail()));
        }
        if (StringUtils.isNotBlank(userQuery.getNickname())) {
            userQueryContext.setNickname(fuzzyQuery(userQuery.getNickname()));
        }
        if (StringUtils.isNotBlank(userQuery.getMobile())) {
            userQueryContext.setMobile(fuzzyQuery(userQuery.getMobile()));
        }
        if (StringUtils.isNotBlank(userQuery.getKeyword())) {
            userQueryContext.setKeyword(fuzzyQuery(userQuery.getKeyword()));
        }
        if (StringUtils.isNotBlank(tenantId)) {
            userQueryContext.setTenantId(tenantId);
        }
        if (StringUtils.isNotBlank(userQuery.getAuthority())) {
            userQueryContext.setAuthority(userQuery.getAuthority());
        }

        if (StringUtils.isNotBlank(userQuery.getPersonal())) {
            Map<String, Object> tempMap = JSONUtil.parseObj(userQuery.getPersonal());
            List<UserFieldsEntity> fields =
                    UserInfoConverter.buildUserFieldsFromMap(tempMap, userDetails.getUsername());
            userQueryContext.setUserFields(fields);
        }

        if (userQuery.getIsOnline() != null) {
            userQueryContext.setIsOnline(userQuery.getIsOnline());
        }

        Set<String> orgIds = resolveOrganizationIds(
                userQuery.getOrganizationId(),
                userQuery.getIncludeChildren(),
                userQuery.getEnableDataPermission(),
                userDetails);
        userQueryContext.setOrgIds(orgIds);

        return userQueryContext;
    }

    private Set<String> resolveOrganizationIds(
            String organizationId, boolean includeChild, boolean dataPermission, UserDetails userDetails) {
        Set<String> orgIds = Sets.newHashSet();
        if (includeChild || StringUtils.isBlank(organizationId)) {
            if (!dataPermission) {
                if (StringUtils.isNotBlank(organizationId)) {
                    List<String> subOrgIds = organizationService.getChildrenById(organizationId);
                    orgIds.addAll(subOrgIds);
                }
            } else {
                orgIds.addAll(userService.getSubOrganizationIds(organizationId, userDetails));
            }
        } else {
            orgIds.add(organizationId);
        }
        return orgIds;
    }
}
