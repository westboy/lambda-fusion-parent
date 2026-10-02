# 动态表单功能逻辑审计报告

审计日期：2026 年 9 月 30 日。

动态表单仍处于开发阶段。2026 年 9 月 30 日完成第一批权限和数据正确性整改，10 月 1 日完成设计器、共享布局、路由挂载、接口权限目录、单表契约及统一鉴权修复。10 月 2 日预览完成前后端数据交互，查询和增删改查通过后端读写目标业务表，关闭预览后数据保留。真实 MySQL 与已登录业务系统的部署验收尚未执行，不能将独立浏览器和 H2 测试等同于部署验收。

本次讨论确认并实施的职责是：发布决定生效版本；发布路由挂载通用运行页、菜单及接口权限绑定；角色授权仍需明确选择。预览、版本发布、路由发布和角色授权分别执行，各批实现与验证边界见下节。

本报告保留审计基线中的问题及证据，并记录随后获授权实施的整改。问题详情中的原始行号以审计基线为准；每项的整改状态和本节汇总描述当前工作区。验证使用测试内存数据库，没有访问运行中的业务数据库。

## 第一批整改结果（2026-09-30）

| 范围 | 已实现行为 | 验证 |
| --- | --- | --- |
| 发布权限 | 发布、停用、回滚在服务入口校验发布权限；保存即发布沿用同一路径，无权限时整笔草稿事务回滚 | Spring 事务与真实 Mapper 集成测试 |
| 表和系统字段边界 | `allowed-tables` 默认空清单，选表、保存、发布和运行均检查；禁止映射 ID、租户和审计列，也禁止业务字段占用 `id` | 保存、编译、运行与租户插件测试 |
| 数据协议 | 列表、详情、树统一返回字段标识与 `id`，保留空值；更新未提交字段保持原值，显式 null 清空；空更新和零影响行报错 | H2 上执行真实动态 SQL |
| 逻辑删除 | 0 有效、1 已删除；新增初始化，普通编辑不能修改；计数、列表、详情、树、修改、删除与子表引用检查统一过滤 | JDBC 读写及删除后访问测试 |
| 查询 | IN 逐元素参数绑定；前端支持多选/逗号输入、数值区间及空值操作，保留 0 和 false；LIKE 直接绑定完整模式值 | JDBC 查询、前端协议与真实数值组件输入测试 |
| 草稿与发布 | 更新版本必传，身份字段不可改；发布和回滚先编译并检查目标表；事务内先通过乐观锁竞争指针，再插入快照 | 缺失版本、非法模型与并发发布测试 |
| 缓存 | 每次运行入口经租户 Mapper 核对数据库状态和版本；编译缓存按表单 ID 区分且版本必须匹配；提交后失效 | 晚到旧加载、同名表单、停用、发布与回滚测试 |

运行边界：停用或版本切换提交后，新进入模型读取的请求检查数据库；此前已取得模型的在途操作不会被强制取消。各节点采用相同核对机制，正确性不再依赖 300 秒 TTL；本批未实现跨节点广播。

配置变化：模块默认不开放任何表；startup 显式开放 `la_demo_leave`。`la_area` 等既有核心业务表不会默认进入动态 CRUD，确需开放的专用表应加入 `lambda.fusion.form.allowed-tables`，并继续满足框架租户策略。H2 仅为 `test` 依赖，版本沿用 BOM，不改变应用数据库。

第一批未改动数据库结构，也未实现旧配置或历史数据迁移。后续批次追加布局、显示名、菜单绑定和资源树管理锁；目标表与字段契约批次复用现有物理元数据和快照，没有新增表或迁移。

## 第二阶段：表单设计器批次（2026-10-01）

| 范围 | 本批实现 |
| --- | --- |
| 可视化画布 | 三步向导中的表单与详情页改为字段区、真实 Vben 控件画布、属性面板；支持拖入、拖动排序、上下移动、移出后重新加入、1～4 列和字段占列 |
| 字段属性 | 显示名、组件、提示、默认值、编辑时只读、必填、字典编码；文本长度与正则、数字上下限分开配置，JSON 仅作为显式应用的高级属性入口 |
| 统一布局 | `FormLayout { columns, items: [{ field, span }] }` 贯穿草稿、不可变快照、运行 Schema 与新增/编辑/详情；新字段默认追加，删除映射清理引用，字段改名同步布局引用 |
| 标签 | 导列取列注释作为默认显示名，保存到 `FIELD_LABEL`，查询、列表、画布、表单和详情均使用 label |
| 三种预览 | 当前未保存设计、列表中的已保存草稿、版本历史中的指定快照；通过正式编译和 Schema 组装，复用运行布局与列表组件，可切换新增、编辑、详情和列表 |
| 预览隔离 | 仅示例数据；预览不保存草稿、不发布、不访问运行时业务数据 API；列表的新增、修改、删除按钮展示配置文案并禁用，详情使用内存示例记录 |
| 生命周期 | 设计器可保存草稿或保存并发布；已有生效版本的停用表单有启用入口，启用沿用原快照且不生成版本 |
| 能力入口 | 新建仅开放 SINGLE 和 NORMAL；暂不开放文件类型、树/主从布局、未兑现的高级查询、合计/冻结/默认排序、导入导出和批量按钮；同动作按钮不再重复添加 |

数据库：追加 `lambda-fusion-form-202609300003`，新增 `la_form_def.FORM_LAYOUT` 和 `la_form_field.FIELD_LABEL`，不改写已存在的 changeSet。追加设计管理资源 `form:design:preview`（`lambda-authority-2026093000012`）；需通过现有角色管理明确授予需要预览的设计人员，本批不自动追加角色授权或任何运行数据权限。

验证：`mvn -pl lambda-fusion-form -am test` 通过（form 93 项、core 3 项），包含 Spotless、SpotBugs，真实 Mapper 的布局保存/发布/预览/回滚、预览权限与无写入检查、非法布局拒绝，以及 H2 中完整表单 Liquibase changelog 的首次与重复执行。前端 `pnpm exec vitest run packages/system-ui/src/views/form` 的 6 个测试文件、17 项通过；表单 API 与视图专项类型检查通过，ESLint 无错误（测试夹具有 4 条单文件多组件提示）。组件测试覆盖实际 Vben 默认值与编辑回填、共用详情布局、拖拽事件同步和预览隔离。

浏览器组件夹具已验证真实字段拖入、显示名及占列编辑、“区域编码/区域名称”同行、“父级区域”整行、序列化后重开和共用详情布局，并检查无脚本错误。拖入位置采用 `newIndex`，恢复 Sortable clone 模式下的源节点后交由 Vue 更新，避免顺序偏差与字段区残留节点。

仓库级验证限制：完整 `system-ui` 类型检查另有数据权限、租户和工作流页面的 7 处既有错误。扩展执行既有 Vben `form-integration.test.ts` 时，27 项被本地 Vue 实例重复加载的 `wrapperRef` 异常阻断，使用基线 Vitest 配置亦可复现；未计入本批通过项。表单组件测试的 Vitest 配置统一 Vue 并使用所需组件库的 ESM 入口，未修改 Vben 表单内核。

边界：浏览器验证使用独立组件夹具与示例数据，后端使用 H2 测试数据库，未在真实 MySQL 或已登录业务系统中部署验收。设计器批次之后的路由、授权及目标表契约见下列批次记录。

## 第二阶段：路由与接口权限批次（2026-10-01）

| 范围 | 本批实现 |
| --- | --- |
| 通用页面 | 统一组件 `views/form/runtime/index.vue`，通过路由 `meta.formKey` 定位当前租户模型；不同 formKey 重新挂载运行组件，清空查询、编辑与详情状态；“刷新表单”重新读取 Schema 与生效版本 |
| 独立挂载 | `GET/PUT/DELETE /v1/form-design/{id}/route`；父菜单、名称、路径、图标和排序可配置；首次要求已有可用发布版本，重复操作复用资源；`POST /v1/form-design/routes` 为设计列表提供真实挂载状态 |
| 稳定身份 | `la_form_def.ROUTE_RESOURCE_ID` 只存菜单绑定，名称、路径等配置以 authority 资源为准；资源扩展记录归属的 formId，路由扩展明确组件与 formKey，不从访问路径推断表单、不绑定版本号 |
| 授权边界 | 发布路由需要 `form:design:route`，并继续校验既有资源管理角色、增改删、移动和接口绑定权限；角色授权独立选择角色和操作，复用现有 grant/revoke API，仅授予使用权（status=0） |
| 动态接口目录 | 不生成动态字节码。固定通用端点的 `@FormPermission(FormAction)` 声明权限动作，当前由统一 Sa-Token 注解链执行；`FormApiPermissionProvider` 读取当前租户已发布定义，补充到既有 `ApiPermissionRegistry`，覆盖七个端点与四类操作 |
| 权限标识分离 | 操作资源标识为 `form-ui:{formKey}:{action}`，真实接口权限仍为 `form:{formKey}:{action}`，通过 `PermissionService.bind` 写入 `la_api_resources`；接口解绑后，按钮标识不能替代接口权限。`design` 为管理权限保留标识，禁止用作业务 formKey |
| 并发与撤销 | 当前表单行锁保证重复挂载；authority 资源树固定锁行将归属/路径检查与写入放在同一事务锁边界，静态资源管理也遵守相同锁。路径冲突拒绝，包含其它功能子资源时拒绝覆盖/撤销。撤销仅删除本功能管理的资源及绑定，保留草稿、快照和生效版本 |
| 缓存与降级 | 资源、接口绑定和角色授权的权限缓存改为提交后清理，回滚不清除已有缓存；缺少本地 authority 服务时明确返回挂载不可用，版本发布与预览继续可用 |

安装变化：追加 `lambda-fusion-form-202610010001`（菜单绑定及唯一约束）、`lambda-authority-202610010001`（路由管理资源）与 `lambda-authority-202610010002`（资源树固定锁行）。管理锁不保存权限或菜单副本，仅提供数据库事务互斥；它必须随 authority 代码升级执行。新增管理权限资源不自动分配给角色，实际使用前需在既有角色管理中明确授权。

接口权限范围：本批实现同应用、当前租户请求下的动态接口目录与授权绑定。目录从持久化定义实时派生，重启不丢失，静态元数据重新加载不覆盖它；无租户上下文不扫描全部租户。跨服务 `PermissionClientInitializer` 仍只上报编译期静态元数据；分离部署下的动态目录同步需要另行扩展携带租户边界的同步协议，本批不将租户动态目录混入全局静态上报。

验收覆盖实际 MVC 权限拒绝、按钮与接口码分离、接口解绑后权限收回、真实 authority Mapper 的独立挂载与显式授权、资源身份保持、同表单及不同表单并发、路径冲突、外来子资源保护、撤销保留版本、提交/回滚缓存、租户边界、可选集成降级。浏览器组件夹具验证了父菜单选择、自定义路径和逐项角色授权，确认发布路由不会自动授权；尚未连接真实业务数据库做部署验收。

本批最终验证：`mvn -pl lambda-fusion-form -am test` 全部通过（form 107 项、core 3 项，包含所涉依赖模块的编译、Spotless 与 SpotBugs）；新增 form/authority changeSet 在 H2 上执行并验证重复执行。前端表单目录 8 个测试文件、21 项通过，表单范围 `vue-tsc` 通过，ESLint 无错误（9 条测试夹具多组件提示）；Chrome 组件夹具验收通过且无脚本错误。仓库其它页面的既有类型问题与 Vben 旧集成测试环境限制仍按上一批记录，不计作全仓检查通过。

## 第二阶段：目标表与字段契约批次（2026-10-01）

| 范围 | 本批实现 |
| --- | --- |
| 元数据 | 限定当前 catalog/schema、转义 JDBC 模式字符、复核表名；从主键元数据识别真实主键，返回类型码、精度、默认值、自增和生成标记 |
| 主键和写入契约 | 单列 ID 主键且能容纳 19 位雪花值；拒绝复合、自增、生成或容量不足的 ID；校验字段类型、可空性、审计列、未赋值必填列和无法输入的必填字段；数值主键也按字符串返回 |
| 租户策略 | 核对实际 SqlSessionFactory 的租户插件，缺失上下文拒绝访问；跳过契约不跳过租户过滤；共享表须无 TENANT_ID，已有租户表被加入忽略清单后读写都拒绝 |
| 字段值 | 服务端执行类型解析、required、文本长度、数值上下限和正则；拒绝非整数、非法布尔/日期、非有限数值、物理列溢出及精度损失；编译时拒绝无效规则和表单默认值 |
| 默认值与事务 | 表单默认值按类型绑定；数据库默认表达式仅由数据库执行，映射字段实际默认值在新增事务内回读校验，失败回滚；显式 null 不触发默认值，部分更新保留未提交字段 |
| 生命周期和设计器 | 发布、回滚、启用及写入重新检查当前结构，失败不切换版本；导列将生成/自增列设为只读、不提交；不落库字段不再阻塞新增表单的必填校验 |

支持范围以设计文档 §5.5 为准：当前目标为支持事务的 MySQL 专用业务表，自动化使用 H2 MySQL 模式。物理元数据不加入快照或长期缓存。字典成员校验、完整文件协议、无物理列的虚拟字段和字段级权限不属于本批交付；readonly 与显示开关仍是展示配置。数据库自身的唯一键、外键、CHECK、触发器、字符集和日期范围约束继续由数据库负责。

本批最终验证：`mvn -pl lambda-fusion-form -am test` 通过（form 177 项、core 3 项，含所涉依赖模块编译、Spotless 和 SpotBugs）。覆盖真实 JDBC 结构反例、非法新增/修改不写数据、数据库默认值校验失败后事务回滚、租户策略变更后拒绝越界读取，以及结构变更后发布、回滚、启用不改变原生效版本。前端表单目录 8 个测试文件、22 项通过，表单范围 `vue-tsc`、本次修改文件的 ESLint 和 oxfmt 检查通过。未执行真实 MySQL 部署验收；此前记录的全仓类型检查限制仍保留。

## 第二阶段：预览入口可达性首次修复（2026-10-01，权限方案已被下节纠正）

用户截图暴露了此前验收遗漏：设计列表已有“编辑、发布、停用、版本”等操作，却没有“预览”。源码中的列表、编辑器和历史版本预览入口只检查新增的 `form:design:preview`，该资源没有自动授予既有设计角色，持有原有设计权限的用户因此看不到入口。此前组件夹具没有覆盖真实权限指令下的设计列表入口，不能证明这条使用路径可用。

当时将入口改为接受 `form:design:detail` 或 `form:design:preview` 任一权限，但没有解决 Service 裸 `StpUtil.checkPermission` 绕过统一开发者豁免的问题。此 OR 方案现已撤回；下节描述当前实现，详情权限不再代替预览权限。

点击列表“预览”直接打开已保存草稿，默认显示列表，可切换新增、编辑和详情；编辑器继续支持未保存设计，版本历史支持指定版本。预览消费示例数据，不以发布或挂载为前提，不读写业务数据。

新增回归从真实设计列表组件与真实 `v-access` 指令进入预览，覆盖仅有既有详情权限、仅有独立预览权限、无预览读取权、历史版本入口，并检查四种呈现模式及业务接口未被调用。后端 `mvn -pl lambda-fusion-form -am test` 通过（form 179 项、core 3 项，含编译与静态检查）；前端表单目录 9 个测试文件、26 项通过，表单范围类型检查、本次文件 ESLint/oxfmt 均通过。浏览器连接工具本轮初始化报“系统找不到指定的路径”，未核对用户已登录页面；修复代码需要后端更新和前端刷新后才会在该页面生效。

## 第二阶段：统一鉴权与开发者豁免修复（2026-10-01）

用户再次反馈“无此权限：form:design:preview”后，核对框架源码确认：Cloud `SaTokenInterceptor` 在注解检查前对 `ROLE_DEV` 调用 `SaRouter.stop()`；表单 Service 随后的裸 `StpUtil.checkPermission` 又重新拒绝了开发者。独立注册的 `FormPermissionInterceptor` 同样游离于此豁免路径。此前通过的静态权限 mock 测试没有覆盖真实拦截链，这是实现和验证范围的缺陷。

本批按现有框架职责修正：

- 固定设计端点保留标准 `@SaCheckPermission`；三个预览端点恢复独立 `form:design:preview`，由 `lambda-cloud-processor` 生成元数据，再由 permission-api 的 loader/registry/matcher 消费。
- 删除独立的表单 MVC 权限拦截器及其注册；`FormPermissionHandler` 使用 Sa-Token 现成的 `SaAnnotationHandlerInterface`，由 `SaBeanInject` 自动注册进 Cloud 使用的注解鉴权链。动态目录仍通过既有 `ApiPermissionProvider` 扩展接入注册表，没有另建权限目录或授权表。
- 预览与路由 Service 删除重复接口鉴权。保存时自动发布、菜单适配器跨能力调用仍需内层权限边界，在 core 的既有 `AuthUtils` 中提供 `checkPermission/hasPermission`，复用 `StpLogicUtils.getActiveStpLogic()`，与 Cloud 的开发者角色豁免一致。
- 前端统一代码权限检查与运行 Schema 的操作权限也豁免精确 `ROLE_DEV`，普通管理员或相似角色名不获得该豁免。预览不读写业务数据；开发者权限豁免不会跳过租户过滤、模型状态、目标表清单和字段校验。

新增测试执行真实 Cloud `SaTokenInterceptor`、Sa-Token Servlet 上下文过滤器/注解扩展、Controller、Service 与 Mapper，只替换登录身份和授权数据源。覆盖开发者无逐项授权时的三种预览、动态读写和自动发布，普通用户未授权拒绝、预览授权与撤销、不同表单/动作隔离、内部自动发布权限及事务回滚；编译产物经真实 `LocalPermissionLoader`、`ApiPermissionRegistry`、`ApiPermissionMatcher` 校验。

最终验证：先执行 clean 构建清除已删除的独立拦截器产物，补齐新测试容器的 Sa-Token Servlet 适配后，`mvn -pl lambda-fusion-form -am test` 全部通过（form 183 项、core 5 项，含编译、Spotless 和 SpotBugs）。前端表单目录 9 个测试文件、28 项通过，表单范围类型检查及本次修改文件的 ESLint 通过。未连接用户当前已登录页面，也未改写实际数据库中的角色授权；需更新后端构建产物并重启、刷新前端使这次统一鉴权修复生效。

## 第二阶段：完整交互预览初版（2026-10-02，数据方式已被后续批次替代）

用户要求预览能够像正常功能一样操作。原有实现把新增、编辑、详情、列表拆成四个展示模式，列表内写入按钮被禁用，查询和分页也没有处理示例数据，不能完成实际使用流程。本批移除该模式切换与固定示例行，改为全屏运行页面，从同一列表完成查询、排序、分页、新增、编辑、详情和删除。

数据按“本次预览内生效”实现：打开时为空，新增经正式 Vben 表单校验后进入列表；编辑回填已有值，确认后更新，取消不修改；删除经确认后移除，末页无记录时返回有效页；刷新保留当前会话数据，关闭、重开或切换配置后清空。预览与正式页面共用列表、编辑弹窗、详情抽屉和字段规则，只替换数据来源。正式页面继续调用既有业务接口。

浏览器验证同时暴露并修正了两个实际运行页问题：异步 Schema 的查询配置必须经 `gridApi.setState` 更新，仅修改初始化对象不会渲染查询栏；格式化单元格改用稳定的共享插槽，避免异步增加布尔/字典/日期字段时真实 VXE 提示插槽不存在。详情抽屉增加关闭按钮，并移除无行为的默认确认按钮。

预览数据只保存在当前组件会话，不写业务库或浏览器持久化存储。未模拟数据库生成值、触发器和物理约束；文件上传仍关闭。预览的权限入口及 `ROLE_DEV` 豁免继续沿用上一批实现。提交过程绑定启动时的数据来源，关闭或切换来源后不会把尚未完成的预览提交转成业务 API 写入。

验证使用真实 Vben、TDesign 和 VXE 组件的独立 Chrome 页面，覆盖新增、查询/重置、数值排序、翻页、编辑回显、详情、末页删除及关闭重开，并断言没有业务 CRUD 请求。未连接用户已登录页面。

本批最终验证：前端表单目录 11 个测试文件、46 项全部通过；专项 `vue-tsc`、oxfmt 及 ESLint 检查通过（测试夹具保留样式规则提示）。Chrome 验收完整流程通过，无脚本错误或业务 CRUD 请求，并检查了紧凑开关控件与全屏/编辑弹窗布局。新增测试同时覆盖正式页面仍调用业务保存接口，以及异步校验时切换数据来源不会把预览提交转为真实写入。

## 第二阶段：预览前后端数据交互（2026-10-02）

用户明确要求预览请求后端并完成正常功能。本批删除前端内存数据引擎，列表、搜索、排序、分页、详情、新增、编辑和删除均通过 `/v1/form-design/preview/data` 数据端点，由现有 `DynamicDataService` 执行真实业务 SQL。关闭预览不会撤销已完成的写入，界面明确展示该行为。

- 三种来源均有明确协议：已保存草稿传 `formId + draftVersion`，历史版本传 `formId + version`，当前未保存设计传 `formId? + design`。服务端读取或编译对应模型，不借用当前发布版本替代待预览设计。
- 普通用户同时需要 `form:design:preview` 与对应 `form:{key}:page/add/edit/delete`。提交当前设计额外要求新增或编辑设计权限，防止仅有预览权限的用户改造物理列映射。所有静态接口仍用 `@SaCheckPermission`，经 Cloud 处理器与 permission-api 收集；内部动态操作权使用统一 `AuthUtils`，开发者沿用 `ROLE_DEV` 豁免。
- 已有表单不可通过当前设计改变标识、目标表或类型；新设计不可冒用已存在的 formKey。保存草稿和编辑器当前设计均核对草稿修订号，拒绝旧窗口继续写入变更后的模型。历史版本使用其字段模型操作当前业务数据，不恢复历史业务数据。
- 数据请求执行目标表清单、物理契约、字段规则、事务和实际租户插件。先校验表单归属再读取历史快照；停用表单不开放数据操作。预览不自动保存草稿、发布、挂载路由或授予角色。
- 前端操作按钮使用后端返回权限，详情请求防止过期结果回填；保存等待后端成功再关闭并重新查表。当前设计配置固定为打开时的深拷贝。文件字段完整上传协议仍未实现。

后端 `mvn -pl lambda-fusion-form -am test` 通过（form 193 项、core 5 项，含编译、Spotless 和 SpotBugs）。新增真实 MVC/Cloud 鉴权/Mapper 测试，以及 H2 中三种来源 CRUD、查询排序分页、数据库默认值校验与回滚、草稿冲突、禁用、身份约束、跨租户隔离和处理器权限元数据测试。前端表单目录 11 个文件、37 项测试通过，移除了仅验证内存模拟器的测试，新增后端请求协议、权限展示、接口保存等待、刷新和重开读取持久化结果的用例。

专项 `vue-tsc`、oxfmt、ESLint 均通过（ESLint 无错误，测试夹具保留 7 条组件风格提示）。独立 Chrome 使用实际 Vben/VXE、表单 API 与 RequestClient，经本机 HTTP 测试桥进入真实 MockMvc/Cloud 拦截器/Controller/Service/MyBatis/H2，25 次请求完成新增、搜索/重置、数值排序、分页、编辑、详情、末页删除、刷新、关闭重开，以及当前未保存设计读取同一业务数据。数据库断言确认写入持久化、更新与删除生效，同时草稿修订号不变、发布版本数仍为零。该验收仅替换登录身份与基础设施，不用前端内存 CRUD 代替后端；未连接实际业务库或用户已登录页面。

## 审计范围

| 范围 | 基线与内容 |
| --- | --- |
| 后端 | Fusion 提交 `32fd96f`；`lambda-fusion-form` 全模块、相关 authority 权限和菜单、startup 演示数据 |
| 前端 | Web 提交 `4c0d16a`；设计器、通用运行页、编辑和详情弹窗、字段映射、API、菜单路由解析 |
| 框架集成 | Cloud MyBatis 自动配置、租户插件、审计填充与动态 SQL 的交互 |
| 设计与版本管理 | [动态表单设计文档](dynamic-form.md)、Liquibase 结构定义、发布快照和现有测试覆盖 |

当前功能处于开发阶段，用户已明确不考虑旧配置和历史数据迁移。本轮按统一的新配置与布局模型推进，不实现旧配置转换、历史数据回填、旧快照格式兼容或旧模型布局适配；相关升级风险不纳入本轮整改和验收。版本历史、预览和回滚的验证范围为当前模型生成的发布版本。

下文前端表单路径以 `_lambda_fusion_web/packages/system-ui/src/views/form/` 为基准，路由工具另列完整路径。优先级含义：P0 为权限或数据归属问题；P1 为基础使用和数据正确性问题；P2 为配置能力、适配和维护问题。

最初的 `MP_OPTLOCK_VERSION_ORIGINAL` 异常属于乐观锁拦截器缺失问题。当前 Cloud 工作区已注册 `OptimisticLockerInnerInterceptor`，本报告不把此前已修正的配置重复计为未修复缺陷。

## 功能完成情况

| 功能 | 源码现状 | 判断 |
| --- | --- | --- |
| 草稿管理与选表导列 | 有三步设计器、字段和关系存储、精确元数据读取；已落实版本必传、身份不可变、表清单、系统列及目标表契约 | 单表约束已实现，部署验收边界见批次记录 |
| 可视化表单设计 | 已接入真实 Vben 画布、属性面板、拖入/排序/占列和统一布局持久化 | 第二阶段设计器批次已实现 |
| 发布与版本回滚 | 快照不可变、MD5 去重、生效指针；已补权限、发布/回滚编译、并发冲突、提交后缓存处理与启用入口 | 第一批修复及启用已完成；完整物理表校验仍待补齐 |
| 独立发布路由与访问入口 | 已有统一运行组件、独立挂载/更新/撤销、实际状态展示与明确角色授权；接口目录在运行时派生 | 本地 authority 集成已实现；分离部署动态目录同步尚未实现 |
| 设计和版本预览 | 三种来源共用编译模型和完整运行页，查询、排序、分页及增删改查连接后端业务表 | 写入真实生效；关闭后保留数据 |
| 单表列表与详情 | 统一投影为字段标识，固定返回 id 并保留空值 | 数据协议已修复 |
| 单表新增与编辑 | 白名单字段、生成 ID、审计填充、参数绑定、显式清空和部分更新 | 更新语义已修复；类型及物理表完整约束仍待补齐 |
| 删除 | 物理删除检查影响行；逻辑删除统一过滤读写并由引擎维护标记 | 本批已修复基础删除语义 |
| 查询 | 字段/操作符白名单、IN 逐项绑定、区间和空值输入 | 本批已修复审计列出的 IN、0 值区间与空值操作问题 |
| 树表 | 后端仅 SELF 按父节点取直接子集；前端运行页没有树渲染 | 部分底层接口，未形成可用树表功能 |
| 主从表 | 能保存关系，删除时检查子表记录 | 无主从查询、编辑、联合保存与级联删除闭环 |
| 工作流与业务扩展 | 保存 `flowCode` 等元数据，运行模型没有对应处理 | 设计文档中的 P2 能力，尚未实现 |
| 文件字段 | 默认映射到普通 Upload 组件 | 未形成统一上传、落库引用、回显和下载协议 |
| 高级列表配置 | 设计器开放多项配置，部分没有传到或被运行页消费 | 有配置入口不等于功能已实现 |

## 应先修复的权限和数据问题

以下问题描述保留原始审计结论，每项开头标明当前整改状态。

### 自动发布绕过发布权限

**整改状态：已修复。** 发布权限下沉到发布服务入口，自动发布拒绝时草稿、字段和快照均回滚。

**基线问题（P0）。** 新建和保存接口分别只检查 `form:design:add`、`form:design:edit`。保存命令带 `publishOnSave=true` 时，设计服务直接调用发布服务，调用路径没有再检查 `form:design:publish`。

因此，一个有编辑权限、没有发布权限的账号，也能通过保存请求使修改对外生效。设计器已经拆分的编辑与发布权限没有在自动发布分支保持一致。

依据：[FormDesignController.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/controller/FormDesignController.java) 第 55、63、93 行；[FormDesignServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormDesignServiceImpl.java) 第 96、116 行。整改时保留自动发布功能，但让自动和手动发布执行同一套发布权限校验。

### 系统列可以被映射为普通业务字段

**整改状态：已修复。** 保存、快照编译和写入归一拒绝系统列与业务字段名 `id`；发布执行同一编译校验。

**基线问题（P0）。** 前端只在自动导列时跳过 `ID`、`TENANT_ID` 和审计列。手工配置与后端保存校验没有禁止这些映射，运行时又把所有持久化字段直接写入 INSERT 或 UPDATE。

如果将 `TENANT_ID` 映射为可写字段，持有该表单新增或编辑权限的用户就能提交其它租户值。UPDATE 中的租户 WHERE 条件只限定当前被更新的记录，并不阻止 SET 改写记录归属；INSERT 显式提供租户列也会改变自动补列路径。映射 ID 或审计列还会造成重复列、主键改写或审计字段失真。

依据：[FormDesignServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormDesignServiceImpl.java) 第 202 行字段校验；[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 102、123、201 行；前端 `designer/form-def-editor.vue` 第 69、342 行。整改应在服务端保存、发布和模型编译阶段拒绝系统列作为普通可写字段，保持租户归属由框架统一处理。

### 任意业务表映射绕过原业务服务

**整改状态：映射边界已收紧。** 表清单默认拒绝，仅显式允许的表可设计和运行；动态 CRUD 仍不会自动复用该表原业务服务，应只开放符合此语义的专用表。

**基线问题（P1）。** 默认只排除表单元数据、Liquibase 和 Quartz 表，没有允许映射的业务表清单，也没有按表区分设计权限。设计和发布权限不能自动等同于所有目标表的管理权限。

截图中的 `la_area` 是真实行政区划表，框架默认对它跳过租户过滤。原 `AreaServiceImpl.deleteArea` 会检查子区域，动态表单单表删除直接执行按 ID 删除，不经过这个检查。相同机制用于用户、资源、角色或带审批状态的表时，也不会自动复用原服务的业务约束。

依据：[FormProperties.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/FormProperties.java) 第 31 行；[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 139 行；[AreaServiceImpl.java](../../lambda-fusion-authority/src/main/java/com/lambda/fusion/authority/area/service/impl/AreaServiceImpl.java) 第 77 行。建议先限定专门开放给动态 CRUD 的表；核心业务表需接入已有服务或明确的业务扩展接口。

## 单表运行时的确定缺陷

### 查询结果没有转换为字段标识

**整改状态：已修复。** 列表、详情与树共用字段投影，固定 `id`，屏蔽驱动列名大小写差异并保留 null 键。

**基线问题（P1）。** Mapper 返回物理列名组成的 Map，数据服务原样返回。前端表格、详情和编辑回填按 `field.name` 取值，而自动导列会把物理列名转成 camelCase 字段名。

例如，字段 `applicant` 映射列 `APPLICANT`，结果可能是 `{ID: "1", APPLICANT: "张三"}`，前端读取的却是 `row.applicant` 和 `row.id`。结果键大小写受驱动影响，但字段名与物理列名不同时，缺少投影转换这一问题始终存在。ID 键为 `ID` 时，详情和删除会传 `"undefined"`；编辑弹窗还可能因 `record.id` 不存在而提交新增。

依据：[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 78、97、191 行；[DynamicTableMapper.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/mapper/DynamicTableMapper.java) 第 33 行；前端 `components/form-runtime.vue` 第 128、187、193 行，`components/form-data-dialog.vue` 第 36 行。应统一对外行协议，固定返回 `id`，其余键按发布模型的字段标识投影。

### 逻辑删除后记录仍然可见和可编辑

**整改状态：已修复。** 统一追加有效记录条件，删除列须为 INT/BOOL，由引擎按 0/1 维护并从编辑 Schema 隐藏。

**基线问题（P1）。** 删除仅执行 `逻辑删除列 = 1`，分页计数、列表、详情和树查询没有自动追加“未删除”条件。更新前的 `getById` 同样接受已删除记录，子表引用检查也不排除软删除记录。

这条动态 SQL 路径没有实体上的逻辑删除 SQL 注入可以代为补齐，因此记录会继续出现在页面和详情里。逻辑删除列还作为普通映射字段参与更新，缺少禁止用户复活记录的约束。

依据：[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 61、89、150、169、185 行。应统一生成有效记录条件，并明确删除列类型、有效值、删除值及写入规则。

### 显式 null 更新无法清空字段

**整改状态：已修复。** 按字段是否提交区分更新意图；前端将可见持久化控件的 undefined 转为 null。空更新和零影响行显式拒绝。

**基线问题（P1）。** `normalize` 仅在 `value != null` 时加入更新内容，没有区分字段未提交与显式提交 null。用户以 null 清空可空字段后，旧数据库值仍然保留。

启用 `skipContractColumns` 时，如果所有提交值均为 null 或没有可写字段，更新语句还可能变成空 SET。当前服务也没有检查 UPDATE 或 DELETE 的影响行数，并发删除后可能仍返回成功。

依据：[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 123、201、215 行。应按键是否存在判断更新意图，支持显式清空，并处理无修改项和零影响行。

### 多种查询条件不能正常生成或执行

**整改状态：已修复本节所列问题。** IN 值保留且逐元素绑定，数字区间有双输入组件，0 值保留，空值操作有显式选择入口。

**基线问题（P1）。** 查询的前后端都有问题：

- 前端 `in` 分支发送 `{field, op}`，丢掉用户选择的 value；后端随后要求数组值，会拒绝该请求。
- 即使直接调用 API 提交正确数组，后端仍将整个集合绑定为 `IN (一个占位符)`，没有为每个元素生成独立参数，不能正确表达 SQL IN 列表。
- 前端用 `!value[0]` 和 `!value[1]` 判断区间完整性，`[0, 10]` 等包含 0 的区间被直接丢弃。
- `isNull`、`isNotNull` 在处理操作符前先过滤空输入，用户不填写值时不会发送条件；没有适合这类操作符的查询交互。
- 数字区间仍生成单个 InputNumber，字典 `in` 仍生成单选 Select，操作符与输入组件没有完整配套。

依据：前端 `form-field-meta.ts` 第 206、240、309、318、332 行；[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 301 行。应按操作符定义输入与值协议，再按元素绑定 IN 参数。

### 契约列豁免与运行时 SQL 不一致

**整改状态：部分修复。** `skipContractColumns` 默认排序改用 ID，不再引用 CREATED_AT。此开关仍不关闭租户插件，目标表与共享表策略的发布校验尚待补齐。

**基线问题（P1，含部署约束）。** 发布允许 `skipContractColumns=true` 的表缺少审计列，运行时默认排序却固定使用 `CREATED_AT DESC, ID DESC`。这类表有数据时，默认分页会因缺少 CREATED_AT 报错。

该开关只影响发布检查和服务中的审计填充，并不关闭框架租户插件。没有 TENANT_ID 的表仍需符合框架共享表策略；这一点虽然在注释里写明，但仅在设计器打开开关并不能保证表单可用。

依据：[FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java) 第 159 行；[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 329 行。应让排序使用已验证存在的列，并在发布时检查目标表与框架租户策略是否相容。

## 发布和生命周期问题

### 发布成功不保证模型能被使用

**整改状态：本节编译时机和 null 配置问题已修复。** 发布、回滚先编译再写入状态；props/rules 接受 JSON null。目标表与字段规则检查见后文及本批次记录。

**基线问题（P1）。** 发布流程校验物理表后序列化快照并拨指针，没有调用 `FormModelCompiler`。运行时第一次读取才做更严格的树结构、列表布局和组件配置检查；回滚也只是切换指针。

例如，TREE 的 `treeConfig="{}"` 能通过设计服务的非空检查和发布表校验，但运行时编译会因缺少 sourceType 拒绝。列表级 `layout` 保存时只检查顶层键，非法枚举也可能到首次访问才报错。`props` 或 `rules` 内含 null 值则可能在编译器的 `Map.copyOf` 处抛异常。

依据：[FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java) 第 65、120 行；[FormModelCompiler.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/support/FormModelCompiler.java) 第 123、197、247 行。应在发布写快照前执行完整编译和目标表约束校验，回滚前验证选定快照可编译并满足当前目标表约束。

### 缓存失效存在并发窗口

**整改状态：已修复后续请求受旧缓存误导的问题。** 状态与版本逐次从数据库核对，缓存命中要求 ID 和版本匹配，事务提交后清除；旧加载晚到只能造成重新编译，不能绕过新请求的停用或版本检查。在途请求边界见本报告首节。

**基线问题（P1，并发缺陷）。** 发布、停用和回滚在事务提交前调用 `evict`，与类注释承诺的“事务提交后”不一致。并发请求可以在缓存清除后、事务提交前读到旧版本并重新缓存，之后没有第二次失效。

此外，Provider 的 load 和 put 没有版本或代次保护，即使把清除移到提交后，已在加载旧版本的请求也可能晚于清除执行 put。缓存命中直接返回模型，不复查停用状态，所以停用后仍可能继续读写，直到默认 300 秒 TTL 到期。跨节点当前只靠 TTL，这是已记录的实现限制，不应误认为已有广播机制。

依据：[FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java) 第 96、111、128 行；[FormModelProvider.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/support/FormModelProvider.java) 第 41 行。整改需处理提交后失效与旧加载回填两个问题；即时停用的要求还需落实到运行入口。

### 草稿版本不是必传参数

**整改状态：已修复。** 更新 version 必传；并发发布先竞争草稿乐观锁，再插入快照，失败者返回既有草稿冲突错误。

**基线问题（P1）。** 命令注释写“更新时必传”，但没有约束，服务仅在 version 非空时比较。客户端省略 version 后，服务会先读最新实体再覆盖全部草稿；乐观锁只能保护这次服务端读取后的竞争，无法识别客户端基于旧草稿提交的覆盖。

并发发布还采用“查最大版本加 1”，唯一索引可防止重复版本提交，但冲突可能先表现为数据库唯一键异常，无法走既有的草稿冲突提示。这里是错误处理和并发控制缺口，不能据此断言会写入重复版本。

依据：[FormDefSaveCommand.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/model/FormDefSaveCommand.java) 第 67 行；[FormDesignServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormDesignServiceImpl.java) 第 108 行；[FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java) 第 249 行。更新应强制提供草稿版本，并统一发布竞争的业务错误。

### 已有表单的身份字段只在前端禁止修改

**整改状态：已修复。** 后端拒绝更改既有表单的 formKey、formType、tableName。

**基线问题（P1）。** 前端编辑时禁止改 formKey、formType、tableName，后端 `apply` 却直接接受它们。通过 API 改 formKey 后，定义查询使用新标识，生效快照、原菜单和权限仍使用旧标识，旧缓存键也没有清除，可能形成多套不一致的身份。

依据：[FormDesignServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormDesignServiceImpl.java) 第 259 行；前端 `designer/form-def-editor.vue` 第 472、485、494 行。应将前端表达的不可变约束落实到服务端。

### 停用后的重新启用路径缺失

**整改状态：已实现。** 独立启用接口与设计列表入口恢复当前生效快照，复用发布权限及模型/目标表校验，不生成新版本。

**P1 确定的流程缺口。** 设计器对 DISABLED 隐藏发布按钮，没有启用按钮。后端对内容未变的发布返回“版本未变化”，无法用重新发布原配置来恢复状态。

回滚 API 虽能把状态设回 PUBLISHED，前端却只允许回滚非当前版本。只有一个版本的已停用表单没有这条 UI 恢复路径；多个版本也不能用回滚当前版本直接恢复。

依据：前端 `designer/index.vue` 第 277、355 行；[FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java) 第 75、124 行。应提供明确的启用操作，并区分当前生效指针与实际可访问状态。

## 目标表和字段契约问题

### 发布校验不足以保证新增和编辑可执行

**整改状态：已实现单表契约。** `TargetTableContract` 与 `TableMetadataReader` 覆盖真实单列主键、ID 容量、默认/生成列、未赋值必填列、列类型和容量、实际租户策略及元数据范围；发布、回滚、启用和写入共用。支持范围及数据库自身约束边界见设计文档 §5.5。

**基线问题（P1）。** 原实现只要求存在 ID 和必要契约列，并检查已配置列的大类类型。没有核实 ID 是否真正的唯一主键、是否能容纳字符串雪花 ID、是否存在身份列或复合主键；也没有检查未映射的非空且无默认值列、实际长度和默认值兼容性。

因此，“表存在、发布成功”不代表 INSERT 或 UPDATE 能成功。非空字段隐藏且没有默认值时，创建页也没有可行的填写路径。

元数据读取没有绑定明确 catalog/schema，传入表名也没有转义 JDBC 匹配模式或复核结果中的 TABLE_NAME。在多 schema、同名表或名称含匹配字符时存在读错或混合列元数据的风险，具体表现依赖 JDBC 驱动。`primaryKey` 还只是按列名是否为 ID 判断，没有读取主键元数据。

依据：[FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java) 第 148 行；[TableMetadataReader.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/support/TableMetadataReader.java) 第 46、75、95、122 行。应明确支持的目标表契约，并让发布验证覆盖实际 CRUD 需要的条件。

### 不落库字段仍被要求有物理列

**整改状态：已明确为物理列只读映射，虚拟字段延期。** `persisted=false` 仍要求物理列，但导列能识别数据库生成列并设为只读、不提交，前端不再要求用户填写它。无物理来源的展示/计算字段尚未实现。

**P2 确定的能力不一致。** `persisted=false` 仅在写入归一时生效，发布仍检查它的物理列，查询也仍选择该列。它可以表达“读取现有列但不写入”，不能实现界面所描述的纯展示或计算字段。

依据：[FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java) 第 168 行；[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 191、205 行。建议先准确标注现有语义，再单独实现不依赖物理列的字段来源。

### 服务端字段校验只有非空约束

**整改状态：基础类型和字段规则已实现。** `FormFieldValidator` 与物理列校验共同执行 required、类型、长度、范围和正则；默认值也遵守同一规则，数值/布尔/日期默认值经转换后绑定。字典值是否属于当前字典、文件引用及字段级业务权限仍需独立能力接入。

**基线问题（P1）。** 原运行时没有按字段类型做转换和校验，也不执行规则中的 required、长度、范围、正则或字典值约束。数值和布尔默认值统一以字符串存储和下发，错误类型可能直到 JDBC 或数据库才失败。

前端 readonly 只是 disabled 属性，formShow/listShow 只是显示配置，不能当作服务端写权限或字段保密规则。这里不能把文档中的“表单中只读”误解为已经实现的业务不可修改保证；如果业务依赖这些约束，当前实现不能满足。

依据：[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 201 行；前端 `form-field-meta.ts` 第 136、277、303 行。应明确哪些是 UI 配置、哪些是必须由服务端执行的数据规则，并为后者建立类型和校验契约。

### SQL 没有完整数据库方言适配

**整改状态：已明确 MySQL 支持范围，其它方言延期。** LIKE 已改为绑定完整模式值，不再依赖三参数 CONCAT；LIMIT 分页和版本查询仍未适配其它方言。H2 MySQL 模式仅为测试环境。

**基线问题（P2）。** 运行时分页和发布版本查询直接写 LIMIT，模糊查询直接写三参数 CONCAT，没有方言分支。这条 Map Mapper 路径没有使用框架分页对象，因此不能假设已有 PaginationInnerInterceptor 会自动适配它。

依据：[DynamicDataServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/DynamicDataServiceImpl.java) 第 76、284 行；[FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java) 第 253 行。如果当前只支持 MySQL，应明确声明；如果复用项目的其它数据库支持承诺，应接入对应方言并核对元数据和 Liquibase 类型兼容性。

## 当前发布逻辑与使用入口

当前发布入口是 `POST /v1/form-design/{id}/publish`，按钮只提交表单 ID，实际流程为：

1. 服务入口校验 `form:design:publish`，读取数据库中已保存的表单定义、字段和关系。
2. 组装快照 JSON，计算 MD5；与当前生效快照相同时拒绝重复发布。
3. 取最大历史版本加 1，编译待发布模型并检查允许表清单、目标表及列。
4. 在同一事务中先通过乐观锁更新 `la_form_def.published_version` 和 `status=PUBLISHED`，再插入不可变快照；失败整笔回滚，并发失败者返回草稿冲突。
5. 事务提交后清除本节点编译缓存。后续运行请求逐次检查数据库状态与版本；前端收到响应后提示成功并刷新设计列表。

上述过程没有调用路由注册、authority 资源创建或角色授权，也没有执行发布后跳转。按钮发布的是数据库中已保存的草稿；未保存的设计器修改不会被此接口读取。保存即发布走先保存再调用同一发布服务的分支。

依据：当前 [FormPublishServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/service/impl/FormPublishServiceImpl.java)、[FormModelProvider.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/support/FormModelProvider.java)、[FormDesignController.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/design/controller/FormDesignController.java) 和前端 `designer/index.vue`。

**此处基线缺口已整改。** 原先只有 `leave_demo/index.vue` 包装页，新表单需要额外编写 Vue、配置菜单及权限。本批已增加统一运行页和独立路由管理，通过明确组件与 formKey 绑定；版本发布仍不执行资源创建或授权，角色须独立选择。

依据：前端 `runtime/leave_demo/index.vue`、`designer/index.vue` 第 40、216 行、`packages/utils/src/helpers/generate-routes-backend.ts` 第 84 行；[lambda-additional-changelog.xml](../../lambda-fusion-startup/src/main/resources/META-INF/db/changelogs/lambda-additional-changelog.xml) 第 137 行。这是设计文档明确采用的 P0 接入方式，但对“设计完即可使用”的产品目标仍是明显缺口。

后端 `GET /v1/form/{formKey}/schema` 和通用 CRUD API 保留。原先缺少的通用参数路由、独立挂载与明确授权已按上文批次补齐，现有发布接口继续只负责版本生效。

通用组件还需监听 formKey 变化；现在仅 onMounted 加载 schema，复用页面切换表单时可能仍保留旧模型。整改时应同步切换 Schema、查询条件及编辑状态，避免菜单已经切换而页面仍显示另一张表单。

### 草稿与版本预览未实现

**整改状态：已实现。** 当前设计、已保存草稿与指定历史版本均有预览入口，共用编译模型、Schema 与正式渲染规则；2026-10-02 按用户要求改为通过后端操作真实业务表，替代下述基线中仅展示效果的方案。

**P1 确定的功能缺口。** 动态表单设计器、列表和版本历史没有预览按钮或预览视图，设计 API 和前端 form API 中也没有对应预览能力。项目已有的表单渲染基础不能等同于已经提供了设计预览流程。

现有 `/v1/form/{formKey}/schema` 通过 `FormModelProvider.require` 读取 `published_version` 指向的快照，未发布表单会报错；已有发布版本的表单也只返回生效快照，不会展示当前草稿修改。它也没有接受历史版本参数。因此，打开已有运行页只能使用生效版本，不能验证未保存设计、已保存草稿或指定历史版本的实际效果。

依据：[FormDataController.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/controller/FormDataController.java) 第 41 行、[FormModelProvider.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/support/FormModelProvider.java) 第 54 行、前端 `designer/form-def-editor.vue`、`designer/index.vue` 与 `api/form/index.ts`。

应提供三种来源的预览：设计器当前未保存模型、数据库已保存草稿、指定发布快照。预览共用实际 Vben 渲染和布局规则，可切换新增、编辑和详情效果，但不修改生效版本、不调用真实业务写入。这样才能在发布前验证配置，并使设计预览与发布后的使用效果保持一致。

## 已确认的发布职责与整改要求

保留“发布”与“发布路由”两个独立操作，预览独立于两者。第一批已修复发布权限、模型编译、并发和缓存问题；后续批次按下面的职责实现发布路由与预览，并补齐单表物理契约。

| 操作 | 处理对象与职责 | 完成后的结果 |
| --- | --- | --- |
| 预览 | 读取当前未保存设计、已保存草稿或指定历史快照，使用正式渲染规则和后端数据引擎操作业务表 | 无需发布或挂载菜单；不自动保存草稿或切换生效版本，数据保存和删除真实生效 |
| 发布 | 校验已保存草稿，生成不可变快照并切换生效版本；保存即发布仍调用同一发布流程 | 新版本可供运行接口使用；不隐式创建路由、菜单或角色授权 |
| 发布路由 | 为已有生效版本的表单配置访问路径，绑定通用运行组件，创建或更新菜单及运行权限资源 | 用户获得可挂载和授权的访问入口；不生成表单版本、不改写当前生效快照 |
| 角色授权 | 使用现有 authority 机制，明确选择哪些角色获得页面及新增、编辑、删除等权限 | 被授权用户可访问相应入口和操作；不得因版本发布或路由发布成功就默认授权所有角色 |

“发布路由”应在设计列表提供独立入口，配置父菜单、菜单名称、访问路径、图标及排序。首次挂载应要求表单已有生效版本且处于可用状态；已有挂载应展示当前配置并支持更新、撤销和进入页面。版本状态、生效版本与路由挂载情况应分别展示，路由状态从资源绑定关系确定，不能用 `PUBLISHED` 同时表达“版本已生效”和“菜单已创建”。

后续实现应遵循以下约束：

1. **路由绑定稳定的表单身份。** 通用组件通过明确的 formKey 参数定位当前租户的表单，读取 `published_version` 指向的快照。访问路径与组件标识分开处理，不靠路径末段猜测 formKey，不把某个版本号写死在路由中；服务端落实 formKey 不可随意修改的约束。
2. **再次发布和回滚沿用已有入口。** 发布新版本或回滚只改变生效快照，同一路由在重新加载后读取对应模型，无需再次发布路由、重建菜单或重新授权。已有页面的刷新行为与缓存失效时效需要一致。
3. **路由发布可重复执行。** 按已建立的表单与资源绑定创建或更新，重复操作不产生重复菜单和权限资源。路径或资源已被其它功能占用时明确报错，不覆盖无关资源；一次操作失败不能显示为挂载成功。
4. **管理权限与运行权限分别校验。** 发布路由需要独立的管理权限，并遵守现有菜单、资源管理权限边界。复用 authority 的资源、角色和权限刷新机制，不直接绕过服务写授权表；版本发布权限不能自动等同于路由管理权或运行数据读写权。保存即发布也不能绕过发布权限。
5. **撤销入口与停用表单分别操作。** 撤销路由撤下本功能管理的入口，保留草稿、历史快照和当前生效版本。停用表单必须在运行入口阻断访问，即使用户持有旧地址或直接调用 API 也不能继续读写。菜单撤下后的权限资源处理应沿用 authority 规则，避免误删其它入口共用的资源；重新启用应有明确操作路径。
6. **保持模块边界和资源事实来源。** 路由挂载使用已有 authority 能力；具体调用方式在实施时核对模块依赖和可用接口。若集成环境未提供菜单管理能力，应明确提示该功能不可用，表单版本发布与预览仍可独立使用，不为挂载另建一套权限体系。

完整使用流程应为：设计并预览 → 保存草稿 → 发布版本 → 独立发布路由 → 明确角色授权 → 从菜单进入运行页。预览也可以直接读取编辑器中尚未保存的设计；已有路由的表单修改后只需再次发布版本，入口和原授权继续沿用。发布成功提示应显示生效版本，并在入口未挂载时提供“发布路由”操作，不能把未创建菜单描述为发布失败，也不能把发布成功描述为已经完成用户授权。

## 设计配置的实际生效情况

| 已开放的配置或类型 | 实际消费情况 | 处理建议 |
| --- | --- | --- |
| 列冻结、合计、默认排序、排序优先级 | 编译模型保存，但 Schema 没有下发，运行页没有实现 | 完成协议和行为，或暂时移除配置入口 |
| 列表布局 TREE_LEFT、TREE_GRID | Schema 有值，运行页始终渲染普通 Grid | 在有树实现前拒绝或明确标记未支持 |
| 高级查询、全量查询 | 保存并下发，运行页不消费 | 不应展示为已生效的开关 |
| 按钮自定义文案 | 已在实际列表和列表预览消费 label | 本批已修复；同动作按钮不再重复添加 |
| 导入、导出、批量删除 | 设计器可选；没有对应运行操作，Schema 权限也只有三个写动作 | 暂时隐藏，后续按需求实现 |
| TREE | 无前端树闭环；未提供 parentValue 时已改用 IS NULL；节点列和过滤列校验仍不完整 | 明确根节点协议和列校验，再完成树查询与联动 |
| MASTER_SUB 与 CASCADE_DELETE | 未联合读写；有子记录时两种策略都拒绝删除，子表及外键有效性到使用时才暴露 | 按延期能力处理，先限制选择 |
| FILE | 普通 Upload，没有模块统一的文件引用协议 | 复用现有文件能力并完成绑定和回显 |
| 字段显示名 | 已保留列注释默认标签和自定义 label，贯穿列表、查询、编辑与详情 | 本批已实现 |
| DATE 展示 | date 仅显示日期，datetime 显示日期时间 | 本批已修复 |

这些配置问题的依据包括：[FormRuntimeServiceImpl.java](../../lambda-fusion-form/src/main/java/com/lambda/fusion/form/runtime/service/impl/FormRuntimeServiceImpl.java) 第 101 行、前端 `components/form-runtime.vue`、`form-field-meta.ts` 与 `designer/form-def-editor.vue`。树、主从、工作流、跨节点广播和代码生成主要属于设计文档中的延期能力；不应把延期本身都算成实现错误，问题在于当前界面已经允许选择部分尚未兑现的能力。

缓存已改为使用租户 Mapper 查得的表单 ID，不再另取登录用户的 tenantId 组成缓存键，消除了两处租户来源不一致的缓存风险。仍未设置容量上限和主动清理，过期后不再访问的表单条目会继续占用内存，需按实际规模补充管理。

## 可视化设计器整改要求

### 当前设计器缺少画布和布局模型

**整改状态：已实现本节基础画布与共享布局。** 下文保留审计基线；第二阶段本批已替换表单属性表格，支持字段拖入、排序、移除、占列和属性编辑。浏览器验证时发现并修正了跨字段区拖入的索引口径及残留克隆节点，回归测试覆盖这两条路径。

**P1 当前明确需求缺口。** 用户要求表单和详情至少基于 Vben 实现动态拖拽设计。原设计文档第 47、447 行明确选择“本期不做拖拽画布”，解释了现状，但该范围不能覆盖现在的产品目标。整改应更新设计器目标，不能继续将属性表格视为可视化设计器已经完成。

当前 `designer/form-def-editor.vue` 第 941 行仍是原生 table，逐行填写组件名称、属性 JSON 和规则。它没有让用户直接看到正在设计的表单，也不能拖动控件、设置占列或调整区域。截图里的字段名换行、配置列拥挤和开关文案断行，反映的是固定弹窗承载过多属性列的交互问题。

运行时 `components/form-data-dialog.vue` 第 41 行已经调用 `useVbenForm`，但第 46 行固定为单栏和响应式两栏；`components/form-data-detail.vue` 第 76 行另用循环渲染纵向字段。后台 `FormConf` 只有 show、component、props、rules、readonly，当前没有独立的表单布局结构。因此，仅给字段配置表增加拖拽排序，还不能设计实际表单和详情布局。

项目已有可复用基础：`packages/system-ui/src/adapter/form.ts` 提供 Vben 表单适配，`packages/@core/ui-kit/form-ui/src/types.ts` 第 548 行支持 formItemClass，第 818、836 行支持布局与 wrapperClass；`packages/@core/composables/src/use-sortable.ts` 提供 Sortable 封装。基础栅格与字段渲染可以复用这些能力，画布编辑、设计数据持久化以及较复杂的布局容器仍需实现。

建议把表单和详情设计改成以下工作区：

| 区域 | 第一版应具备的能力 |
| --- | --- |
| 左侧字段和组件区 | 显示已映射业务字段及可用组件，将字段拖入画布，明确字段绑定关系 |
| 中间表单画布 | 显示真实 Vben 控件，支持拖入、拖动排序、移除、单栏或多栏及字段占列，能看到标签和必填状态 |
| 右侧属性面板 | 根据选中组件编辑中文标签、提示、默认值、只读、字典和相应规则；文本显示长度规则，数值显示数值范围；常用属性不用手填 JSON |
| 顶部操作区 | 提供布局设置、新增和编辑预览、详情预览、保存草稿与发布，画布与属性面板同步更新 |

字段映射、查询设计、列表设计和表单画布应承担各自职责。基础数据页保留表映射；列表页使用实际列表预览配置列与按钮；表单页用较大的工作区设计布局。JSON 可保留为高级编辑入口，不能承担常规设计操作。

设计模型至少需要稳定的字段引用、业务标签、排列顺序、占列和全局栅格设置。布局应以统一的新结构贯穿草稿、发布快照、运行 Schema 与渲染过程，并为新建表单提供默认布局。预览、新增、编辑与详情共用布局来源，按模式分别渲染交互控件或只读值；分组、折叠等更复杂容器按明确需求扩展。

截图还暴露两处可直接核对的配置问题：`designer/form-def-editor.vue` 第 611 行添加按钮时总是新增 add，没有动作去重，而运行页第 73 行用 find 只取同动作的第一个配置，多个“新增”不会成为独立按钮；“详情只读”实际修改的是编辑控件的 disabled，应改成“编辑时只读”或其它准确文案。

这一阶段的验收例子应是：把“区域编码”和“区域名称”拖到同一行，再把“父级区域”放到下一行并占满；设计画布即时显示效果，保存并重新打开后布局保持一致，发布后的新增、编辑和详情也按同一结构展示。该流程才证明可视化设计已经落地。

## 已有正确机制与验证边界

设计与运行子域分离、不可变快照和生效指针、草稿修改不直接改变已发布字段模型，都是合理结构。运行接口按 `form:{formKey}:{action}` 检查权限；查询字段、操作符和排序有服务端白名单；值采用参数绑定，没有发现用户值直接拼进 SQL 的路径。草稿乐观锁和发布版本唯一索引也能阻止部分并发覆盖或重复提交。

第一批验证：`mvn -pl lambda-fusion-form -am test` 通过，包含 Spotless、SpotBugs，表单模块 7 个测试类共 83 项，依赖的 core 模块另有 3 项。新增 H2 测试通过真实 Mapper、租户插件、乐观锁插件和 Spring 事务验证结果键、参数绑定、逻辑删除、自动发布权限拒绝及并发发布；另有缓存晚到回填、提交/回滚和版本选择测试。H2 依赖限定 test 范围，未替换运行数据库。JaCoCo 仅排除插桩后超过 JVM 方法大小限制的第三方生成词法解析器，业务代码检查仍启用。

前端相关 Vitest 共 9 项通过，覆盖查询值协议、清空语义、可空字段校验及数值区间组件的实际输入；`apps/web-tdesign` 的 `vue-tsc --noEmit --skipLibCheck` 通过。本批没有执行浏览器端完整使用流程、真实 MySQL 部署或多节点环境验收；这些测试不能替代尚未完成的能力验收。

## 建议整改顺序

第一阶段已完成权限和数据正确性整改：自动发布权限、系统列映射、字段结果投影、逻辑删除、null 更新、查询操作符、发布前编译、草稿版本及缓存失效。表映射采用默认拒绝、显式允许的清单。已完成与待实施项以首节和每项整改状态为准。

第二阶段完成可使用的单表版本与 Vben 可视化设计器：画布和属性面板、可持久化的布局模型、共享布局的预览与新增编辑详情渲染、通用运行路由、独立的“发布路由”及挂载管理、明确的角色授权、设计列表进入入口、停用和启用、字段显示名、目标表契约校验。保留版本发布职责，让用户按“发布版本、挂载路由、授权使用”完成接入，新表单不需要再写 Java 或 Vue。

第三阶段逐项兑现设计器配置，暂时隐藏未支持选项，再按真实需求开发树表、主从表、文件和工作流。无需把全部 P1、P2 能力一次做完，但界面声明的能力必须与实际行为一致。

单表版本至少应满足以下验收条件：

- 发布前拒绝不能运行的配置，更新必须携带草稿版本。
- 列表、详情和编辑都按字段标识取值，所有记录固定提供 id。
- 可空字段能清空，逻辑删除数据不会继续被正常查询或编辑。
- in、包含 0 的区间、空值查询按统一协议工作。
- 自动发布不能绕过发布权限，用户不能提交系统归属或审计列。
- 同一 formKey 在不同租户下正确加载各自模型，停用和回滚达到约定的生效时效。
- 版本发布成功只切换生效快照，不自动创建菜单、路由或扩大角色权限；保存即发布遵守同一边界。
- “发布路由”是独立操作，复用通用页面；能配置父菜单、名称、路径、图标和排序，并显式选择角色授权。
- 同一表单重复发布路由不产生重复资源，冲突路径不覆盖其它资源；发布新版本或回滚无需重复挂载或授权。
- 撤销路由保留版本数据；停用表单阻断已有页面和直接 API 访问，重新启用有明确入口。
- 未保存设计、已保存草稿和指定历史版本均可预览；预览不自动保存设计、不改变生效版本，且无需先创建菜单。数据操作按权限读写真实业务表，关闭后仍保留。
- 发布路由后，被授权用户能从菜单进入正确表单；复用组件切换 formKey 时，Schema、查询和编辑状态同步切换。
- 字段可拖入真实表单画布，排序和占列可调整；保存重开、预览与发布后使用保持同一布局。
- 当前模型生成的发布版本能够正确读取、预览和回滚；目标数据库和目标表支持范围有明确声明。
