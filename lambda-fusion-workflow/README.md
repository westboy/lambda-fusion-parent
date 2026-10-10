# Lambda Fusion Workflow

`lambda-fusion-workflow` 是 Lambda Fusion 框架的工作流模块，基于 [warm-flow](https://warm-flow.cn) 1.8.9 流程引擎构建，为下游应用提供**带登录态、带租户隔离**的完整单据审批能力。

本模块是**流程能力装配层**，不是流程引擎本身：流程本体（定义 / 实例 / 任务 / 历史）归 warm-flow 引擎所有，引擎表（`flow_*`）与引擎实体不做二次包装；本模块只做三件事——**身份/权限/租户桥接**（让引擎说 fusion 的语言）、**REST 出口**（定义管理 + 审批操作面）、**业务集成 SPI**（单据关联与状态回写）。

## 模块能力

### 1. 引擎桥接（`bridge/`）

让引擎接入 fusion 的身份、租户与模块协作体系。

- **核心能力**：
  - `FusionPermissionHandler`：登录身份自动翻译为引擎权限标识（`user:{username}` / `role:{roleCode}` / `org:{orgId}`），节点 `permission_flag` 按标识串精确匹配办理人（多个用 `@@` 分隔）；
  - 多租户单一机制：`flow_*` 全表含 `tenant_id`，由框架 MyBatis 租户插件统一过滤与填充，**不注册**引擎侧 `TenantHandler`，无第二套机制；
  - 设计器桥接：办理人弹窗 ← authority 用户/角色/组织远程接口，流程类别 ← dictionary `workflow_category` 字典，自定义表单路径 ← 本模块注册表；
  - `WorkflowTaskNotifier`：新待办分派经 message 模块发站内信，SSE 实时刷新通知铃铛。
- **解决的问题**：引擎如何知道「当前用户能办哪个任务」？`flow_*` 表如何不写一行租户代码就随登录态隔离？

### 2. REST 出口（`definition/` `instance/` `task/` `formpath/`）

面向前端的完整审批操作面，统一前缀 `/workflow`。

- **核心能力**：
  - 定义管理（管理端）：分页 / 详情 / 保存设计器 JSON（升版）/ 发布 / 失效 / 复制 / 导出；
  - 审批操作（登录即可，办理资格由引擎权限标识匹配约束）：通过 / 退回 / 转办 / 委派 / 加签 / 减签 / 撤回；
  - 随办理提交表单数据：`通过` 命令可携带 `formData`，经 `TaskFormHandler` SPI（form 模块实现）按节点白名单校验后与引擎办理同事务回写业务行，写入字段并入流程变量；表单能力缺席时显式拒绝；
  - 管理干预（权限码约束）：终止 / 挂起 / 激活 / 改办理人 / 任意跳转；
  - 查询面：我的待办 / 已办 / 我发起的、按业务单据查实例、审批历史时间线、流程图数据；
  - 自定义表单注册表：设计器 `form_path` 下拉的后台维护（本模块唯一自有表）。
- **解决的问题**：业务系统如何发起流程、办理人如何审批、管理端如何干预？

### 3. 业务集成 SPI（`integration/`）

业务单据与流程实例之间的关联纽带。

- **核心能力**：
  - `WorkflowBusinessProvider`：按 `bizType` 批量解析业务单据标题与详情链接，回填待办/已办/实例列表（无实现时标题为空，优雅降级不报错）；
  - `WorkflowGlobalListener` → `WorkflowFlowEvent`：引擎 start / assignment / finish / create 四钩子统一转为 Spring 领域事件，供下游 `@EventListener` 回写业务状态（`flowStatus` 分组谓词由 `WorkflowConstants` 统一提供，回写免逐值硬编码）。
- **解决的问题**：待办列表如何显示「张三的请假单（3 天）」？审批通过后业务单据状态如何自动推进？

## 架构协同与设计哲学

1. **会话同一性**（选型的决定性理由）：引擎经 `warm-flow-mybatis-plus-sb4-starter` 走业务**同一个** `SqlSessionFactory`，`flow_*` 表与业务表同处一个 MyBatis 拦截器链——框架租户插件、分页插件对引擎表零额外成本生效。对比 Flowable/Activiti 自带独立 MyBatis 环境，业务侧拦截器对引擎表全部失效，等于再造一套数据世界。
2. **单一事实来源**：业务事实（单据内容、业务状态）留业务表，流程事实（节点、任务、历史）留引擎表，`flow_instance.business_id` 只做外键式关联；不 fork 引擎、不建 `la_flow_*` 平行表、实例启动时 `def_json` 快照保证定义升版不影响在途实例。
3. **优雅降级**：authority / dictionary / message / 设计器 UI 均为 optional 依赖，缺失时对应能力自动降级（弹窗空列表 / 类别下拉为空 / 静默不通知 / 仅 REST 无设计器），不影响流程流转。

这种设计使业务接入保持极简——**业务侧只编排自己的状态机，流程能力零侵入**，接入路径见下节。

## 快速开始

### 1. 前置条件

| 前提 | 说明 |
| --- | --- |
| **登录身份** | 办理人身份取自 `AuthUtils` 的 `UserDetails`（username / roles / orgId），通常由 lambda-fusion-authority 提供；下游自建认证时需保证登录后 `UserDetails` 已正确填充。**无登录上下文时权限标识为空、待办查询返回空且不报错**——这是预期行为 |
| **租户插件** | 租户隔离依赖框架租户插件（`mybatis-plus.tenant.enabled=true`）；`flow_*` 不得加入 `tenant.ignore-tables` |
| **构建** | parent / BOM 为 `SNAPSHOT` 版本时，需先把 `lambda-cloud-parent` 安装到本地仓库（`mvn clean install`） |

### 2. 添加依赖与配置

```xml
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-workflow</artifactId>  <!-- 版本由 BOM 管理，禁写 version -->
</dependency>
```

```yaml
lambda:
  fusion:
    workflow:
      enabled: true     # 模块总开关（matchIfMissing=true）：REST 出口与桥接 Bean
warm-flow:              # 引擎自身配置（前缀归属引擎 starter，本模块不重复定义）
  enabled: true         # 引擎本体：DAO/Service 与 MapperScan
  ui: true              # 设计器：/warm-flow-ui/** 与 /warm-flow/**（生产可关闭）
  banner: false
```

- **双开关职责**：`lambda.fusion.workflow.enabled` 管业务出口，`warm-flow.enabled` 管引擎本体；彻底不用工作流时应**移除依赖**，而不是双开关齐关；
- **设计器鉴权**：静态页 `/warm-flow-ui/**` 加入 `lambda.security.sa-token.ignored` 匿名放行（iframe 文档请求无法带 header），数据接口 `/warm-flow/**` 保留登录态（设计器经 URL `Authorization` 参数携带 Bearer token）；
- **数据库表**：引擎 7 表（`flow_definition` / `flow_node` / `flow_skip` / `flow_instance` / `flow_task` / `flow_his_task` / `flow_user`）+ 本模块 1 张 `la_workflow_form_path`，由 changelog 经 Liquibase 幂等交付，**禁止手工执行建表 SQL**。

### 3. 画一个流程并发布

1. 打开设计器：`/warm-flow-ui/index.html`（使用 lambda-fusion-web 时直接走「流程设计器」菜单，包装页已处理 token 携带与暗色模式）；
2. 节点办理人按标识约定填写，如 `user:zhangsan@@role:dept_manager`（`@@` 分隔多个标识；接入 authority 时可经办理人弹窗点选用户/角色/部门，自动生成标识）；
3. **保存 ≠ 发布**：设计器保存的是草稿，需在「流程定义」列表发布后，`start` 才能按 `flow_code` 引用（否则报 `16101`）；发布后再修改产生新版本，**不影响在途实例**（启动时已做 `def_json` 快照）；
4. 记下定义的 `flow_code`，业务发起时引用。节点类型、会签比例、条件表达式等引擎语义见 [warm-flow 官方文档](https://warm-flow.cn)。

### 4. 业务接入（3 个动作）

**① 提交即启动**（业务 Service 内编排，业务表与流程实例同事务）：

```java
@Transactional(rollbackFor = Exception.class)
public void submit(String leaveId) {
    Leave leave = requireDraft(leaveId);                  // 幂等由业务状态机保证：仅草稿可提交
    leave.setStatus(LeaveStatus.APPROVING);
    updateById(leave);
    StartCommand command = new StartCommand();
    command.setFlowCode("leave_flow");                    // 第 3 步已发布定义的 flow_code
    command.setBusinessId(leave.getId());                 // 落 flow_instance.business_id 关联
    command.setBizType("leave");                          // 落 ext，供 SPI 路由标题解析
    command.setVariable(Map.of("days", leave.getDays())); // 供 skip_condition 条件分支
    instanceService.start(command);
}
```

**② 实现 `WorkflowBusinessProvider`** 回填待办/已办/实例列表的业务标题与详情链接（不实现则标题为空，不报错；完整实现见 startup 样板 `LeaveBusinessProvider`）。

**③ 监听 `WorkflowFlowEvent` 回写业务状态**（不实现则业务状态不自动变化——模块不猜业务语义）：

```java
@EventListener
public void on(WorkflowFlowEvent event) {
    if (event.type() != WorkflowConstants.Type.FINISH) {
        return;
    }
    // 状态分组谓词由模块统一承载（审批通过的实例终态为 8 而非 2，源码核实口径），
    // 业务只做分组 → 业务状态的映射，不逐值硬编码
    String status = event.flowStatus();
    if (WorkflowConstants.isPassed(status)) {            // 2/3/8：通过
        leaveService.updateFlowStatus(event.businessId(), LeaveEntity.STATUS_APPROVED);
    } else if (WorkflowConstants.isReturned(status)) {   // 9 退回 / 6 撤销：回草稿可重提
        leaveService.updateFlowStatus(event.businessId(), LeaveEntity.STATUS_DRAFT);
    } else if (WorkflowConstants.isTerminated(status)) { // 4/5/10：终止/作废/失效
        leaveService.updateFlowStatus(event.businessId(), LeaveEntity.STATUS_REJECTED);
    }                                                    // 其余为在途中间态，忽略
}
```

**幂等**：同一 `businessId` 引擎允许多实例（驳回重提是合法场景），去重由业务状态机或唯一约束保证；`GET /workflow/instance/business/{businessId}` 可辅助查询在途实例。

### 5. 前端界面

使用 lambda-fusion-web 时**前端零开发**：已内置待办 / 已办 / 我发起的 / 流程定义 / 实例管理 / 表单管理页面与「流程设计器」包装页；菜单与按钮权限码随 authority 的 data-init changelog 自动登记，部署即有。

> 完整可运行样板：[lambda-fusion-startup](../lambda-fusion-startup) 的 `com.fusion.startup.leave`（请假审批演示业务，含后端与前端页面）。

## 常见问题

| 现象 | 排查方向 |
| --- | --- |
| 发起报 `16101 流程未发布或不存在` | `flowCode` 拼写是否与定义一致；定义是否已发布（设计器保存 ≠ 发布，需在定义列表点发布） |
| 待办列表为空 | 当前是否有登录上下文、`UserDetails` 是否填充（无登录态时静默返回空，见前置条件）；节点 `permission_flag` 是否按 `user:`/`role:`/`org:` 前缀约定填写、有无拼写错误；单据是否在当前租户下 |
| 设计器打不开 / 401 | `warm-flow.ui` 是否开启；网关与 dev 代理是否转发 `/warm-flow-ui/**`、`/warm-flow/**`；数据接口需登录态——经前端包装页访问（URL 携带 `Authorization`），不要直接裸访数据接口 |
| 站内信未收到 | message 模块是否在 classpath（缺失时静默不通知）；收件人标识须为 `user:` 形态——`role:`/`org:` 聚合标识不展开（见已知限制） |
| 租户隔离未生效 | 框架租户插件是否启用（`mybatis-plus.tenant.enabled=true`）；`flow_*` 是否被误加入 `tenant.ignore-tables` |

## 错误码与权限码

- **错误码**：模块占用 **161xx** 段，由 `WorkflowErrorCode` 枚举承载（通用 / 定义 / 实例 / 任务 / 表单分组）；引擎侧异常统一翻译为 `16140 流程操作被拒绝`（消息携带引擎原因）——对外只抛模块自己的 `WorkflowBusinessException`，不向下游泄露引擎实现；
- **权限码**：管理端操作挂 `workflow_definition_*` / `workflow_instance_*` / `workflow_task_admin` / `workflow_form_path_*`（`@SaCheckPermission` 声明于各 Controller，授权在下游权限体系配置）；审批操作仅要求登录——「谁能办」由引擎 `flow_user` 权限标识匹配约束。

## 已知限制

1. 待办通知收件人只展开 `user:` 标识；`role:`/`org:` 聚合标识待 authority-api 扩充角色→用户远程接口后支持；
2. `flow_form` 未引入（引擎 1.8.9 标注「开发中」），后续版本跟进；
3. `flow_his_task` 只增不减，归档/清理策略待实例量上来后单独立项。

## 快速导航

- **API 明细**：各 Controller 以 Swagger 注解（`@Tag` / `@Operation`）自描述，运行期经 Swagger UI 查看；可运行样板见 [lambda-fusion-startup](../lambda-fusion-startup) 的 `com.fusion.startup.leave` 请假审批演示。
- **引擎语义**（节点类型、会签/票签、监听器、条件表达式）权威来源：[warm-flow 官方文档](https://warm-flow.cn)。
- **建表参考脚本**：[docs/sql/flow-init.sql](../docs/sql/flow-init.sql)。
