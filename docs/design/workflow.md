# 工作流（lambda-fusion-workflow）设计

> 状态：初稿（选型 + 桥接设计 + 引擎 DDL 引入 + 分期）
> 相关阅读：`.rule/engineering-contract.md`（§1 依赖治理、§3 自动配置、§5 多租户、§6 Liquibase、§9 Entity/Service、§10 包结构、§12 配置属性、§19 特性开关、§20 最小充分设计）、`.rule/package-structure.md`
> 引擎版本：warm-flow **1.8.9**（本地源码 `D:\IdeaProjects\warm-flow`，基线 DDL `sql/mysql/warm-flow-all.sql`）

## 1. 背景与定位

现有体系无流程能力：authority 管身份/租户/权限，message 管消息投递，但"单据如何被审批"没有承载者。

**定位**：`lambda-fusion-workflow` 是**流程能力装配层**，不是流程引擎本身——

- 流程本体（定义 / 实例 / 任务 / 历史）归 **warm-flow 引擎**所有，引擎表（`flow_*`）与引擎实体不做二次包装；
- 本模块只做三件事：**身份/权限/租户桥接**（让引擎说 fusion 的语言）、**REST 出口**（定义管理 + 审批操作面）、**业务集成 SPI**（业务单据关联、事件回调）；
- 面向下游的复用单位仍是 starter jar（宪章 §1）：业务方依赖本模块即获得"带登录态、带租户隔离"的完整审批能力。

## 2. 选型与边界

### 2.1 为什么是 warm-flow 而不是 Flowable / Activiti

| 维度 | Flowable | Activiti | warm-flow 1.8.9 |
| --- | --- | --- | --- |
| 引擎表数量 | 约 40 张 | 约 25 张 | **7 张**（`flow_*`） |
| 流程定义 | BPMN 2.0 XML | BPMN 2.0 XML | **JSON**（设计器产出，实例启动时 `def_json` 快照，定义变更不影响在途实例） |
| 引擎 SQL 会话 | 自带独立 MyBatis 配置，**绕过业务插件体系** | 同左 | MyBatis-Plus 适配（`warm-flow-mybatis-plus-sb4-starter`），**走业务同一 SqlSessionFactory** |
| 多租户 | 无内建租户字段 | 无内建租户字段 | 全表 `tenant_id`，天然落入框架租户插件 |
| Spring Boot 4（本项目 Boot 4.1.1 / Jakarta） | 7.1+ 才支持 | 8.x | 官方 `sb4-starter`（1.8.9），且内置 Jackson 3 序列化插件 |
| 设计器 | BPMN 建模，学习成本高 | 同左 | 内置 Web 设计器（经典 + 仿钉钉双模式，`warm-flow-plugin-ui`） |
| 运维面 | DDL 复杂、升级脚本多 | 同左 | 单库 7 表、按版本提供 upgrade 脚本 |

决定性理由有两点：

1. **会话同一性**：Flowable/Activiti 用自己的 MyBatis 环境跑引擎表，业务侧的租户拦截器、分页拦截器、数据权限对引擎表全部失效，等于再造一套数据世界；warm-flow 的 MP 适配层就是 MP `BaseMapper`，引擎表和业务表**同处一个拦截器链**，契约 §5.1 的租户隔离对 `flow_*` 零额外成本生效。
2. **定义形态契合**：JSON 定义 + 仿钉钉设计器贴合后台管理系统的审批场景；BPMN 的完整规范（泳道、消息事件、补偿）在本项目的实际单据审批中用不上，引入即过度设计（契约 §20.1）。

### 2.2 为什么只做桥接、不改引擎

- 流程核心（状态机、会签/票签/加签/减签、退回/拿回/撤销、条件分支、监听器）引擎已完整实现，全部经 `FlowEngine` 服务门面暴露（`DefService` / `InsService` / `TaskService` / `HisTaskService` / `UserService` / `ChartService`）；
- 集成点全部走**官方 SPI**：`PermissionHandler`（办理人权限）、`GlobalListener`（全局监听）、`DataFillHandler`（数据填充）——SPI 支持类路径配置或 Spring 容器 Bean 两种方式（`FlowEngine.initBean` 先按路径反射、取不到再 `FrameInvoker.getBean` 按类型取 Spring Bean），本模块以 Bean 方式装配；
- 不 fork、不魔改引擎表、不建平行表（契约 §20.1 单一事实来源）：业务事实（单据内容、业务状态）留在业务表，流程事实（节点、任务、历史）留在引擎表，`flow_instance.business_id` 只做外键式关联。

### 2.3 边界（本期不做）

- 不做 BPMN 导入导出互操作；
- 不自研流程设计器，复用 `warm-flow-plugin-ui`（见 §4.6）；
- **不引入 `flow_form`**：引擎 1.8.9 该表脚本标注"TODO form 开发中"，待引擎稳定后跟进（P3）；
- 不建 `la_flow_*` 业务平行走：P1 本模块**零自有表**，唯一 DB 交付物是引擎 DDL 的 Liquibase 化（§6.4）；
- 待办消息通知（对接 message 模块）为 P2，本期只埋事件（§4.3）。

## 3. 总体架构

```text
┌──────────────────────────── 下游应用 ────────────────────────────┐
│  业务表 la_xxx（单据本体与业务状态机，业务侧持有，不回搬到本模块）      │
└──────────────┬──────────────────────────────────┬────────────────┘
               │ REST：start / pass / reject ...    │ SPI：标题解析 / 状态回写
┌──────────────▼──────────────────────────────────▼────────────────┐
│                  lambda-fusion-workflow                            │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ bridge/  引擎桥接子域                                        │  │
│  │  FusionPermissionHandler  PermissionHandler → 当前用户权限标识  │  │
│  │  WorkflowGlobalListener   GlobalListener → Spring 领域事件      │  │
│  ├────────────────────────────────────────────────────────────┤  │
│  │ definition/  instance/  task/  三个业务子域                   │  │
│  │  Service 包装引擎服务（编排、校验、VO 转换、分页收敛）            │  │
│  ├────────────────────────────────────────────────────────────┤  │
│  │ integration/  业务集成 SPI                                   │  │
│  │  WorkflowBusinessProvider  bizType+businessId → 标题/链接      │  │
│  └──────────────────────────┬─────────────────────────────────┘  │
└─────────────────────────────┼────────────────────────────────────┘
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│ warm-flow 引擎（org.dromara.warm 1.8.9，非本仓库代码）               │
│ FlowEngine 门面：Def/Ins/Task/HisTask/User/Chart Service           │
│ warm-flow-mybatis-plus-sb4-starter：                               │
│   FlowAutoConfig（@MapperScan org.dromara.warm.flow.orm.mapper，   │
│   @ConditionalOnProperty warm-flow.enabled）                       │
└──────────────────────────┬───────────────────────────────────────┘
                           ▼ 同一 SqlSessionFactory（主库 master，dynamic-datasource 主路由）
┌──────────────────────────────────────────────────────────────────┐
│ lambda-cloud-starter-mybatis 拦截器链                               │
│   TenantLineInnerInterceptor（框架 TenantHandler，tenant_id 过滤）  │
│   PaginationInnerInterceptor                                       │
└──────────────────────────┬───────────────────────────────────────┘
                           ▼
        flow_* 7 张引擎表（主库共享库，tenant_id 字段级隔离，契约 §5.1）
```

| 组件 | 职责 | 来源 |
| --- | --- | --- |
| warm-flow 引擎服务 / MP 适配 / sb4 自动配置 | 流程核心、引擎表 CRUD、MapperScan | 引擎（现成，Maven 依赖） |
| `FlowAutoConfig` | 引擎 DAO/Service 注册、`warm-flow.*` 配置绑定、ID 生成器（MP 雪花） | 引擎（现成） |
| `FusionPermissionHandler` | 办理人权限标识 / 当前办理人对接 `AuthUtils` | 本仓库 |
| `WorkflowGlobalListener` | 引擎四钩子（start/assignment/finish/create）→ Spring 事件 | 本仓库 |
| `Definition/Instance/Task Service` + Controller | REST 出口与操作编排 | 本仓库 |
| `WorkflowBusinessProvider` SPI | 业务单据标题/链接解析（下游实现） | 本仓库（接口） |

## 4. 关键设计

### 4.1 身份与办理人权限桥（PermissionHandler）

引擎的权限模型是"**权限标识串精确匹配**"：节点上配置 `permission_flag`（`@@` 分隔），任务创建时按标识落 `flow_user.processed_by`，办理与待办查询时把"当前用户的权限标识集合"与 `processed_by` 做等值匹配。因此桥接的全部工作就是把 fusion 身份翻译成标识串：

- **`FusionPermissionHandler implements PermissionHandler`**（`WorkflowConfigure` 注册为 Bean，引擎 `initBean` 按类型从容器获取）：

  | SPI 方法 | 实现 |
  | --- | --- |
  | `permissions()` | 返回当前用户标识集合：`user:{username}` + 每个角色的 `role:{roleCode}` + `org:{orgId}`（来自 `AuthUtils.getUser()` 的 `UserDetails`） |
  | `getHandler()` | 返回 `AuthUtils.getSafeUsername()`（username；无登录上下文时经 `OperatorUtils.getSafeOperator()` 降级，不抛异常） |
  | `convertPermissions()` | 默认透传：角色作为聚合标识直接匹配，不做角色→用户展开（需要展开时下游覆盖该 Bean 即可，`@Primary` 优先） |

- **标识约定**（写入 `WorkflowConstants`，设计器节点权限、API 文档统一引用）：`user:{username}` / `role:{roleCode}` / `org:{orgId}`。为什么用显式前缀而不是引擎示例的裸用户 ID：可读、可区分类型、避免 username 与 roleCode 撞名。
- **为什么 handler 用 username 而非数字 ID**：authority 以 username 为登录与远程查询口径（`RemoteUserService.getByUsername`），`flow_his_task.approver` 存 username 可直接展示与追溯。
- 扩展纪律：全容器只应存在一个 `PermissionHandler` Bean；下游覆盖用 `@Primary`，不新增平行 SPI。

### 4.2 租户隔离：复用框架租户插件，单一机制（契约 §5.1）

对源码核查后的结论（不是推测）：

- warm-flow 的 MP 适配层 DAO 全部经 MP `BaseMapper` 走业务 `SqlSessionFactory`；**引擎自身不做租户过滤**——`TenantHandler` SPI 在 MP 路径没有任何消费点，官方租户方案本身就是"借 MyBatis-Plus 租户插件"。
- 因此本设计**不注册** warm-flow `TenantHandler`（`tenant-handler-path` 不配），租户事实只由框架 `TenantLineInnerInterceptor`（`lambda-cloud-starter-mybatis`）一个机制表达：
  - `flow_*` 7 张表均含 `tenant_id varchar(40)`，且**不加入** `mybatis-plus.tenant.ignore-tables` → 常规 CRUD 查询自动追加 `tenant_id = 当前租户`，INSERT 由租户插件自动补列填充（MP 对 INSERT 的租户列补填行为）；
  - 无租户上下文时按框架 `MissingTenantStrategy`（默认 `SkipOnMissingTenantStrategy`）全表跳过过滤——与业务表语义一致，平台监管跨租户查询同路径生效；
  - 跨租户管理流程（如平台查看全部租户实例）属 §5.1 例外：必须经权限入口、显式说明目标租户来源，不得把 `@InterceptorIgnore` 当作常规修复手段。
- **后台线程上下文**：引擎监听器在调用线程同步执行，HTTP 请求内发起的审批天然携带租户上下文；若下游在事件里转异步线程处理（如发消息），按契约 §5.1 从领域对象恢复 `TenantContextHolder`（事件体携带 `tenantId`，取自 `instance.getTenantId()`），任务结束清理。

### 4.3 引擎事件桥（GlobalListener → Spring 事件）

- `WorkflowGlobalListener implements GlobalListener`（容器唯一 Bean），四个钩子 `start / assignment / finish / create` 统一转为 Spring 领域事件 `WorkflowFlowEvent`（record：钩子类型、instanceId、businessId、taskId、flowStatus、tenantId、时间戳）发布；
- 模块内用 `@EventListener` 消费；下游应用监听同一事件做业务回写（如单据状态推进）——比节点监听器（listener_path 配置在定义 JSON 里）更可测试、可编排；
- 节点级/定义级 `listener_path` 能力保留给业务方按引擎原生方式使用，本模块不封装（避免双出口，§20.1）；
- P2 起 message 模块（optional 依赖 `lambda-fusion-message-api`，缺失时优雅降级）消费 `assignment` 事件发待办通知，复用 ai 模块 `ScheduledTaskNotifier` 的先例。

### 4.4 业务单据关联与回写

- **关联**：`flow_instance.business_id varchar(40)` 挂业务表主键（框架业务主键为 `varchar(32)` 雪花串，长度可容纳）。启动时由调用方传入，引擎不做唯一约束——同一 `businessId` 允许多实例（驳回重提、不同流程阶段），**幂等由业务侧保证**（业务表状态机校验或唯一约束），模块提供 `InstanceService.listByBusiness(businessId)` 辅助查重；
- **回写**：业务状态推进由下游监听 `WorkflowFlowEvent`（`finish` 钩子时 `instance.getFlowStatus()` 已终态）自行更新业务表，模块提供回写范式文档与示例，不做自动回写框架（各业务状态机语义不同，自动映射必然过度设计）；
- **列表展示**：待办/已办/实例列表需要业务标题，引擎表无此事实。`WorkflowBusinessProvider` SPI：

  ```java
  public interface WorkflowBusinessProvider {
      /** 支持的业务类型标识，如 leave、expense；与启动入参 bizType 对应 */
      Set<String> supportedTypes();

      /** 批量解析业务单据展示信息（标题、详情链接），供列表拼接 */
      Map<String, BizInfo> resolve(String bizType, Collection<String> businessIds);
  }
  ```

  经 `ObjectProvider<WorkflowBusinessProvider>` 收集全部实现；无实现时列表照常返回流程字段，标题为空（优雅降级，§4.1）。

- **变量与条件分支**：启动/审批传入的 `variables`（Map）由引擎落 `flow_instance.variable`；`flow_skip.skip_condition` 用引擎表达式（`${var}` 默认策略与 SpEL 已随 starter 装配）求值，网关出边条件在定义 JSON 中配置。

### 4.5 审批操作面（REST → 引擎方法映射）

| REST | 引擎入口 | 说明 |
| --- | --- | --- |
| `POST /workflow/instance/start` | `InsService.start(businessId, FlowParams)` | 按 `flowCode` 取已发布定义启动；FlowParams 携带 variables |
| `POST /workflow/task/{id}/pass` | `TaskService.pass(taskId, message, variable)` | 通过；会签/票签按节点 `node_ratio` 聚合 |
| `POST /workflow/task/{id}/reject` | `TaskService.reject(...)` | 驳回（按 REJECT 出边） |
| `POST /workflow/task/{id}/back` | `TaskService.rejectLast(...)` | 退回到上一节点 |
| `POST /workflow/task/{id}/transfer` | `TaskService.transfer(taskId, FlowParams.addHandlers)` | 转办 |
| `POST /workflow/task/{id}/depute` | `TaskService.depute(...)` | 委派 |
| `POST /workflow/task/{id}/signature` | `addSignature / reductionSignature` | 加签 / 减签 |
| `PUT /workflow/task/{id}/handler` | `updateHandler(...)` | 修改当前处理人（管理干预） |
| `PUT /workflow/instance/{id}/revoke` | `TaskService.revoke(...)` | 发起人撤销 |
| `PUT /workflow/instance/{id}/terminate` | `terminationByInsId(...)` | 终止（权限点约束） |
| `PUT /workflow/instance/{id}/pending` | `pendingByInsId(...)` | 挂起 / 激活（`active`） |
| `POST /workflow/task/{id}/skip` | `skipAtWill / passAtWill` | 任意跳转（管理干预，强权限点） |

操作前统一在 Service 层做办理人校验（`task` 存在、`flow_user` 含当前用户标识、实例非终态），错误走 `WorkflowErrorCode`（§10）；Controller 只编排 Service（契约 §9.3）。

### 4.6 流程定义管理与设计器

- **定义管理**：`DefinitionService` 包装 `DefService`：分页、详情（`getAllDataDefinition` 定义+节点+跳转）、保存（`saveDef`/`importJson`，设计器 JSON）、发布/失效（`publish`/`unPublish`，版本语义由引擎保证）、复制、导入导出（`exportJson`）；
- **设计器**：复用 `warm-flow-plugin-ui`（`warm-flow-plugin-ui-sb-web`，optional 依赖）：
  - 开关 `@ConditionalOnProperty(warm-flow.ui)`（引擎自带，默认 true），静态资源 `/warm-flow-ui/**`，数据接口 `/warm-flow/**` 与业务系统共享 `Authorization` 请求头（引擎 `tokenName` 默认 `Authorization`，与 Sa-Token 请求头一致）；
  - **鉴权衔接待落地验证**：starter 的 Sa 拦截默认拦截所有路径，需将 `/warm-flow-ui/**`（页面与静态资源）纳入 `lambda.security.sa-token.ignored` 匿名放行、`/warm-flow/**`（数据接口）保留登录态校验——具体收敛方式在 P1 落地时与 `lambda-cloud-starter-security` 对齐后定稿；
  - 设计器产出的定义 JSON 中节点 `permission_flag` 按 §4.1 标识约定填写。

### 4.7 数据填充与审计字段

- `DataFillHandler` 用引擎默认实现（匿名内部类），不重复实现（契约 §2.2）：ID 经 `MybatisPlusIdGen` 雪花、`create_time/update_time` 自动填充、`create_by/update_by` 取自 `PermissionHandler.getHandler()`（即 §4.1 的 username 桥）；
- 引擎表审计口径（`create_by varchar(64)`、datetime、`del_flag char(1)` 逻辑删）与框架 `BaseEntity`（`CREATED_BY` 等大写、LocalDateTime）**不统一**：引擎表是引擎内部事实，不为风格一致建平行 Entity/Converter（契约 §20.1、现状优先）；
- 本模块 VO 直接按引擎字段口径暴露（`DefinitionView`/`InstanceView`/`TaskView`，命名遵循 §10.2 视图对象无后缀），转换手写为静态装配（引擎实体非本模块所有，不入 `ConverterResolver` 的 MapStruct 体系——注册跨 jar 的 Mapper 反而复杂，§8.1 的自动转换约束针对本模块自有实体，本期无自有实体）。

## 5. 包结构

遵循「模块根级共享 + 子域内分层」（`.rule/package-structure.md`），基础包 `com.lambda.fusion.workflow`：

```text
com.lambda.fusion.workflow
├── WorkflowConstants                     模块常量：权限标识前缀、事件名、流程状态引用
├── exception/
│   ├── WorkflowBusinessException         模块级业务异常（extends 框架 BusinessException）
│   └── WorkflowErrorCode                 错误码枚举（16xxx 段，见 §10）
├── bridge/                               引擎桥接子域（横切能力归属子域，不建 commons/，§10.3）
│   ├── FusionPermissionHandler           PermissionHandler 实现（对接 AuthUtils）
│   ├── WorkflowGlobalListener            GlobalListener 实现 → 发布 WorkflowFlowEvent
│   └── WorkflowFlowEvent                 领域事件 record（含 tenantId，供异步消费恢复上下文）
├── definition/                           流程定义子域
│   ├── controller/DefinitionController   定义分页/详情/保存/发布/失效/复制/导入导出
│   ├── service/{DefinitionService, impl/DefinitionServiceImpl}   包装 DefService/NodeService/SkipService
│   └── model/                            DefinitionView/DefinitionPage/DefinitionSaveCommand（视图与命令，无业务逻辑）
├── instance/                             流程实例子域
│   ├── controller/InstanceController     发起/我的/详情/历史/流程图/撤销/终止/挂起
│   ├── service/{InstanceService, impl/InstanceServiceImpl}       包装 InsService + ChartService
│   └── model/                            InstanceView/InstancePage/StartCommand/HistoryView
├── task/                                 任务子域
│   ├── controller/TaskController         待办/已办/通过/驳回/退回/转办/委派/加签减签/改处理人/跳转
│   ├── service/{TaskService, impl/TaskServiceImpl}               包装 TaskService/HisTaskService/UserService
│   └── model/                            TaskView/TaskTodoPage/TaskDonePage/ApproveCommand/RejectCommand/TransferCommand
└── integration/                          业务集成 SPI
    ├── WorkflowBusinessProvider          业务单据展示信息解析 SPI
    └── BizInfo                           标题/链接 record

com.lambda.fusion.autoconfig
└── WorkflowAutoConfiguration             @AutoConfiguration 入口，注册 AutoConfiguration.imports（末尾无空行）
com.lambda.fusion.workflow
├── WorkflowConfigure                     @Configuration：SPI Bean 装配、UI 条件装配、引擎 WarmFlow 定制
└── WorkflowProperties                    @ConfigurationProperties(prefix = "lambda.fusion.workflow")
```

要点：

- 子域内 `model/` 只放 VO/Query/Command，禁业务逻辑（契约 §9.3）；分页入参继承 `PageQuery<T>`、出参 `PageView`（契约 §8.2）；
- `bridge/`、`integration/` 为按需目录中的横切归属，不建空目录、不建顶层 `vo/`/`entity/` 集中包（契约 §10.3）；
- 待办/已办分页不走 `AbstractCrudService`（引擎表无本模块 Entity），Service 直接注入引擎 `FlowTaskMapper`/`FlowUserMapper`/`FlowHisTaskMapper`（引擎 starter 注册的 MP Mapper Bean）拼 `LambdaQueryWrapper` 并经 `PageView` 手工装配——这是**查询编排**不是**新的 CRUD 抽象**，不违反契约 §2.2/§9.2（本模块无自有单表 CRUD）。

## 6. 自动配置与依赖

### 6.1 三件套（契约 §3.1）

| 件 | 内容 |
| --- | --- |
| `WorkflowAutoConfiguration` | `com.lambda.fusion.autoconfig`；`@ConditionalOnProperty(prefix="lambda.fusion.workflow", name="enabled", matchIfMissing=true)`；`@Import(WorkflowConfigure.class)` |
| `WorkflowConfigure` | `@ComponentScan` 装配 `FusionPermissionHandler`/`WorkflowGlobalListener` 等 Bean（引擎 `initBean` 按类型从容器取）与 `BizInfoResolver`；`ObjectProvider` 收集 `WorkflowBusinessProvider` SPI。UI 由 `warm-flow-plugin-ui-sb-web` 自带的 `@ConditionalOnProperty("warm-flow.ui")` 自动配置装配，本类不做 UI 条件装配 |
| `WorkflowProperties` | 前缀 `lambda.fusion.workflow`（§9） |

### 6.2 pom 依赖（初稿）

```xml
<dependencies>
    <dependency>
        <groupId>com.lambda.cloud</groupId>
        <artifactId>lambda-fusion-core</artifactId>              <!-- AuthUtils/PageQuery/PageView -->
        <version>${project.version}</version>
    </dependency>
    <dependency>
        <groupId>com.lambda.cloud</groupId>
        <artifactId>lambda-cloud-starter-security</artifactId>   <!-- @SaCheckPermission -->
    </dependency>
    <dependency>
        <groupId>com.lambda.cloud</groupId>
        <artifactId>lambda-cloud-starter-mybatis</artifactId>    <!-- 租户/分页拦截器（flow_* 表需要） -->
    </dependency>
    <dependency>
        <groupId>com.lambda.cloud</groupId>
        <artifactId>lambda-cloud-starter-datasource</artifactId> <!-- dynamic-datasource 主库路由 -->
    </dependency>
    <dependency>
        <groupId>com.lambda.cloud</groupId>
        <artifactId>lambda-cloud-starter-liquibase</artifactId>  <!-- changelog 聚合 -->
    </dependency>
    <dependency>
        <groupId>com.lambda.cloud</groupId>
        <artifactId>lambda-cloud-starter-logger</artifactId>     <!-- @OperationLog -->
    </dependency>
    <dependency>
        <groupId>org.dromara.warm</groupId>
        <artifactId>warm-flow-mybatis-plus-sb4-starter</artifactId>  <!-- 引擎：core+MP适配+sb装配+jackson3 -->
    </dependency>
    <dependency>
        <groupId>org.dromara.warm</groupId>
        <artifactId>warm-flow-plugin-ui-sb-web</artifactId>
        <optional>true</optional>                               <!-- 设计器，缺失时无 UI 仅 REST -->
    </dependency>
</dependencies>
```

- **版本治理（契约 §1.1/§1.2，跨仓库前置，P0 已完成）**：`org.dromara.warm` 版本统一由 BOM 管理，禁止在模块 POM 硬编码 version。`lambda-cloud-parent`（姊妹仓库）已登记 `warm-flow.version=1.8.9` 属性并把 `warm-flow-mybatis-plus-sb4-starter`、`warm-flow-plugin-ui-sb-web` 纳入其 `dependencyManagement`；本仓库 BOM 随之自然生效；
- 引擎自带 MyBatis-Plus 版本（3.5.12）会被本项目导入的 `mybatis-plus-bom:3.5.17` 统一覆盖，无需干预；`sb4-starter` 已排除旧版 MP 构件并改用 `mybatis-plus-spring-boot4-starter`，与框架既有 MP 装配兼容（落地时以 `mvn dependency:tree` 验证一次）；
- 不引入 `lambda-fusion-authority-api`：P1 权限标识直接用登录态 `UserDetails`，角色→用户展开（如需要）由下游覆盖 `convertPermissions` 自行接入，模块不预置远程依赖（§20.1 不预埋）。

### 6.3 与引擎自动配置的共存（双开关职责）

| 开关 | 归属 | 默认 | 管辖 |
| --- | --- | --- | --- |
| `lambda.fusion.workflow.enabled` | 本模块 | true | 业务能力出口：REST、桥接 Bean、事件、SPI 装配 |
| `warm-flow.enabled` | 引擎 starter | true | 引擎本体：DAO/Service Bean、MapperScan |

- 依赖即装配引擎（DAO 与表是模块的地基）；关模块开关 = 保留引擎、关闭对外能力，语义单一（契约 §19.1）；
- 下游彻底不用工作流时应移除依赖而不是双开关齐关——文档明确写出，避免"双实现/双开关"形式完整主义（§20.1）。

### 6.4 Liquibase：引擎 DDL 的 Liquibase 化（契约 §6）

- 新增 `lambda-fusion-workflow/src/main/resources/META-INF/db/changelogs/lambda-workflow-changelog.xml`（命名严格匹配 `lambda-\w*-changelog.xml`）；
- **基线内容**：`warm-flow-all.sql` **1.8.9** 的 7 张表（`flow_definition` / `flow_node` / `flow_skip` / `flow_instance` / `flow_task` / `flow_his_task` / `flow_user`）逐表一个 changeSet，列名/类型/默认值**逐字取自官方脚本**（含小写表名列名），每 changeSet 带 `<preConditions onFail="MARK_RAN"><not><tableExists/></not></preConditions>`（与 authority 现有写法一致）；不引入 `flow_form`（§2.3）；
- **刻意与框架表风格不同**：小写命名跟随引擎官方脚本，换取引擎升级时与官方 `v1-upgrade` 脚本的零成本 diff；引擎表不加 `la_` 前缀、不改 `create_by` 等审计口径（§4.7）；
- 索引保持官方脚本原样（仅 `flow_user` 的 `user_processed_type` / `user_associated`）；不额外加业务索引——待办查询条件（`processed_by` + `type` + 租户插件的 `tenant_id`）已被官方索引覆盖；
- 同步维护 `docs/sql/flow_-init.sql` 参考脚本（契约 §6.2 SHOULD）；
- 引擎升级流程（沉淀为规则）：升级 `warm-flow.version` 时，把官方 `v1-upgrade/warm-flow_x.x.x.sql` 逐条翻译为追加 changeSet（只增不改，契约 §6.1）。

## 7. 引擎表清单（1.8.9 基线）

| 表 | 说明 | 关键列 |
| --- | --- | --- |
| `flow_definition` | 流程定义（元信息） | `flow_code` + `version` 业务标识；`is_publish`（0 未发布/1 已发布/9 失效）；`model_value`（CLASSICS/MIMIC）；`activity_status`（挂起/激活）；`listener_type/path`；`ext` |
| `flow_node` | 节点 | `definition_id`；`node_type`（0 开始/1 中间/2 结束/3 互斥网关/4 并行网关）；`node_code`；`permission_flag`（`@@` 分隔）；`node_ratio`（会签/票签比例）；`coordinate`；`any_node_skip` |
| `flow_skip` | 节点间出边 | `now_node_code → next_node_code`；`skip_type`（PASS/REJECT）；`skip_condition`（表达式） |
| `flow_instance` | 流程实例 | `business_id`（业务单据关联）；`flow_status`（0 待提交/1 审批中/2 通过/4 终止/5 作废/6 撤销/8 完成/9 退回/10 失效/11 拿回）；`variable`（JSON）；`def_json`（定义快照） |
| `flow_task` | 待办任务 | `instance_id`；`node_code`；`flow_status` |
| `flow_his_task` | 历史任务 | `task_id`；`approver`；`cooperate_type`（1 审批/2 转办/3 委派/4 会签/5 票签/6 加签/7 减签）；`collaborator`；`skip_type`；`message`（审批意见）；`variable`/`ext` |
| `flow_user` | 办理人权限 | `type`（1 审批人/2 转办人/3 委托人）；`processed_by`（权限标识）；`associated`（任务 ID） |

公共列（全部引擎表）：`id bigint` 主键（MP 雪花）、`create_time`/`update_time` datetime、`create_by`/`update_by` varchar(64)、`del_flag char(1)` 逻辑删（MP `@TableLogic`）、`tenant_id varchar(40)`。

## 8. API 定稿（P1 已实现，权限码为实际码值）

### 8.1 用户侧（登录即可，办理资格由引擎 flow_user 权限标识匹配约束）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/workflow/task/todo` | 我的待办分页（两段式查询：`flow_user.processed_by ∈ 当前用户权限标识` → `flow_task`，bizInfo 回填标题/链接） |
| GET | `/workflow/task/done` | 我的已办分页（`flow_his_task.approver = 当前用户`） |
| GET | `/workflow/instance/mine` | 我发起的分页（`create_by = 当前用户`） |
| GET | `/workflow/instance/business/{businessId}` | 按业务单据查实例列表（业务详情页内嵌时间线/流程图用） |
| GET | `/workflow/instance/{id}` | 实例详情 |
| GET | `/workflow/instance/{id}/history` | 审批历史（`flow_his_task`，每条含 businessId/bizType 供展示回填） |
| GET | `/workflow/instance/{id}/chart` | 流程图数据（`ChartView = flow(FlowCombine) + instance(InstanceView)`） |
| POST | `/workflow/instance/start` | 发起 `{flowCode, businessId, bizType, variable}`（`workflow_instance_add`） |
| POST | `/workflow/task/{id}/pass` | 通过 `{message, variable}` |
| POST | `/workflow/task/{id}/reject` | 退回上一节点 `{message}` |
| POST | `/workflow/task/{id}/transfer` | 转办 `{targetUsername, message}` |
| POST | `/workflow/task/{id}/depute` | 委派 `{targetUsername, message}` |
| POST | `/workflow/task/{id}/signature/add` | 加签 `{handlers, message}` |
| POST | `/workflow/task/{id}/signature/reduction` | 减签 `{handlers, message}` |
| PUT | `/workflow/instance/{id}/revoke` | 撤销（回到发起节点，`?message=`；引擎校验发起人身份） |

> P1 收敛说明：引擎无独立 `back`（拿回）任务操作——撤销即 `revoke`（实例侧）；退回即 `reject`。加签/减签按路径区分而非 body 内 type。

### 8.2 管理端（实际权限码，snake_case）

| 方法 | 路径 | 权限码 |
| --- | --- | --- |
| GET | `/workflow/definition`、`/{id}` | `workflow_definition_list` |
| POST | `/workflow/definition` | `workflow_definition_add` |
| POST | `/workflow/definition/{id}/copy` | `workflow_definition_add` |
| PUT | `/workflow/definition/{id}/publish`、`/unpublish` | `workflow_definition_publish` |
| DELETE | `/workflow/definition/{id}` | `workflow_definition_delete` |
| GET | `/workflow/definition/{id}/export` | `workflow_definition_export` |
| GET | `/workflow/instance/page` | `workflow_instance_list` |
| PUT | `/workflow/instance/{id}/terminate`、`/{id}/active`、`/{id}/unactive` | `workflow_instance_admin` |
| PUT | `/workflow/task/{id}/handler` | `workflow_task_admin` |
| POST | `/workflow/task/{id}/skip` | `workflow_task_admin` |

- Controller 只编排 Service、分页收敛 Service（契约 §8.3/§9.3）；权限点经 `@SaCheckPermission` 声明，具体授权在下游应用的权限体系中配置；前端 `v-access:code`、后端注解、权限登记三处一致（§10.4⑤）；
- 设计器入口：前端直接访问 `/warm-flow-ui/index.html`（UI starter 资源），或由前端路由代理（细节前端定）。

## 9. 配置项定稿

```yaml
lambda:
  fusion:
    workflow:
      enabled: true                    # 模块总开关（matchIfMissing=true）
warm-flow:
  ui: ${WORKFLOW_UI_ENABLED:true}      # 设计器静态资源（/warm-flow-ui/**）与数据接口（/warm-flow/**）开关
  enabled: true                        # 引擎开关（引擎自带，默认 true）
  banner: false                        # 关闭引擎启动 banner
  # key-type 缺省：MP 雪花（MybatisPlusIdGen），不配置
  # tenant-handler-path 不配：租户由框架 MP 租户插件单一表达（§4.2）
  # permission-handler-path 不配：FusionPermissionHandler 以 Bean 方式被引擎获取（§4.1）
```

- `WorkflowProperties` 字段：仅 `enabled`（本期一项，不为未来需求预埋字段，§20.1）；
- `warm-flow.*` 键归属引擎 starter（`WarmFlowProperties`，前缀 `warm-flow`），本模块只在文档中约定覆盖值，不在 `lambda.fusion.*` 内重新定义（契约 §3.2）；
- **设计器开关收敛定稿（P1）**：UI 开关单一表达为引擎侧 `warm-flow.ui`，模块侧不再重复 `ui-enabled` 字段——双开关漂移风险归零，下游关闭设计器直接配 `warm-flow.ui=false`（startup 已按此落地）。

## 10. 下游接入指南（P1 能力就绪后对下游生效）

> 视角切换：本仓库是能力提供方，下游应用是集成方（宪章 §1）。以下全部动作发生在**下游应用代码库**中。

### 10.1 后端：依赖与配置（3 步）

```xml
<!-- ① 引入 starter（版本由 BOM 管理，禁写 version，契约 §1.2） -->
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-workflow</artifactId>
</dependency>
```

```yaml
# ② application.yml：开关 + 设计器（引擎表由 starter 的 Liquibase changelog 自动建，
#    下游不得手工执行 flow_* 建表 SQL，契约 §6.1）
lambda:
  fusion:
    workflow:
      enabled: true
warm-flow:
  banner: false
  ui: true
```

```yaml
# ③ 设计器访问（P1 口径）：不放行匿名路径——设计器页面与数据接口均需登录态；
#    iframe 内 token 传递（Authorization header 无法自动携带）的鉴权对齐为 P2 事项（§12），
#    生产环境可用 warm-flow.ui=false 关闭设计器入口
# lambda.security.sa-token.ignored 默认不加 warm-flow 路径
```

管理端权限点（`workflow:definition:*`、`workflow:task:admin` 等）在下游权限体系中给角色授权；普通办理人**无需授权**——登录即可办理。

### 10.2 后端：接入一个审批业务（5 步，以请假为例）

```text
① 业务表 la_leave（id/tenant_id/applicant/days/reason/status/审计四件套）——业务方自持，引擎不碰
② 提交时启动流程      ③ 实现 WorkflowBusinessProvider（待办列表显示标题）
④ 监听事件回写状态    ⑤ 幂等由业务状态机保证
```

**② 提交即启动**（业务 Service 内编排，Controller 只调业务 Service）：

```java
@Transactional(rollbackFor = Exception.class)
public void submit(String leaveId) {
    Leave leave = requireDraft(leaveId);                       // ⑤ 状态机校验：仅草稿可提交（幂等入口）
    leave.setStatus(LeaveStatus.APPROVING);
    updateById(leave);
    instanceService.start(StartCommand.builder()
            .flowCode("leave_flow")                             // 对应设计器定义的 flow_code
            .businessId(leave.getId())                          // flow_instance.business_id 关联
            .bizType("leave")                                   // 供 WorkflowBusinessProvider 路由
            .variables(Map.of("days", leave.getDays()))         // 供 skip_condition 条件分支
            .build());
}
```

**③ 业务标题解析**（没有它待办列表标题为空，不报错——优雅降级）：

```java
@Component
public class LeaveBusinessProvider implements WorkflowBusinessProvider {
    @Override
    public Set<String> supportedTypes() { return Set.of("leave"); }

    @Override
    public Map<String, BizInfo> resolve(String bizType, Collection<String> businessIds) {
        return listByIds(businessIds).stream().collect(Collectors.toMap(
                Leave::getId,
                l -> new BizInfo(l.getApplicant() + " 的请假单（" + l.getDays() + " 天）", "/leave/detail/" + l.getId())));
    }
}
```

**④ 业务状态回写**（监听模块事件，不实现则业务状态不自动变化——模块不猜业务语义）：

```java
@Component
public class LeaveFlowStatusUpdater {
    @EventListener
    public void on(WorkflowFlowEvent event) {
        if (!"leave".equals(event.bizType()) || event.type() != WorkflowFlowEvent.Type.FINISH) {
            return;
        }
        // flowStatus：2 通过 / 9 驳回 / 4 终止 / 6 撤销（见 §7 状态枚举）
        leaveService.updateFlowStatus(event.businessId(), mapStatus(event.flowStatus()));
    }
}
```

**⑤ 幂等**：同一 `businessId` 引擎允许多实例（驳回重提是合法场景），**去重判断是业务语义**——用业务表状态机（草稿才能提交）或唯一约束保证；`InstanceService.listByBusiness(businessId)` 可辅助查询进行中实例。

### 10.3 后端：明确不做什么

- 不建引擎平行表、不直连 `flow_*` 写数据（查询经本模块 REST 或 SPI）；
- 不写任何租户代码——`flow_*` 与业务表同一拦截器链，租户隔离随登录态自动生效（§4.2）；
- 不解析权限标识——办理人匹配由 `FusionPermissionHandler` 完成，节点权限只按 §4.1 约定填写。

### 10.4 前端：lambda-fusion-web 落地

前端仓库（vben-admin 5.x monorepo + TDesign，`apps/web-tdesign` 为宿主）探查结论与落点设计：

**结构性事实**（决定落点，勿违反）：

- 业务页面全部在 `packages/system-ui/src/views/{system,message,ai}/`，app 自身只有壳（`_core`/`dashboard`）；`page-map.ts` 以 glob 收录 `system-ui/views/**/*.vue`，与后端菜单 component 字符串匹配（`apps/web-tdesign/src/router/access.ts`）；
- 路由**完全后端菜单驱动**（`GET /auth/menus` → `generateAccessible`，`accessMode=mixed`），前端不注册静态业务路由；
- API 模块在 `packages/system-ui/src/api/{system,ai}/`，经 `setSystemRequestClient(requestClient)` 注入宿主 axios 实例（`#/api/request`，dev 代理 `/api → 127.0.0.1:20005`）；响应约定 HTTP 200 直接返回 data；
- 页面范式：`Page`（`@vben/common-ui`）+ `useVbenVxeGrid`（`proxyConfig.ajax.query` 返回 `{ items, total }`），配套 `components/<domain>-<role>-dialog.vue`；类型与 API 函数同文件（`message.ts` 的 namespace 先例）；
- 权限：`v-access:code="'user_add'"` 指令 + 路由 meta，权限码 snake_case 风格（`user_add`/`dict_edit` 等），无 `v-permission`；
- **全仓当前无任何审批/工作流页面**——greenfield；`@vue-flow/core`（及 background/controls/minimap）已在 `@vben/system-ui` 依赖（catalog）——流程图自绘的依赖基础已就位。

**① 页面与组件落点**（新增 `packages/system-ui/src/views/workflow/`，glob 自动收录，无需改 page-map）：

```text
src/views/workflow/
├── todo/index.vue                 待办列表（vxe-grid；标题/链接列取列表项 bizInfo）
├── done/index.vue                 已办列表
├── mine/index.vue                 我发起的
├── definition/index.vue           流程定义管理（管理端）
├── instance/index.vue             实例管理（管理端）
└── components/
    ├── approval-drawer.vue        审批抽屉：单据信息（链接跳业务详情）+ 审批意见 + 操作按钮组
    ├── instance-detail-drawer.vue 实例详情：基本信息 + 审批历史时间线（history 接口）
    ├── flow-chart.vue             流程图（@vue-flow 渲染 chart 接口，按状态着色，只读）
    └── start-process-dialog.vue   发起确认（选已发布流程 + 变量录入，供业务页嵌入）
```

跨页面组件复用有先例（tenant 页复用 role 的 `role-permission-dialog.vue`）：业务方单据详情页如需内嵌审批操作区，直接 import `workflow/components/approval-drawer.vue`。

**② API 模块**：`packages/system-ui/src/api/system/workflow.ts`，对齐 `message.ts`/`application.ts` 先例——同文件导出 `namespace WorkflowApi` 类型与 `getTodoTasksApi`/`getDoneTasksApi`/`getMyInstancesApi`/`getInstanceChartApi`/`startInstanceApi`/`passTaskApi`/`rejectTaskApi`/`transferTaskApi` 等函数；在 `src/index.ts` 追加 `export * from './api/system/workflow'`。分页请求/响应沿用全仓既有约定（application 页面先例）：请求参数名 **`page`/`size`**（对应 `PageQuery`），vxe-grid `proxyConfig.ajax.query` 内做 `items: res.records, total: Number(res.total)` 映射——`PageView` 无需额外适配层。

**③ 菜单登记（纯后端配置，零前端路由代码）**：在权限管理的菜单/资源中登记——

| 菜单 | path | component | 说明 |
| --- | --- | --- | --- |
| 待办 / 已办 / 我发起 | `/workflow/todo` 等 | 留空自动推导 `workflow/todo/index` | 普通角色可见 |
| 流程定义 / 实例管理 | `/workflow/definition` 等 | 显式同上 | 按钮级权限码控制 |
| **流程设计器** | — | 菜单类型=**内嵌页面**（`EMBEDDED_PAGE`），url=`/warm-flow-ui/index.html` | authority `MenuRouteAssembler` 自动产出 `IFrameView` + `meta.iframeSrc`，**设计器零前端开发嵌入** |

部署注意（P1 状态）：后端已落地引擎开关 `warm-flow.ui`（startup 经 `WORKFLOW_UI_ENABLED` 控制）；设计器页面与数据接口**均需登录态**（sa-token 白名单未放行 warm-flow 路径）。网关/nginx（及 dev 的 vite proxy）需把 `/warm-flow-ui/**`、`/warm-flow/**` 转发到后端（当前 proxy 只配了 `/api`）；iframe 内 `Authorization` header 无法自动携带的鉴权对齐为 P2 事项（§12）——联调前设计器页面暂以 dev 直开带 token 方式验证。

**④ 流程图渲染（P1 定稿）**：`flow-chart.vue` 基于 `@vue-flow/*` 渲染 `GET /workflow/instance/{id}/chart`（P1 实际响应：`{ flow: FlowCombine, instance: InstanceView }`），只读展示（Controls + Background + MiniMap 用官方包）。字段映射（引擎 `FlowCombine` 结构已从源码核实）：

| chart 响应 | vue-flow | 说明 |
| --- | --- | --- |
| `flow.allNodes[].nodeCode` | `node.id` | 节点唯一键 |
| `flow.allNodes[].nodeName` | `node.data.label` | 展示名 |
| `flow.allNodes[].nodeType` | 自定义节点形状 | 0 开始 / 1 中间 / 2 结束 / 3 互斥网关 / 4 并行网关（`permissionFlag` 非空即审批节点） |
| `flow.allNodes[].coordinate` | `node.position` | 引擎存 `"x,y"` 逗号分隔像素串，`split(',')` 取整；与设计器保存格式 P2 联调核对一次 |
| `flow.allSkips[].nowNodeCode → nextNodeCode` | `edge.source → edge.target` | 出边 |
| `flow.allSkips[].skipName / skipCondition` | `edge.label` | 条件分支可标注 `skipCondition` |
| `flow.allSkips[].skipType === 'REJECT'` | 红色边样式 | 退回线 |
| `instance.nodeCode` | 高亮 = 「当前节点」 | 实例当前停留节点 |
| `history[].targetNodeCode`（另调 history 接口） | 高亮 = 「已办节点」 | 三色着色：已办 / 当前 / 未达（色值对齐 TDesign 色板） |

**⑤ 权限码定稿（P1 实际码值，snake_case，对齐 `user_add`/`dict_edit` 风格）**：

```text
workflow_definition_list    定义查询/详情
workflow_definition_add     定义保存/复制
workflow_definition_publish 发布/失效
workflow_definition_delete  定义删除
workflow_definition_export  定义导出
workflow_instance_add       发起流程
workflow_instance_list      实例管理分页
workflow_instance_admin     终止/挂起/激活
workflow_task_admin         改办理人/任意跳转
```

页面按钮 `v-access:code="'workflow_definition_publish'"` 等；**三处一致**——前端 `v-access`、后端 `@SaCheckPermission`、权限管理登记的 code（清单即 §8.2 表）。待办/已办/审批动作/历史/流程图仅要求登录，不挂权限码（办理资格由引擎权限模型约束）。

**⑥ 实时提醒（P2 前端零改动）**：assignment 事件 → message 模块发站内信 → SSE 推既有 `notification` 事件 → `notificationStore` 已有监听自动刷新铃铛；待办页不做轮询，进入页面即刷新（推拉边界与 message-center 一致）。

### 10.5 全链路时序（下游视角）

```text
用户提交请假单 ──▶ 业务接口（草稿→审批中 + start）──▶ flow_instance/flow_task 落库（租户插件自动填 tenant_id）
                                                        │ assignment 事件
经理打开待办 ◀── GET /workflow/task/todo（标题经 LeaveBusinessProvider）◀──┘
经理点通过   ──▶ POST /workflow/task/{id}/pass ──▶ 引擎流转（会签聚合/条件分支）
                                                        │ finish 事件（终态）
业务表 status 更新 ◀── LeaveFlowStatusUpdater（@EventListener）◀──┘
用户查看单据 ──▶ 历史时间线 + 流程图（history/chart 接口）
```

## 11. 错误码

`WorkflowErrorCode implements com.lambda.cloud.core.exception.model.ErrorCode`（契约 §11.1，禁止裸 code），**占用 16xxx 段**（authority 13xxx / config 14xxx / message 141xx / logger 15xxx / ai 30xxx 之后的首个空闲段；落地前按全仓错误码盘点再对齐一次）：

| 码段 | 用途 |
| --- | --- |
| 16100-16109 | 通用：参数非法、流程未发布、并发状态冲突 |
| 16110-16119 | 定义：不存在、编码重复、已发布不可改、已存在运行中实例不可失效 |
| 16120-16129 | 实例：不存在、已终态、无权操作（非创建人/非办理人）、businessId 已存在进行中的同流程实例 |
| 16130-16139 | 任务：不存在、当前用户无办理权限、目标处理人非法、协作操作非法（加签/减签/跳转前置校验失败） |

## 12. 已知限制与后续

1. **`flow_form` 未引入**：引擎标注"开发中"，P3 跟进（§2.3）；
2. **设计器鉴权收敛**：嵌入方式已定稿（菜单 `EMBEDDED_PAGE` → `IFrameView`，§10.4③）；遗留 `/warm-flow-ui/**` 匿名放行的粒度、设计器数据是否按租户隔离，P1 落地时与 `lambda-cloud-starter-security` 对齐定稿（§4.6）；
3. **待办通知**：P2 经 `WorkflowFlowEvent` 对接 message 模块（站内信 + SSE 刷新信号），复用 message 现有投递能力，不建流程侧平行通知表；
4. **角色展开**：节点配置 `role:X` 时待办按聚合标识命中（组成员各自可见待办）；如需"转办时按角色解析成具体人"，下游覆盖 `convertPermissions`，P2 评估是否由模块内置 authority-api 实现；
5. **流程数据归档**：`flow_his_task` 只增不减，归档/清理策略待实例量上来后单独立项（同 message 归档问题的处置节奏）；
6. **引擎升级**：跟随 warm-flow release，DDL 变更按 §6.4 的追加式翻译流程处理。

## 13. 分期实施

| 阶段 | 内容 |
| --- | --- |
| P0（前置） | `lambda-cloud-parent` 登记 `warm-flow.version=1.8.9` 与 dependencyManagement（§6.2）；契约 §3.2 前缀表登记 `workflow → lambda.fusion.workflow`（规则文件独立提交，契约 §18.2） |
| P1（本期） | 后端：模块骨架（pom + 三件套 + imports）；`lambda-workflow-changelog.xml`（7 张引擎表，§6.4）；bridge（`FusionPermissionHandler`/`WorkflowGlobalListener`/`WorkflowFlowEvent`）；definition/instance/task 三子域 Service + Controller（§4.5/§8）；`WorkflowBusinessProvider` SPI + `ObjectProvider` 收集；startup 集成验证（依赖、开关、设计器匿名路径、`mvn compile` 与 Spotless/SpotBugs 通过）。前端（lambda-fusion-web，独立提交）：`system-ui` 新增 `views/workflow/` 页面与组件、`api/system/workflow.ts`、菜单/权限码登记、设计器 `IFrameView` 嵌入与代理配置（§10.4） |
| P2 | 待办通知对接 message（事件消费 + optional 依赖优雅降级）；`docs/skills/lambda-fusion-workflow/SKILL.md` 模块文档；startup 演示业务落地请假样板（按 §10.2 步骤）；角色展开内置实现的评估结论 |
| P3 | `flow_form` 跟进；流程数据归档策略；设计器按租户/权限强收敛（若 P1 定稿为宽松放行） |

## 14. 落地检查清单（PDCA Check）

- [ ] `mvn compile` 通过（Spotless Palantir + SpotBugs，契约 §13）；
- [ ] changelog 命名匹配 `lambda-\w*-changelog.xml`，changeSet 全部带 `preConditions onFail="MARK_RAN"`（契约 §6.1）；
- [ ] 模块 POM 无硬编码版本（契约 §1.2），`mvn dependency:tree` 确认 warm-flow 版本经 BOM 管理、MP 版本收敛到 3.5.17；
- [ ] `flow_*` 表未出现在任何 `mybatis-plus.tenant.ignore-tables` 配置中（§4.2）；
- [ ] 容器内 `PermissionHandler`/`GlobalListener` Bean 各唯一（§4.1/§4.3）；
- [ ] startup 演示：发起 → 待办可见（同租户）/不可见（异租户）→ 通过 → 业务事件回调 → 已办与历史正确；
- [ ] 规则沉淀：`lambda.fusion.workflow` 前缀登记进契约 §3.2（P0 已完成则复核）。
