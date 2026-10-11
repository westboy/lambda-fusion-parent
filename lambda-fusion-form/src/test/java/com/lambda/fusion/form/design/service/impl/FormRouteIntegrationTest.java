package com.lambda.fusion.form.design.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.lambda.cloud.core.utils.StpLogicUtils;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.authority.api.RemoteCreateResource;
import com.lambda.fusion.authority.api.RemoteMoveResource;
import com.lambda.fusion.authority.api.RemoteResource;
import com.lambda.fusion.authority.api.RemoteResourceService;
import com.lambda.fusion.authority.authentication.assembler.MenuRouteAssembler;
import com.lambda.fusion.authority.authentication.mapper.AuthenticationMapper;
import com.lambda.fusion.authority.authentication.model.MenuQuery;
import com.lambda.fusion.authority.exception.AuthorityBusinessException;
import com.lambda.fusion.authority.exception.AuthorityErrorCode;
import com.lambda.fusion.authority.resource.mapper.ApiResourceMapper;
import com.lambda.fusion.authority.resource.mapper.ResourceMapper;
import com.lambda.fusion.authority.resource.model.CreateResourceConverterImpl;
import com.lambda.fusion.authority.resource.model.ResourceConverterImpl;
import com.lambda.fusion.authority.resource.model.entity.ResourceEntityConverterImpl;
import com.lambda.fusion.authority.resource.service.impl.PermissionServiceImpl;
import com.lambda.fusion.authority.resource.service.impl.ResourceServiceImpl;
import com.lambda.fusion.authority.resource.support.AuthorizationCache;
import com.lambda.fusion.authority.role.mapper.AccessPermissionMapper;
import com.lambda.fusion.authority.role.mapper.RoleGroupMapper;
import com.lambda.fusion.authority.role.mapper.RoleMapper;
import com.lambda.fusion.authority.role.service.RoleService;
import com.lambda.fusion.authority.role.service.impl.RoleServiceImpl;
import com.lambda.fusion.authority.user.mapper.UserRoleMapper;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.tree.filter.TreeDataFilter;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.design.adapter.FormRouteGateway;
import com.lambda.fusion.form.design.adapter.RemoteFormRouteGateway;
import com.lambda.fusion.form.design.mapper.FormDefMapper;
import com.lambda.fusion.form.design.model.FormRouteCommand;
import com.lambda.fusion.form.design.model.FormRouteInfo;
import com.lambda.fusion.form.design.service.FormRouteService;
import com.lambda.fusion.form.exception.FormBusinessException;
import com.lambda.fusion.form.exception.FormErrorCode;
import com.lambda.fusion.permission.service.ApiPermissionRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 真实 authority 服务、Mapper、租户过滤和事务：挂载只生成资源，授权必须明确执行。 */
class FormRouteIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private FormRouteService routes;
    private JdbcTemplate jdbc;
    private MockedStatic<StpLogicUtils> loginContext;
    private StpLogic activeLogic;

    @BeforeEach
    void setUp() {
        activeLogic = mock(StpLogic.class);
        loginContext = mockStatic(StpLogicUtils.class);
        loginContext.when(StpLogicUtils::getActiveStpLogic).thenReturn(activeLogic);
        TenantContextHolder.getInstance().setTenantId("T1");
        context = new AnnotationConfigApplicationContext(Config.class);
        routes = context.getBean(FormRouteService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.update(
                "INSERT INTO la_form_def (ID, FORM_KEY, FORM_NAME, FORM_TYPE, TABLE_NAME, STATUS, PUBLISHED_VERSION, VERSION, TENANT_ID) VALUES ('f1','leave','请假','SINGLE','LA_TEST_BUSINESS','PUBLISHED',1,0,'T1')");
        jdbc.update("INSERT INTO la_form_def_version (ID,FORM_ID,VERSION,SNAPSHOT_JSON) VALUES ('v1','f1',1,'{}')");
    }

    @AfterEach
    void tearDown() {
        loginContext.close();
        context.close();
        TenantContextHolder.getInstance().close();
    }

    @Test
    void developerCanMountAndRevokeWithoutIndividualResourcePermissions() {
        org.mockito.Mockito.when(activeLogic.hasRole(FusionConstants.ROLE_DEV)).thenReturn(true);
        FormRouteInfo route = routes.save("f1", command("/forms/developer"));
        assertThat(route.mounted()).isTrue();
        routes.remove("f1");
        assertThat(routes.detail("f1").mounted()).isFalse();
        org.mockito.Mockito.verify(activeLogic, org.mockito.Mockito.never())
                .checkPermission(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void routeUpdateKeepsIdentityAndExplicitPermissionsWhileRevokeKeepsVersions() {
        try (var security = mockStatic(AuthUtils.class)) {
            FormRouteInfo first = routes.save("f1", command("/forms/leave"));
            assertThat(first.mounted()).isTrue();
            assertThat(first.actions()).hasSize(6);
            assertThat(count("la_resources")).isEqualTo(8);
            assertThat(count("la_api_resources")).isEqualTo(6);
            assertThat(count("la_role_resources")).isZero();
            var authentication = context.getBean(AuthenticationMapper.class);
            assertThat(authentication.selectPermissionsByUsername("alice")).isEmpty();
            UserDetails operator = new UserDetails();
            operator.setUsername("developer");
            operator.setTenantId("T1");
            operator.setRoles(Set.of(FusionConstants.ROLE_DEV));
            String readId = actionId(first, "page");
            context.getBean(RoleService.class).grantRolePermission("R1", readId, 0, operator);
            assertThat(authentication.selectPermissionsByUsername("alice")).containsExactly("form:leave:page");
            assertThat(authentication.selectAuthoritiesByUsername("alice"))
                    .contains("form-ui:leave:page")
                    .doesNotContain("form:leave:page");
            context.getBean(com.lambda.fusion.authority.resource.service.PermissionService.class)
                    .unbind(readId, "form:leave:page");
            assertThat(authentication.selectPermissionsByUsername("alice")).isEmpty();
            assertThat(authentication.selectAuthoritiesByUsername("alice")).doesNotContain("form:leave:page");

            FormRouteInfo updated = routes.save("f1", command("/forms/leave-updated"));
            assertThat(updated.resourceId()).isEqualTo(first.resourceId());
            assertThat(updated.actions()).isEqualTo(first.actions());
            assertThat(count("la_resources")).isEqualTo(8);
            assertThat(count("la_form_def_version")).isEqualTo(1);
            assertThat(authentication.selectPermissionsByUsername("alice")).containsExactly("form:leave:page");
            MenuQuery query = new MenuQuery();
            query.setMode(0);
            var menu = authentication.selectNavigation(query).stream()
                    .filter(item -> first.resourceId().equals(item.getId()))
                    .findFirst()
                    .orElseThrow();
            context.getBean(MenuRouteAssembler.class).enrich(List.of(menu));
            assertThat(menu.getPath()).isEqualTo("/forms/leave-updated");
            assertThat(menu.getComponent()).isEqualTo("/form/runtime/index");
            assertThat(menu.getMeta().getExtra()).containsEntry("formKey", "leave");
            assertThat(menu.getName()).isEqualTo("resource:" + first.resourceId());

            routes.remove("f1");
            assertThat(routes.detail("f1").mounted()).isFalse();
            assertThat(count("la_resources")).isEqualTo(1);
            assertThat(count("la_api_resources")).isZero();
            assertThat(authentication.selectPermissionsByUsername("alice")).isEmpty();
            assertThat(count("la_form_def_version")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT PUBLISHED_VERSION FROM la_form_def WHERE ID='f1'", Long.class))
                    .isEqualTo(1L);
        }
    }

    @Test
    void conflictingPathAndMissingMenuPermissionRollbackWithoutOrphanResources() {
        try (var security = mockStatic(AuthUtils.class)) {
            assertThatThrownBy(() -> routes.save("f1", command("/parent"))).isInstanceOf(RuntimeException.class);
            assertThat(count("la_resources")).isEqualTo(1);
            assertThat(routes.detail("f1").mounted()).isFalse();
            security.when(() -> AuthUtils.checkPermission("authority:resource:api-permission-bind"))
                    .thenThrow(new SecurityException("denied"));
            assertThatThrownBy(() -> routes.save("f1", command("/forms/leave"))).isInstanceOf(SecurityException.class);
            assertThat(count("la_resources")).isEqualTo(1);
            assertThat(count("la_api_resources")).isZero();
        }
    }

    @Test
    void draftDisabledAndOtherTenantFormsCannotGetNewRoutes() {
        try (var security = mockStatic(AuthUtils.class)) {
            jdbc.update("UPDATE la_form_def SET STATUS='DISABLED' WHERE ID='f1'");
            assertThatThrownBy(() -> routes.save("f1", command("/forms/leave"))).isInstanceOf(RuntimeException.class);
            jdbc.update("UPDATE la_form_def SET PUBLISHED_VERSION=NULL, STATUS='DRAFT' WHERE ID='f1'");
            assertThatThrownBy(() -> routes.save("f1", command("/forms/leave"))).isInstanceOf(RuntimeException.class);
            TenantContextHolder.getInstance().setTenantId("T2");
            assertThatThrownBy(() -> routes.detail("f1")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> routes.save("f1", command("/forms/leave"))).isInstanceOf(RuntimeException.class);
            assertThat(count("la_resources")).isEqualTo(1);
        }
    }

    @Test
    void cacheEvictionWaitsForCommitAndRollbackKeepsPreviousCache() {
        var cache = context.getBean(CacheManager.class).getCache("auth:permission");
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        try (var security = mockStatic(AuthUtils.class)) {
            cache.put("alice", "cached");
            transaction.executeWithoutResult(status -> {
                routes.save("f1", command("/forms/leave"));
                assertThat(cache.get("alice", String.class)).isEqualTo("cached");
                status.setRollbackOnly();
            });
            assertThat(cache.get("alice", String.class)).isEqualTo("cached");
            assertThat(routes.detail("f1").mounted()).isFalse();
            transaction.executeWithoutResult(status -> routes.save("f1", command("/forms/leave")));
            assertThat(cache.get("alice")).isNull();
        }
    }

    @Test
    void concurrentPublicationOfSameFormIsIdempotent() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> saveInTenant("f1"));
            var second = executor.submit(() -> saveInTenant("f1"));
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(count("la_resources")).isEqualTo(8);
        assertThat(count("la_api_resources")).isEqualTo(6);
    }

    @Test
    void concurrentDifferentFormsCannotClaimTheSamePath() throws Exception {
        jdbc.update(
                "INSERT INTO la_form_def (ID,FORM_KEY,FORM_NAME,FORM_TYPE,TABLE_NAME,STATUS,PUBLISHED_VERSION,VERSION,TENANT_ID) VALUES ('f2','other','其它表单','SINGLE','LA_TEST_BUSINESS','PUBLISHED',1,0,'T1')");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimPath("f1"));
            var second = executor.submit(() -> claimPath("f2"));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("la_resources")).isEqualTo(8);
        assertThat(count("la_api_resources")).isEqualTo(6);
    }

    @Test
    void revokeRefusesForeignChildrenInsteadOfDeletingOtherFeatures() {
        try (var security = mockStatic(AuthUtils.class)) {
            FormRouteInfo route = routes.save("f1", command("/forms/leave"));
            jdbc.update(
                    "INSERT INTO la_resources(ID,RES_NAME,PARENT_ID,PARENT_KEYS,RES_TYPE,RES_LEVEL,RES_MODE,ORDER_NO) VALUES ('foreign','其它功能',?,?,3,2147483647,0,5)",
                    route.resourceId(),
                    "parent-" + route.resourceId());
            assertThatThrownBy(() -> routes.remove("f1"))
                    .isInstanceOf(FormBusinessException.class)
                    .satisfies(error -> {
                        var conflict = (FormBusinessException) error;
                        assertThat(conflict.getCode()).isEqualTo(FormErrorCode.FORM_ROUTE_CONFLICT.getCode());
                        assertThat(String.valueOf(conflict.getArgs()[0])).contains("非本表单管理");
                    });
            assertThat(count("la_resources")).isEqualTo(9);
            assertThat(count("la_api_resources")).isEqualTo(6);
        }
    }

    private boolean claimPath(String id) {
        try {
            saveInTenant(id);
            return true;
        } catch (AuthorityBusinessException error) {
            assertThat(error.getCode()).isEqualTo(AuthorityErrorCode.RESOURCE_PATH_CONFLICT.getCode());
            return false;
        }
    }

    private String saveInTenant(String id) {
        try (var tenant = TenantContextHolder.getInstance();
                var security = mockStatic(AuthUtils.class);
                var login = mockStatic(StpLogicUtils.class)) {
            login.when(StpLogicUtils::getActiveStpLogic).thenReturn(mock(StpLogic.class));
            tenant.setTenantId("T1");
            return routes.save(id, command("/forms/leave")).resourceId();
        }
    }

    private FormRouteCommand command(String path) {
        return new FormRouteCommand("parent", "请假管理", path, "lucide:table", 1);
    }

    private String actionId(FormRouteInfo route, String action) {
        return route.actions().stream()
                .filter(item -> action.equals(item.action()))
                .findFirst()
                .orElseThrow()
                .resourceId();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({
        FormRouteServiceImpl.class,
        ResourceServiceImpl.class,
        PermissionServiceImpl.class,
        RoleServiceImpl.class,
        AuthorizationCache.class,
        MenuRouteAssembler.class,
        SpringUtil.class,
        CreateResourceConverterImpl.class,
        ResourceConverterImpl.class,
        ResourceEntityConverterImpl.class
    })
    static class Config {
        @Bean
        DataSource dataSource() {
            DataSource source = new FormLifecycleIntegrationTest.Config().dataSource();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute(
                    "CREATE TABLE la_resources (ID VARCHAR(64) PRIMARY KEY, RES_NAME VARCHAR(128), RES_PATH VARCHAR(200), RES_URL VARCHAR(510), PARENT_ID VARCHAR(64), RES_LEVEL INT, ORDER_NO INT, ICON VARCHAR(128), METHOD VARCHAR(255), HIDDEN BOOLEAN DEFAULT FALSE, RES_TYPE INT, PARENT_KEYS VARCHAR(1000), RES_MODE INT DEFAULT 0, APPLICATION VARCHAR(64), REMARK VARCHAR(2000), KEEP_ALIVE BOOLEAN DEFAULT FALSE, EXPAND VARCHAR(1024), BUSINESS_EXPAND VARCHAR(1024), IS_DEV INT DEFAULT 0, KEY_NAME VARCHAR(64))");
            jdbc.execute("CREATE TABLE la_resource_tree_lock (ID VARCHAR(16) PRIMARY KEY)");
            jdbc.execute("INSERT INTO la_resource_tree_lock VALUES ('tree')");
            jdbc.execute(
                    "CREATE TABLE la_api_resources (RESOURCE_ID VARCHAR(64), PERMISSION_ID VARCHAR(255), PRIMARY KEY(RESOURCE_ID,PERMISSION_ID))");
            jdbc.execute(
                    "CREATE TABLE la_role_resources (AUTHORITY VARCHAR(64), RESOURCE_ID VARCHAR(64), TENANT_ID VARCHAR(32), STATUS INT, PRIMARY KEY(AUTHORITY,RESOURCE_ID))");
            jdbc.execute("CREATE TABLE la_user_roles (USERNAME VARCHAR(64), AUTHORITY VARCHAR(64))");
            jdbc.execute("CREATE TABLE la_organization_roles (ORGANIZATION_ID VARCHAR(64), AUTHORITY VARCHAR(64))");
            jdbc.execute("CREATE TABLE la_user_organization (USERNAME VARCHAR(64), ORGANIZATION_ID VARCHAR(64))");
            jdbc.execute(
                    "CREATE TABLE la_organization_resources (RESOURCE_ID VARCHAR(64), ORGANIZATION_ID VARCHAR(64))");
            jdbc.execute("CREATE TABLE la_applications (ID VARCHAR(64), STANDALONE INT)");
            jdbc.execute(
                    "INSERT INTO la_resources(ID,RES_NAME,RES_PATH,RES_TYPE,RES_LEVEL,ORDER_NO,RES_MODE,PARENT_KEYS) VALUES ('parent','业务菜单','/parent',1,0,1,0,'')");
            jdbc.execute("INSERT INTO la_user_roles VALUES ('alice','R1')");
            return source;
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            SqlSessionFactory factory = new FormLifecycleIntegrationTest.Config().sqlSessionFactory(source);
            var configuration = factory.getConfiguration();
            var tenant = new MybatisPlusInterceptor();
            tenant.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
                @Override
                public Expression getTenantId() {
                    return new StringValue(TenantContextHolder.getCurrentTenantId());
                }
            }));
            configuration.addInterceptor(tenant);
            for (String name : List.of("ResourceMapper", "ApiResourceMapper", "RoleMapper", "AuthenticationMapper")) {
                String location = "mapper/" + name + ".xml";
                try (var stream = new ClassPathResource(location).getInputStream()) {
                    new XMLMapperBuilder(stream, configuration, location, configuration.getSqlFragments()).parse();
                }
            }
            return factory;
        }

        @Bean
        SqlSessionTemplate template(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        FormDefMapper forms(SqlSessionTemplate template) {
            return template.getMapper(FormDefMapper.class);
        }

        @Bean
        ResourceMapper resources(SqlSessionTemplate template) {
            return template.getMapper(ResourceMapper.class);
        }

        @Bean
        ApiResourceMapper apiResources(SqlSessionTemplate template) {
            return template.getMapper(ApiResourceMapper.class);
        }

        @Bean
        RoleMapper roles(SqlSessionTemplate template) {
            return template.getMapper(RoleMapper.class);
        }

        @Bean
        AuthenticationMapper authentication(SqlSessionTemplate template) {
            return template.getMapper(AuthenticationMapper.class);
        }

        @Bean
        RoleGroupMapper roleGroups() {
            return mock(RoleGroupMapper.class);
        }

        @Bean
        UserRoleMapper userRoles() {
            return mock(UserRoleMapper.class);
        }

        @Bean
        AccessPermissionMapper accessPermissions() {
            return mock(AccessPermissionMapper.class);
        }

        @Bean
        TreeDataFilter treeFilter() {
            return mock(TreeDataFilter.class);
        }

        @Bean
        ObjectMapper json() {
            return new ObjectMapper();
        }

        @Bean
        ApiPermissionRegistry registry() {
            return new ApiPermissionRegistry(List.of());
        }

        @Bean
        CacheManager caches() {
            return new ConcurrentMapCacheManager("auth:permission", "auth:role", "ResourceOwners");
        }

        @Bean
        PlatformTransactionManager transactions(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        FormRouteGateway gateway(
                com.lambda.fusion.authority.resource.service.ResourceService resources,
                com.lambda.fusion.authority.resource.service.PermissionService permissions,
                ObjectMapper json) {
            // 契约 stub：把真实 authority 服务包成 RemoteResourceService（对齐生产适配器的映射边界）
            return new RemoteFormRouteGateway(new StubRemoteResourceService(resources, permissions), json);
        }
    }

    /** RemoteResourceService 契约到真实 authority 服务的测试桩（映射与生产适配器一致） */
    static class StubRemoteResourceService implements RemoteResourceService {
        private final com.lambda.fusion.authority.resource.service.ResourceService resources;
        private final com.lambda.fusion.authority.resource.service.PermissionService permissions;

        StubRemoteResourceService(
                com.lambda.fusion.authority.resource.service.ResourceService resources,
                com.lambda.fusion.authority.resource.service.PermissionService permissions) {
            this.resources = resources;
            this.permissions = permissions;
        }

        @Override
        public RemoteResource getResourceById(String id) {
            return toRemote(resources.getResourceById(id));
        }

        @Override
        public java.util.List<RemoteResource> queryAvailableChildren(String parentId) {
            com.lambda.fusion.authority.resource.model.Resource parent = resources.getResourceById(parentId);
            if (parent == null) {
                return java.util.List.of();
            }
            return resources.queryAvailableChildren(parent).stream()
                    .map(StubRemoteResourceService::toRemote)
                    .toList();
        }

        @Override
        public RemoteResource addResource(RemoteCreateResource create) {
            com.lambda.fusion.authority.resource.model.CreateResource domain =
                    new com.lambda.fusion.authority.resource.model.CreateResource();
            domain.setParentId(create.getParentId());
            domain.setResName(create.getResName());
            domain.setResPath(create.getResPath());
            domain.setResType(create.getResType());
            domain.setResMode(create.getResMode());
            domain.setApplication(create.getApplication());
            domain.setIcon(create.getIcon());
            domain.setExpand(create.getExpand());
            domain.setBusinessExpand(create.getBusinessExpand());
            return toRemote(resources.addResource(domain));
        }

        @Override
        public void updateResource(RemoteResource resource) {
            com.lambda.fusion.authority.resource.model.Resource current = resources.getResourceById(resource.getId());
            current.setResName(resource.getResName());
            current.setResPath(resource.getResPath());
            current.setIcon(resource.getIcon());
            current.setOrderNo(resource.getOrderNo());
            current.setExpand(resource.getExpand());
            current.setKeepAlive(resource.getKeepAlive());
            current.setApplication(resource.getApplication());
            resources.updateResource(current);
        }

        @Override
        public void moveResource(RemoteMoveResource move) {
            com.lambda.fusion.authority.resource.model.MoveResource domain =
                    new com.lambda.fusion.authority.resource.model.MoveResource();
            domain.setId(move.getId());
            domain.setTid(move.getTid());
            domain.setType(move.getType());
            resources.move(domain);
        }

        @Override
        public void deleteResource(String id) {
            resources.deleteResource(id);
        }

        @Override
        public void lockResourceTree() {
            resources.lockResourceTree();
        }

        @Override
        public void bindPermission(String resourceId, String permissionId) {
            permissions.bind(resourceId, permissionId);
        }

        private static RemoteResource toRemote(com.lambda.fusion.authority.resource.model.Resource resource) {
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
}
