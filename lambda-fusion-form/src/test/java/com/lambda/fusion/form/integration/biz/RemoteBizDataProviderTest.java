package com.lambda.fusion.form.integration.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lambda.fusion.authority.api.RemoteOrg;
import com.lambda.fusion.authority.api.RemoteOrganizationService;
import com.lambda.fusion.authority.api.RemotePage;
import com.lambda.fusion.authority.api.RemoteRole;
import com.lambda.fusion.authority.api.RemoteRoleService;
import com.lambda.fusion.authority.api.RemoteUser;
import com.lambda.fusion.authority.api.RemoteUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 远程契约 biz 数据适配：机构树客户端嵌套、用户检索参数透传、标签批量映射、服务缺席降级。
 *
 * @author Zx
 */
class RemoteBizDataProviderTest {

    private final RemoteOrganizationService organizationService = mock(RemoteOrganizationService.class);
    private final RemoteUserService userService = mock(RemoteUserService.class);
    private final RemoteRoleService roleService = mock(RemoteRoleService.class);
    private final RemoteBizDataProvider provider =
            new RemoteBizDataProvider(organizationService, userService, roleService);

    @Test
    void organizationTreeNestsFlatListByParentId() {
        when(organizationService.listOrganizations(isNull(), isNull()))
                .thenReturn(List.of(
                        new RemoteOrg("root", "拉姆达软件工作室", null, ""),
                        new RemoteOrg("child", "软件部", null, "root"),
                        new RemoteOrg("orphan-parent", "游离父节点", null, "ghost")));

        List<Map<String, Object>> tree = provider.organizationTree();

        // 根 = 父为空或父不在结果集内；子节点挂到父的 children
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0)).containsEntry("id", "root").containsEntry("name", "拉姆达软件工作室");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children =
                (List<Map<String, Object>>) tree.get(0).get("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0)).containsEntry("id", "child");
        assertThat(tree.get(1)).containsEntry("id", "orphan-parent");
    }

    @Test
    void searchUsersPassesKeywordOrgIdAndClampedPageSize() {
        RemoteUser user = new RemoteUser();
        user.setUsername("zx");
        user.setNickname("张鑫");
        when(userService.pageUsers(isNull(), eq("张"), eq("9001"), eq(1), eq(20)))
                .thenReturn(new RemotePage<>(1, List.of(user)));

        List<Map<String, Object>> users = provider.searchUsers("张", 20, "9001");

        assertThat(users).hasSize(1);
        assertThat(users.get(0)).containsEntry("username", "zx").containsEntry("nickName", "张鑫");
    }

    @Test
    void searchUsersOversizedPageSizeIsClamped() {
        when(userService.pageUsers(any(), any(), any(), eq(1), eq(20))).thenReturn(new RemotePage<>(0, List.of()));
        provider.searchUsers(null, 500, null);
        org.mockito.Mockito.verify(userService).pageUsers(any(), any(), any(), eq(1), eq(20));
    }

    @Test
    void rolesMapAuthorityAndAlias() {
        when(roleService.listRoles(isNull(), isNull())).thenReturn(List.of(new RemoteRole("ROLE_DEV", "开发工程师")));

        List<Map<String, Object>> roles = provider.roles();

        assertThat(roles).hasSize(1);
        assertThat(roles.get(0)).containsEntry("authority", "ROLE_DEV").containsEntry("name", "开发工程师");
    }

    @Test
    void labelsDelegateToBatchContractAndFilterByIds() {
        when(userService.mapNicknames(any())).thenReturn(Map.of("zx", "张鑫"));
        when(roleService.listRoles(any(), any()))
                .thenReturn(List.of(new RemoteRole("ROLE_DEV", "开发工程师"), new RemoteRole("ROLE_X", "其他")));
        when(organizationService.listOrganizations(any(), any()))
                .thenReturn(List.of(new RemoteOrg("root", "总", null, ""), new RemoteOrg("child", "子", null, "root")));

        assertThat(provider.labels("USER", List.of("zx", "ghost"))).containsEntry("zx", "张鑫");
        assertThat(provider.labels("ROLE", List.of("ROLE_X"))).containsEntry("ROLE_X", "其他");
        assertThat(provider.labels("ORG", List.of("child"))).containsEntry("child", "子");
        assertThat(provider.labels("ORG", List.of("missing"))).isEmpty();
    }

    @Test
    void absentRemoteServicesDegradeToEmptyResults() {
        RemoteBizDataProvider degraded = new RemoteBizDataProvider(null, null, null);

        assertThat(degraded.organizationTree()).isEmpty();
        assertThat(degraded.searchUsers("k", 20, null)).isEmpty();
        assertThat(degraded.roles()).isEmpty();
        assertThat(degraded.labels("USER", List.of("zx"))).isEmpty();
        assertThat(degraded.labels("ORG", List.of("9001"))).isEmpty();
    }
}
