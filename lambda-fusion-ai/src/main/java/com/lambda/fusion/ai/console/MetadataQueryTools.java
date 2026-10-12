package com.lambda.fusion.ai.console;

import com.lambda.fusion.ai.runtime.gateway.RuntimeProperty;
import com.lambda.fusion.ai.runtime.operator.ToolOperatorContext;
import com.lambda.fusion.core.FusionConstants;
import com.lambda.fusion.core.identity.UserDetails;
import com.lambda.fusion.core.pagination.PageView;
import com.lambda.fusion.core.utils.AuthUtils;
import com.lambda.fusion.form.design.model.FormDefPage;
import com.lambda.fusion.form.design.model.FormDefView;
import com.lambda.fusion.form.design.service.FormDesignService;
import com.lambda.fusion.form.design.support.DataSourceCatalog;
import com.lambda.fusion.form.runtime.support.FormModel;
import com.lambda.fusion.form.runtime.support.FormModel.FormField;
import com.lambda.fusion.form.runtime.support.FormModelProvider;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 控制台助手元数据只读工具（能力包①）：面向平台开发者/管理员，回答「平台有哪些表单/数据源、
 * 表单有什么字段」等元数据问题。由 {@code AiConfigure.ConsoleConfiguration} 按 form 在 classpath
 * 条件装配，经 {@code ToolkitAssembler.registerMetadataTools} 全局注册进各应用工具集
 * （ObjectProvider 模式，form 缺失时优雅降级，契约 §3.3/§4.1）。
 *
 * <p>执行语义：每个工具经 {@link ToolOperatorContext#callAs} 在操作人会话与租户上下文内执行——
 * 平台开发者/管理员无租户归属即平台模式（跨租户元数据 listing）；显式传入 tenantId 即代维模式。
 * 工具级平台角色门槛 + run-as 匿名对话 fail-closed 双闸。输出只含业务语义（字段标识/中文名/
 * 类型/字典编码），不含物理表名列名。
 *
 * @author zx
 */
@Slf4j
public class MetadataQueryTools {

    /** 单次列出的表单上限（防上下文爆炸，更多结果提示加条件） */
    private static final int MAX_LIST_SIZE = 50;

    private static final Set<String> PLATFORM_ROLES = Set.of(FusionConstants.ROLE_DEV, FusionConstants.ROLE_ADMIN);

    private final FormDesignService formDesignService;
    private final FormModelProvider formModelProvider;

    /**
     * 创建元数据工具集。
     *
     * @param formDesignService 表单设计服务（form 模块）
     * @param formModelProvider 已发布表单模型读取（form 模块）
     */
    public MetadataQueryTools(FormDesignService formDesignService, FormModelProvider formModelProvider) {
        this.formDesignService = formDesignService;
        this.formModelProvider = formModelProvider;
    }

    /** 列出平台业务表单清单。 */
    @Tool(
            name = "list_forms",
            description = "List business forms registered on the platform (form key, name, type, status,"
                    + " published version, tenant). Use this when the user asks which forms exist"
                    + " or before inspecting a form's structure.")
    public String listForms(RuntimeContext context) {
        return execute(context, null, () -> {
            FormDefPage page = new FormDefPage();
            page.setPageNum(1);
            page.setPageSize(MAX_LIST_SIZE);
            PageView<FormDefView> result = formDesignService.page(page);
            if (result.getRecords().isEmpty()) {
                // 平台模式跨全部租户查询仍为空：大概率后端连接的库里确实没有表单定义
                return "平台上暂无表单定义（平台模式：已跨全部租户查询）。" + "若表单在其他环境/数据库，请确认后端数据源连接。";
            }
            StringJoiner rows = new StringJoiner("\n");
            for (FormDefView view : result.getRecords()) {
                rows.add(String.format(
                        "- 表单标识=%s，名称=%s，类型=%s，状态=%s，发布版本=%s，租户=%s",
                        orUnknown(view.getFormKey()),
                        orUnknown(view.getFormName()),
                        orUnknown(view.getFormType()),
                        orUnknown(view.getStatus()),
                        orUnknown(String.valueOf(view.getPublishedVersion())),
                        orUnknown(view.getTenantId())));
            }
            long total = result.getTotal();
            String truncated = total > result.getRecords().size()
                    ? "（共 " + total + " 个，仅列出前 " + result.getRecords().size() + " 个）"
                    : "共 " + total + " 个";
            return "平台表单清单" + truncated + "：\n" + rows;
        });
    }

    /** 读取指定表单的字段定义（业务语义，不含物理列）。 */
    @Tool(
            name = "get_form_fields",
            description = "Read a form's field definitions (field name, Chinese label, type, dict code,"
                    + " queryable flag). Physical column names are never exposed. tenantId is only"
                    + " needed when a platform admin inspects another tenant's form; obtain it from"
                    + " list_forms results.")
    public String getFormFields(
            RuntimeContext context,
            @ToolParam(name = "formKey", description = "表单标识，来自 list_forms 结果") String formKey,
            @ToolParam(name = "tenantId", description = "归属租户（平台管理员跨租户查看时传入）", required = false) String tenantId) {
        return execute(context, tenantId, () -> {
            if (!StringUtils.hasText(formKey)) {
                return "缺少表单标识（formKey），请从 list_forms 结果中选择。";
            }
            FormModel model = formModelProvider.require(formKey);
            List<FormField> fields = model.fields();
            if (fields.isEmpty()) {
                return "表单 " + formKey + "（" + orUnknown(model.formName()) + "）暂无字段定义。";
            }
            StringJoiner rows = new StringJoiner("\n");
            for (FormField field : fields) {
                rows.add(String.format(
                        "- 字段标识=%s，名称=%s，类型=%s%s%s，可查询=%s",
                        orUnknown(field.name()),
                        orUnknown(field.label()),
                        orUnknown(field.type()),
                        StringUtils.hasText(field.dictType()) ? "，字典=" + field.dictType() : "",
                        field.queryable() ? "" : "（不参与查询）",
                        field.queryable() ? "是" : "否"));
            }
            return "表单 " + formKey + "（" + orUnknown(model.formName()) + "）字段定义：\n" + rows;
        });
    }

    /** 列出平台注册的数据源。 */
    @Tool(
            name = "list_datasources",
            description = "List datasources registered on the platform (id, name, database type)."
                    + " Use this when the user asks which datasources exist.")
    public String listDataSources(RuntimeContext context) {
        return execute(context, null, () -> {
            List<DataSourceCatalog.Option> options = formDesignService.listDataSources();
            if (options.isEmpty()) {
                return "平台暂无注册数据源（仅主库可用）。";
            }
            StringJoiner rows = new StringJoiner("\n");
            for (DataSourceCatalog.Option option : options) {
                rows.add(String.format(
                        "- 标识=%s，名称=%s，类型=%s",
                        orUnknown(option.id()), orUnknown(option.name()), orUnknown(option.dbType())));
            }
            return "平台数据源清单：\n" + rows;
        });
    }

    /**
     * 统一执行骨架：绑定作用域内平台角色门槛 → 异常转译为工具结果。
     * 显式 tenantId（代维）→ 绑定目标租户执行；未指定 → 平台模式（跨全部租户，
     * 操作者定位即"不属于任何租户的开发者/管理员"，不回落会话/操作人租户）。
     */
    private String execute(RuntimeContext context, String explicitTenantId, Supplier<String> body) {
        try {
            String runId = RuntimeProperty.runId(context);
            Supplier<String> guarded = () -> {
                // 平台角色门槛在绑定作用域内执行：读取的是绑定会话中的操作人角色
                String denied = platformOperatorDenied();
                if (denied != null) {
                    return denied;
                }
                return body.get();
            };
            return StringUtils.hasText(explicitTenantId)
                    ? ToolOperatorContext.callAs(runId, explicitTenantId, guarded)
                    : ToolOperatorContext.callAsPlatform(runId, guarded);
        } catch (RuntimeException e) {
            // 工具错误以可读文本返回，交由 Agent 自纠错/如实答复，不中断 ReAct 循环
            log.warn("控制台元数据查询失败: {}", e.getMessage());
            return "查询失败：" + e.getMessage();
        }
    }

    /** 平台角色门槛：仅平台开发者/管理员可用（租户门槛按对话入口与审计约束，见设计文档 §4.2）。 */
    private static String platformOperatorDenied() {
        UserDetails operator = AuthUtils.getUser();
        Set<String> roles = operator == null || operator.getRoles() == null ? Set.of() : operator.getRoles();
        if (PLATFORM_ROLES.stream().noneMatch(roles::contains)) {
            return "当前操作人不是平台开发者或管理员，无权使用控制台助手元数据查询。";
        }
        return null;
    }

    private static String orUnknown(String value) {
        return StringUtils.hasText(value) ? value : "未知";
    }
}
