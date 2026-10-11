package com.lambda.fusion.ai.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lambda.cloud.core.Constants;
import com.lambda.cloud.mybatis.tenant.TenantContextHolder;
import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import com.lambda.fusion.ai.runtime.operator.ToolOperatorContext;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.form.design.model.FormDefPage;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.support.DataSourceCatalog;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import io.agentscope.core.agent.RuntimeContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 元数据只读工具行为验证：平台角色门槛、输出语义（无物理列名）、代维租户绑定与错误转译。
 * 登录与 run-as 绑定复用 {@code ToolOperatorContextPocTest} 的无 Web 手法。
 *
 * @author zx
 */
class MetadataQueryToolsTest {

    private static final String RUN_ID = "console-run";

    private final FormDesignService formDesignService = mock(FormDesignService.class);
    private final FormModelProvider formModelProvider = mock(FormModelProvider.class);
    private final MetadataQueryTools tools = new MetadataQueryTools(formDesignService, formModelProvider);

    @BeforeAll
    static void setUpSaTokenConfig() {
        SaManager.getConfig().setTokenName("Authorization");
        SaManager.getConfig().setTokenPrefix("Bearer");
    }

    @AfterAll
    static void restoreSaTokenConfig() {
        SaManager.getConfig().setTokenName("satoken");
        SaManager.getConfig().setTokenPrefix("");
        ToolOperatorContext.release(RUN_ID);
    }

    private void loginOperator(String username, String tenantId, Set<String> roles) {
        SaTokenContextMockUtil.setMockContext(() -> {
            StpUtil.login(username);
            UserDetails user = new UserDetails();
            user.setUsername(username);
            user.setTenantId(tenantId);
            user.setRoles(roles);
            String token = StpUtil.getTokenValueByLoginId(username);
            StpUtil.stpLogic.getTokenSessionByToken(token).set(Constants.LOGIN_USER, user);
            return token;
        });
        ToolOperatorContext.capture(RUN_ID, StpUtil.getTokenValueByLoginId(username));
    }

    private RuntimeContext runtimeContext() {
        return RuntimeContext.builder()
                .userId("operator")
                .put(RuntimeProperty.KEY_RUN_ID, RUN_ID)
                .build();
    }

    @Test
    void listFormsReturnsBusinessSemanticsForPlatformOperator() {
        loginOperator("admin", null, Set.of(FusionConstants.ROLE_ADMIN));
        FormDefView view = new FormDefView();
        view.setFormKey("asset_check");
        view.setFormName("设备巡检");
        view.setFormType("SINGLE");
        view.setStatus("PUBLISHED");
        view.setPublishedVersion(3L);
        view.setTenantId("tenant-1");
        Page<FormDefView> mpPage = new Page<>(1, 50);
        mpPage.setRecords(List.of(view));
        mpPage.setTotal(1);
        when(formDesignService.page(any(FormDefPage.class))).thenReturn(PageView.of(mpPage));

        String result = tools.listForms(runtimeContext());

        assertThat(result)
                .contains("asset_check")
                .contains("设备巡检")
                .contains("tenant-1")
                .contains("PUBLISHED");
    }

    @Test
    void nonPlatformOperatorIsDeniedWithoutTouchingServices() {
        loginOperator("tenant-user", "tenant-9", Set.of(FusionConstants.ROLE_USER));

        String result = tools.listForms(runtimeContext());

        assertThat(result).contains("平台开发者或管理员");
        verifyNoInteractions(formDesignService, formModelProvider);
    }

    @Test
    void getFormFieldsExposesBusinessSemanticsWithoutPhysicalColumns() {
        loginOperator("admin", null, Set.of(FusionConstants.ROLE_DEV));
        FormField field = new FormField(
                "status",
                "设备状态",
                "device_status",
                "DICT",
                true,
                null,
                "asset_status_dict",
                1,
                true,
                null,
                new FormModel.QueryConf(true, "eq", null),
                null,
                null,
                null,
                null);
        FormModel model = new FormModel(
                "asset_check",
                "设备巡检",
                3L,
                "SINGLE",
                "la_demo_asset_check",
                "ds-1",
                null,
                null,
                null,
                null,
                List.of(field),
                List.of(),
                null,
                null,
                null,
                null);
        when(formModelProvider.require("asset_check")).thenReturn(model);

        String result = tools.getFormFields(runtimeContext(), "asset_check", null);

        assertThat(result).contains("设备状态").contains("asset_status_dict").contains("可查询=是");
        // 物理表名与物理列名绝不出现在工具输出
        assertThat(result).doesNotContain("la_demo_asset_check").doesNotContain("device_status");
    }

    /** 代维模式：平台管理员显式传入 tenantId → 工具在目标租户上下文内执行。 */
    @Test
    void getFormFieldsWithExplicitTenantBindsTargetTenant() {
        loginOperator("admin", null, Set.of(FusionConstants.ROLE_ADMIN));
        AtomicReference<String> tenantInView = new AtomicReference<>();
        when(formModelProvider.require("asset_check")).thenAnswer(invocation -> {
            tenantInView.set(TenantContextHolder.getCurrentTenantId());
            throw new RuntimeException("模拟中止");
        });

        String result = tools.getFormFields(runtimeContext(), "asset_check", "tenant-1");

        assertThat(tenantInView).hasValue("tenant-1");
        assertThat(result).startsWith("查询失败：");
    }

    @Test
    void runtimeErrorsAreTranslatedIntoToolResultText() {
        loginOperator("admin", null, Set.of(FusionConstants.ROLE_DEV));
        when(formModelProvider.require("missing")).thenThrow(new RuntimeException("表单不存在"));

        String result = tools.getFormFields(runtimeContext(), "missing", null);

        assertThat(result).startsWith("查询失败：").contains("表单不存在");
    }

    @Test
    void listDataSourcesReturnsCatalogOptions() {
        loginOperator("admin", null, Set.of(FusionConstants.ROLE_ADMIN));
        when(formDesignService.listDataSources())
                .thenReturn(List.of(new DataSourceCatalog.Option("ds-1", "业务主库", "mysql")));

        String result = tools.listDataSources(runtimeContext());

        assertThat(result).contains("ds-1").contains("业务主库").contains("mysql");
    }
}
