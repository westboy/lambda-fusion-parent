package com.lambda.fusion.form.integration.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.lambda.fusion.authority.organization.model.entity.UserOrganizationEntity;
import com.lambda.fusion.authority.organization.service.OrganizationService;
import com.lambda.fusion.authority.role.service.RoleService;
import com.lambda.fusion.authority.user.mapper.UserOrganizationMapper;
import com.lambda.fusion.authority.user.model.entity.UserInfoEntity;
import com.lambda.fusion.authority.user.service.UserInfoService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * authority biz 数据适配的用户检索语义：空关键词=初始列表、关键词下推 LIKE、机构范围收窄。
 *
 * @author Zx
 */
class AuthorityBizDataProviderTest {

    /** Wrapper 生成 SQL 前需初始化实体的 lambda 缓存（列名解析依赖 TableInfo） */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), UserInfoEntity.class);
    }

    private final OrganizationService organizationService = mock(OrganizationService.class);
    private final UserInfoService userInfoService = mock(UserInfoService.class);
    private final UserOrganizationMapper userOrganizationMapper = mock(UserOrganizationMapper.class);
    private final RoleService roleService = mock(RoleService.class);
    private final AuthorityBizDataProvider provider =
            new AuthorityBizDataProvider(organizationService, userInfoService, userOrganizationMapper, roleService);

    @Test
    void searchUsersWithoutKeywordReturnsInitialList() {
        List<Consumer<LambdaQueryWrapper<UserInfoEntity>>> conditions = new ArrayList<>();
        stubChain(conditions, List.of(user("zx", "张鑫"), user("jin", null)));

        List<Map<String, Object>> users = provider.searchUsers(null, 20, null);

        assertThat(users).hasSize(2);
        assertThat(users.get(0)).containsEntry("username", "zx").containsEntry("nickName", "张鑫");
        // 空关键词不下推 LIKE（否则初始列表永远为空，下拉打开无数据）
        assertThat(appliedSql(conditions)).doesNotContain("LIKE");
    }

    @Test
    void searchUsersWithKeywordPushesLikeOnNickAndUsername() {
        List<Consumer<LambdaQueryWrapper<UserInfoEntity>>> conditions = new ArrayList<>();
        stubChain(conditions, List.of(user("zx", "张鑫")));

        provider.searchUsers("张", 20, null);

        String sql = appliedSql(conditions);
        assertThat(sql).contains("DD_NICK LIKE");
        assertThat(sql).contains("USERNAME LIKE");
    }

    @Test
    void searchUsersScopedByOrganization() {
        UserOrganizationEntity member = new UserOrganizationEntity();
        member.setUsername("zx");
        member.setOrganizationId("9001");
        when(userOrganizationMapper.selectList(any())).thenReturn(List.of(member));
        List<Consumer<LambdaQueryWrapper<UserInfoEntity>>> conditions = new ArrayList<>();
        stubChain(conditions, List.of(user("zx", "张鑫")));

        List<Map<String, Object>> users = provider.searchUsers(null, 20, "9001");

        assertThat(users).hasSize(1);
        assertThat(appliedSql(conditions)).contains("USERNAME IN");
    }

    @Test
    void searchUsersReturnsEmptyWhenOrganizationHasNoMember() {
        when(userOrganizationMapper.selectList(any())).thenReturn(List.of());

        assertThat(provider.searchUsers(null, 20, "9001")).isEmpty();
    }

    /** 桩链式查询：捕获 and 条件供 SQL 语义断言，list 返回给定用户 */
    @SuppressWarnings("unchecked")
    private void stubChain(List<Consumer<LambdaQueryWrapper<UserInfoEntity>>> conditions, List<UserInfoEntity> users) {
        LambdaQueryChainWrapper<UserInfoEntity> chain = mock(LambdaQueryChainWrapper.class);
        when(chain.and(any())).thenAnswer(invocation -> {
            conditions.add(invocation.getArgument(0));
            return chain;
        });
        when(chain.last(anyString())).thenReturn(chain);
        when(chain.list()).thenReturn(users);
        when(userInfoService.lambdaQuery()).thenReturn(chain);
    }

    /** 把捕获的条件消费器应用到真实 Wrapper 上，取生成的 SQL 片段 */
    private String appliedSql(List<Consumer<LambdaQueryWrapper<UserInfoEntity>>> conditions) {
        assertThat(conditions).isNotEmpty();
        LambdaQueryWrapper<UserInfoEntity> wrapper = new LambdaQueryWrapper<>();
        conditions.get(0).accept(wrapper);
        return wrapper.getTargetSql();
    }

    private static UserInfoEntity user(String username, String nick) {
        UserInfoEntity user = new UserInfoEntity();
        user.setUsername(username);
        user.setDdNick(nick);
        return user;
    }
}
