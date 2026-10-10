# Lambda Fusion 工程契约（Engineering Contract）

> 本文件定义 Lambda Fusion 仓库的可执行工程条款。每条以 **MUST**（强制）/ **MUST NOT**（禁止）/ **SHOULD**（推荐）标注等级，等级定义见 [project-charter.md](project-charter.md) §3.1。
> 冲突时：禁止条款 > 强制条款 > 宪章解释 > `AGENTS.md`（仅入口）。

## 1. 依赖与版本治理契约

### 1.1 Parent / BOM 统一版本（MUST）

- 依赖版本由父 POM `com.lambda.cloud:lambda-cloud-parent:2026.1.1-SNAPSHOT` 与 BOM `lambda-cloud-starter-dependencies` 统一管理；本仓库自有的 `lambda-fusion-bom` 统一管理 `lambda-fusion-*` 版本。
- 模块 POM 仅声明 `groupId`/`artifactId`，不声明 `version`（由 BOM 统一）。

### 1.2 硬编码版本禁令（MUST NOT）

- 不得在业务模块 POM 中硬编码任何依赖 `<version>`。
- 不得引入未经 BOM 管理的第三方依赖版本。

### 1.3 SNAPSHOT 构建前置（MUST）

- 父 POM 与 BOM 均为 `2026.1.1-SNAPSHOT`，构建本仓库前必须先把基础框架安装到本地仓库：在 `lambda-cloud-parent` 目录执行 `mvn clean install`（或 `-N`），否则 SNAPSHOT 解析失败。

### 1.4 关键三方框架升级兼容性清单（MUST）

`lambda-fusion-ai` 的执行语义建立在 AgentScope（`io.agentscope:*`）的行为假设之上（历史教训：框架小版本
行为漂移曾导致子 Agent HITL 事件被误判为运行级待交互）。升级 AgentScope 版本（含小版本）前，必须逐项
验证并在 PR 描述中确认：

1. **事件序列契约**：根/子 Agent 的 `AGENT_START`/`AGENT_END`/`AGENT_RESULT` 顺序、`source` 标记语义
   （`source == null` 为根 Agent）；
2. **HITL 状态语义**：`ASKING`、`TOOL_SUSPENDED`（`GenerateReason`）、`REQUIRE_USER_CONFIRM` /
   `REQUIRE_EXTERNAL_EXECUTION` 的发出时机与持久化时点（先持久化挂起状态、再发结束事件）；
3. **挂起/恢复协议**：`getAgentState`/`saveAgentState` 的会话键语义、工具结果补写的 ID 匹配与不重复约束；
4. **防腐层核对**：升级后 `mvn -pl lambda-fusion-ai test` 必须全量通过，并人工核对
   `AgentExecutionAdapter`、`AgentEventMapper` 中引用框架行为的注释是否仍然成立，失效注释随升级 PR 一并更新。

## 2. 框架能力复用契约（禁止重复实现）

### 2.1 `lambda-fusion-core` 能力复用（MUST）

所有业务模块编写代码时必须优先复用 `lambda-fusion-core` 已提供的能力，详细用法见 `docs/skills/lambda-fusion-core/`（模块深度文档，若该模块已发布 SKILL.md 则以其为准）：

- **分页**：入参继承 `PageQuery<T>`，出参用 `PageView`；禁止自定义分页模型。
- **CRUD**：单表 Service 继承 `AbstractCrudService<E, V, M>`，实体↔VO 转换经 `ConverterResolver`（MapStruct）完成。
- **树结构**：构建用 `TreeBuilder` / `TreeNodeUtils`，过滤扩展实现 `TreeDataFilter`。
- **身份与鉴权**：当前用户信息经 `AuthUtils` / `UserDetails` 获取。
- **字典翻译**：枚举实现 `DictEnum` 并在枚举类上标注 `@DictMapper`。
- **实体基类**：业务实体继承 `BaseEntity`。

### 2.2 复用禁令（MUST NOT）

- 不得重复实现分页模型、CRUD 抽象、树构建、身份上下文解析、字典映射、对象转换。
- 不得在业务模块重复解析 Sa-Token 上下文以获取用户/租户信息（须经 `AuthUtils`）。

## 3. 模块自动配置约定契约

### 3.1 三件套结构（MUST）

每个业务模块作为 Spring Boot 自动配置单元，遵循一致结构：

1. `com.lambda.fusion.autoconfig.XxxAutoConfiguration` — `@AutoConfiguration` 入口，注册在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（每行一个类，**文件末尾无空行**）。
2. `com.lambda.fusion.<domain>.XxxConfigure` — `@Configuration` 模块装配类；条件 Bean 放在这里。
3. `com.lambda.fusion.<domain>.XxxProperties` — `@ConfigurationProperties(prefix = "lambda.fusion.<domain>")`。

### 3.2 配置前缀（MUST）

| 模块 | 前缀 |
|---|---|
| authority | `lambda.fusion.authorize`（注意：authority 域前缀为 `authorize`） |
| permission / permission-api | `lambda.fusion.permission` |
| permission-datascope | `lambda.fusion.datascope` |
| config | `lambda.fusion.config` |
| dictionary | `lambda.fusion.dict` |
| datasource | `lambda.fusion.datasource` |
| ai | `lambda.fusion.ai` |
| workflow | `lambda.fusion.workflow` |
| form | `lambda.fusion.form` |

基础框架 cloud-starter 的前缀（`lambda.oss`、`lambda.security`、`lambda.liquibase`、`lambda.sse`、`lambda.cache` 等）来自 `lambda-cloud-parent`，不得在 fusion 模块内重新定义。

### 3.3 条件装配与扩展点（MUST）

- 条件装配、第三方对象构造和可替换基础设施的默认实现，通过 `@ConditionalOnClass` / `@ConditionalOnProperty` / `@ConditionalOnMissingBean` / `ObjectProvider` 收口到模块 `Configure`。
- 业务 Service / Component 继续使用 `@Service` / `@Component` 并由模块组件扫描发现；不得仅为了开关一个业务实现而移除 stereotype、改成手工 `@Bean`，也不得把条件注解直接散落到业务 Service 实现类。
- 优先定义可供下游应用覆盖的扩展点接口（如 `ConfigChangeHandler`、`DataViewProvider`、`DictSourceResolver`、`TreeDataFilter`）。

### 3.4 自动配置禁令（MUST NOT）

- 不得在业务域包内放置 `@AutoConfiguration` 入口（必须统一在 `com.lambda.fusion.autoconfig`）。
- 不得遗漏 `AutoConfiguration.imports` 注册导致自动配置不生效。
- 不得在 `AutoConfiguration.imports` 文件末尾留空行。

## 4. 模块边界与可选依赖契约

### 4.1 可选依赖约定（MUST）

fusion 模块之间的 `optional=true` 依赖（如 authority → datasource/config/dubbo/nacos）是刻意的：模块在可选依赖缺失时必须**优雅降级**。

### 4.2 改强依赖禁令（MUST NOT）

- 在未核对条件装配逻辑前，不得把可选依赖改成强依赖。
- 不得引入会导致模块在缺失某 starter 时启动失败的强依赖。

## 5. 多租户隔离契约

### 5.1 隔离模型（MUST）

租户隔离的默认模型为**单一共享库 + 字段级隔离**：租户业务表通过 `tenant_id` 列区分租户，不再有按租户切换独立数据源（DEDICATED）的能力。

- authority 的 `TenantContextInterceptor` 负责为常规 HTTP 请求解析并设置当前租户上下文（`tenant_id`）。
- `mybatis-plus.tenant.enabled=true` 时，面向**当前上下文租户**的常规业务 CRUD 以 MyBatis 租户插件作为查询隔离和插入填充的默认机制；同一路径不得再用 Wrapper 重复追加相同 `tenant_id` 条件，也不得手工 `setTenantId` 形成双重机制。
- authority 的租户/组织/用户开通与管理、平台管理、数据同步等**明确操作目标租户而非当前上下文租户**的受控流程，可以显式传递租户字段、设置目标实体的 `tenantId`、追加目标租户条件，或在确需跨租户访问的 Mapper 上使用 `@InterceptorIgnore(tenantLine = "true")`。此类流程必须有明确的权限入口和目标租户来源，查询/更新仍须按租户或业务主键收窄，并在代码语义或注释中说明绕过原因；不得与当前租户插件过滤叠加成两套事实来源。
- DTO、查询对象或领域结果中的 `tenantId` 作为业务参数/返回字段，不属于“手工填充数据库租户列”禁令。
- 动态数据源（`dynamic-datasource` + `p6spy`）仍用于多数据源/多库类型等通用场景，但与租户上下文解耦；请求期不再按租户切换动态数据源。
- 后台任务、异步回调等脱离请求线程后继续执行普通租户业务时，必须从已完成所有权校验的领域对象恢复 `TenantContextHolder`，并在任务结束后清理/恢复原上下文；明确的跨租户管理流程按上一条例外显式限定目标租户。
- 无租户上下文会触发框架的系统级缺省策略，只允许用于明确、受控的启动恢复、平台管理或跨租户扫描；取得目标领域对象后，后续普通租户业务必须恢复其真实租户上下文，仍属于跨租户管理职责的操作则必须显式收窄范围。

### 5.2 多租户禁令（MUST NOT）

- 不得新增按租户切换独立数据源的实现。
- 不得在缺少权限校验、明确目标租户和范围约束时，绕过 `tenant_id` 字段过滤构造跨租户查询或写入路径。
- 不得把 `@InterceptorIgnore(tenantLine = "true")`、手工租户 Wrapper 或实体 `setTenantId` 当作修复普通业务租户上下文缺失的快捷方式；普通租户路径应传播上下文，显式目标租户/跨租户流程按 §5.1 的例外执行。

## 6. 数据库迁移（Liquibase）契约

### 6.1 changelog 命名与聚合（MUST）

- `lambda-cloud-starter-liquibase` 自动聚合 classpath 中所有 `META-INF/db/changelogs/lambda-*-changelog.xml`（正则 `lambda-\w*-changelog.xml`），通过一个 master changelog 执行。
- **`lambda-datasource-changelog.xml` 被强制最先执行**，`lambda-additional-changelog.xml` 最后执行。
- 新增/扩展模块表结构时，编辑该模块 `META-INF/db/changelogs/lambda-<module>-changelog.xml`，命名必须严格匹配该模式，否则不会被加载。
- 已存在于基线的 changeSet 视为可能已经执行，不得删除、改 ID 或改写其内容；结构调整必须追加新的 changeSet。

### 6.2 同步 SQL（SHOULD）

- 新增表时，若该域已有对应的 `docs/sql/la_*.sql` 参考脚本，应同步更新。

### 6.3 Liquibase 禁令（MUST NOT）

- 不得使用不匹配 `lambda-\w*-changelog.xml` 的命名，否则迁移不会被执行。
- 不得在多个模块重复定义同一张表的变更。

## 7. 跨服务：Dubbo、Nacos、Sa-Token 契约

### 7.1 条件装配（MUST）

- 所有 Dubbo `ServiceBean` / `ReferenceBean` 都通过 `@ConditionalOnClass` 判断 Dubbo 是否在 classpath 上才装配。
- Nacos 在多数模块中为可选（`@ConditionalOnClass` + `spring.cloud.nacos.config.enabled`）。
- authority 条件性暴露 Dubbo 远程认证（`AuthorityConfigure.DubboServiceConfiguration`）；`TenantManager` 仅在存在 `DataSourceManageService` 时才创建。

### 7.2 Sa-Token 远程认证（MUST）

- `authority-api` 的 `RemoteAuthenticationService` 继承 `StpInterface`；客户端自动配置为其暴露一个 Dubbo `ReferenceBean`，使其他服务可远程复用角色/权限查询。

### 7.3 跨服务禁令（MUST NOT）

- 不得在缺少 Dubbo 的环境下强装配 Dubbo Bean。
- 不得绕过 `*-api` 模块直接跨服务引用实现类。

## 8. 基础模型与转换契约（VO/DTO/Converter）

### 8.1 自动转换（MUST）

- 实体↔VO 转换经 `ConverterResolver`（MapStruct）完成，不要手写转换。
- 单表 Service 继承 `AbstractCrudService<E, V, M>` 后，`toVO(entity)` / `pageForVO(page, wrapper)` / `pageView(page)` 直接可用。

### 8.2 分页（MUST）

- 入参继承 `PageQuery<T>` 并提供 `getLambdaQueryWrapper()`；出参用 `PageView`。
- 禁止自定义分页模型。

### 8.3 转换禁令（MUST NOT）

- 不得手写实体↔VO 转换方法（与 `ConverterResolver` 重复）。
- 不得在 Controller 直接 `new Page` 与拼 Wrapper（分页收敛到 Service）。

## 9. Entity / Mapper / Service 契约

### 9.1 Entity（MUST）

- 业务实体继承 `BaseEntity`，并在类上标注 `@EqualsAndHashCode(callSuper = true)`。
- 实体类标注 `@TableName("la_<...>")`。

### 9.2 Mapper / Service（MUST）

- Mapper 为继承 `BaseMapper`（或基础框架 `LambdaBaseMapper`）的接口。
- 单表 Service 继承 `AbstractCrudService<E, V, M>`，实现层放 `service/impl/`。

### 9.3 禁令（MUST NOT）

- 不得在 `model/` 子包放业务逻辑。
- 不得在 Controller 直接调用 Mapper（须经 Service）。

## 10. 代码组织与命名契约

### 10.1 包结构（MUST）

- 业务模块采用「模块根级共享 + 子域内分层」结构，详见 [package-structure.md](package-structure.md)。
- 基础包名 `com.lambda.fusion.*`；启动演示模块使用 `com.fusion.startup`；自动配置入口统一在 `com.lambda.fusion.autoconfig`。

### 10.2 命名规则（MUST）

- 持久化实体 `{Name}Entity`、视图对象 `{Name}`（无后缀）、Mapper `{Name}Mapper`、Service `{Name}Service` / `{Name}ServiceImpl`、Controller `{Name}Controller`、自动配置 `*AutoConfiguration` / `*Configure` / `*Properties`。

### 10.3 禁令（MUST NOT）

- 不得新建并行的旧式顶层分层目录（`vo/`、`dto/`、`entity/` 集中包）；新增代码补充到现有子域目录。
- 不得把跨子域共享的横切能力集中到 `commons/` 包（归属对应子域）。

## 11. 异常与错误码契约

### 11.1 统一异常模型（MUST）

- 模块级异常放 `exception/` 子包（跨子域共享，如 `{Domain}BusinessException`、错误码枚举）。
- 错误码以枚举承载，禁止裸 `Integer`/`String` code 承载语义。

### 11.2 禁令（MUST NOT）

- 不得在子域 `model/` 内定义模块级异常。
- 不得吞异常或返回无错误码的泛化异常。

## 12. 配置属性（@ConfigurationProperties）契约

### 12.1 Properties 类（MUST）

- 配置属性类标注 `@ConfigurationProperties(prefix = "lambda.fusion.<domain>")`，前缀遵循 §3.2。
- 嵌套配置用静态内部类，复杂配置加 `@Validated` JSR-303 校验。

### 12.2 敏感配置（MUST）

- 数据库、Redis、Nacos、对象存储等敏感配置必须经环境变量注入（`${MAIN_DB_URL}`、`${REDIS_HOST}`、`${NACOS_*}` 等）。
- 本地敏感值放仓库根目录已 gitignore 的 `.evn` 文件（注意拼写），不得提交真实值。

### 12.3 禁令（MUST NOT）

- 不得散落使用 `@Value` 注入本应由 `@ConfigurationProperties` 管理的配置。
- 不得提交含真实密钥的配置文件。

## 13. 静态检查契约（Spotless + SpotBugs）

### 13.1 已绑定的检查（MUST）

父 POM 在 **`compile`** 阶段强制执行两道关卡，`mvn compile` 即会在违规时失败：

- **Spotless — Palantir Java Format**（`PALANTIR` 风格，v2.67.0，不格式化 Javadoc）。自动修复：`mvn spotless:apply`；仅检查：`mvn spotless:check`。
- **SpotBugs** — compile 阶段 `check`，使用各模块 `spotbugs-exclude.xml`。根目录 `spotbugs.skip=false`；`lambda-fusion-ai` 设 `spotbugs.skip=true`。可按模块用 `-Dspotbugs.skip=true` 覆盖。

### 13.2 完成标准（MUST）

- 所有 Java 代码必须已符合 Palantir 规范，否则构建失败。
- 提交前 `mvn compile`（含 Spotless + SpotBugs）必须通过。

### 13.3 禁令（MUST NOT）

- 不得绕过 Spotless / SpotBugs 提交（禁止 `--no-verify` 类绕过手段）。
- 不得手动编辑由 antrun 重新生成的 `lombok.config`。

## 14. 注解处理器契约

### 14.1 装配约定（MUST）

注解处理器由父 POM 装配：Lombok、MapStruct（+ `lombok-mapstruct-binding`）、`spring-boot-configuration-processor`，以及自定义的 `lambda-cloud-processor`。`lombok.config`（`addLombokGeneratedAnnotation=true`）由 antrun 步骤在每次构建时重新生成。

### 14.2 禁令（MUST NOT）

- 不得手动编辑 `lombok.config`（会被构建覆盖）。
- 不得在业务模块自行引入与父 POM 冲突的注解处理器版本。

## 15. 测试契约

### 15.1 运行约定（MUST）

- 测试运行在 classpath 上而非 module path（父 POM 设置 `surefire.useModulePath=false`）；遇到 JPMS 相关测试失败时据此排查。
- `lambda-fusion-ai` 带完整测试套件，`core` / `form` / `logger` 已陆续补充针对性测试；改动 agent / graph / evaluator / node 逻辑时，必须执行 `mvn -pl lambda-fusion-ai test`，其他模块改动执行对应模块测试。

### 15.2 测试分层（SHOULD）

- 单元测试优先复用基础框架 `lambda-cloud-starter-test` 提供的设施。
- 鼓励为其余核心模块（authority / config / dictionary 等）补充测试，持续扩大覆盖面。

### 15.3 禁令（MUST NOT）

- 不得提交无断言或仅“实现陪跑”的无效测试。
- 不得用反射访问 private 成员维持脆弱测试。

## 16. 安全红线契约

### 16.1 安全禁令（MUST NOT）

- 不得硬编码依赖版本、密钥、Token。
- 不得提交含真实凭据的 `.evn` 或配置文件（`.evn` 已 gitignore）。
- 不得引入绕过鉴权、绕过权限同步、绕过多租户隔离的实现。
- 不得新增会导致“数据权限 / 租户隔离失效”的查询路径。
- 不得在日志中输出敏感信息（密钥、Token、完整凭据）。

## 17. 执行与验证契约

### 17.1 规则执行（MUST）

- 执行任何代码生成、修改、重构、依赖/配置/数据库变更前，先读 [project-charter.md](project-charter.md) 确认规则等级与例外，再读本契约确认涉及条款。
- 用户要求与规则冲突时，必须先说明冲突点并请求确认，不得直接违反禁止条款。
- 规则缺失或不可执行时，优先提出规则修订建议，而不是按历史习惯自行补充。

### 17.2 变更完成标准（MUST）

- `mvn compile`（含 Spotless + SpotBugs）通过。
- 涉及 ai 模块逻辑变更时 `mvn -pl lambda-fusion-ai test` 通过。
- 涉及数据库变更时 Liquibase changelog 命名匹配且可执行。

## 18. Git 代码提交规范契约

### 18.1 提交信息格式（MUST）

使用 [Conventional Commits](https://www.conventionalcommits.org/) 风格 `<type>(<scope>): <描述>`：

- **type**：`feat`、`fix`、`refactor`（不含行为变更）、`style`、`docs`、`chore`、`test`、`perf`。
- **scope**（可选）：受影响模块或域，如 `authority`、`core`、`permission`、`dictionary`、`ai`，或完整模块名 `lambda-fusion-authority` 等。
- **描述**：中英文均可，祈使语气、简明扼要。

### 18.2 提交粒度（MUST）

- 原子提交：一个提交对应一个明确目的；避免混合多个无关变更。
- 规则文件变更独立提交，不与业务代码混在同一提交。

### 18.3 提交前检查（MUST）

- 提交前 `mvn compile`（或对应模块 `mvn verify`）通过。

### 18.4 禁令（MUST NOT）

- 禁止不带描述的裸 `fix` / `refactor` 提交。
- 禁止 `--no-verify` 绕过检查、禁止 force push 主分支。

## 19. 特性开关与条件装配契约

### 19.1 能力开关（MUST）

- 模块整体能力开关用 `@ConditionalOnProperty(prefix = "lambda.fusion.<domain>", name = "enabled")`，并设 `matchIfMissing` 默认值。
- 回退装配必须安全降级（缺失依赖时不得抛错启动）。

### 19.2 业务级动态开关（SHOULD）

- 运行时动态开关可结合 Nacos `@RefreshScope`。

### 19.3 禁令（MUST NOT）

- 不得用散乱 `if (config == xxx)` 判断替代条件装配。
- 不得引入额外的 feature flag 第三方库（基础框架 `@Conditional*` 已足够）。

## 20. 最小充分设计与变更审计契约

### 20.1 既有事实优先（MUST）

- 新增状态、表、实现类、异常层、配置项或装配分支前，必须先审计既有领域对象、框架拦截器、统一异常机制和可复用 Service；已有事实来源能满足需求时不得复制字段或另建平行事实来源。
- 设计与实现必须区分“当前已支持”与“未来可能扩展”；不得为尚未纳入本次范围的 Redis、多实例、命令总线、兼容双实现或 feature flag 预埋空壳。
- 同一业务能力默认保留一个主实现。确需新旧双实现、命令流水表、专用 Advice 或额外配置开关时，必须有可验证的并存/幂等/错误映射需求，不能仅为实现形式完整而新增。

### 20.2 自动化修改禁令（MUST NOT）

- 不得因未核对项目组件扫描与自动配置结构，随意移除业务类的 `@Service` / `@Component`，或把部分业务实现改成 `@Bean`、部分保留 stereotype。
- 不得因后台线程缺少请求上下文，就在普通租户业务中绕过租户插件、复制 `tenantId` 到多个业务事实、手工设置实体租户字段或为每个 Wrapper 编写租户辅助方法；应从已校验的领域对象传播框架上下文。authority 等显式目标租户/跨租户管理流程按 §5.1 的例外边界执行。
- 不得把可以由行锁、唯一索引、状态与阶段号直接保证的幂等流程，未经证明确有历史命令查询需求就扩展为额外命令账本。
- 不得新增只重复统一异常映射职责的局部 `ControllerAdvice`。

### 20.3 并发与单一事实来源契约（MUST / MUST NOT）

- 单一事实来源（MUST）：同一份领域事实只能由一个组件解释或推导；下游消费其规范化输出，不得各自再维护一份平行的状态机或推导逻辑。按「持久性」区分归属：易失的运行时态（如调度器 trigger、在跑任务）归运行时组件解释；需跨重启一致的业务状态（如定时任务 `schedule_enabled` 启用态）归 DB 解释，运行时组件仅按其联动、不作为该状态的权威来源。
- 并发边界单一表达（MUST）：一段状态的并发保护只能用一种机制表达；若所有读写都发生在同一把锁的同步边界内，不得再叠加原子类/线程安全容器重复表达同一边界。仅被非同步路径访问的共享引用才使用原子类型，并据此明确区分。
- 锁内校验为唯一权威（MUST）：涉及「检查-再用」的共享状态，校验与使用必须在同一临界区内完成；不得在锁外先做一次性预检再到锁内使用，以免引入 TOCTOU 窗口。锁外的早期校验不得作为权威依据。
- 显式语义优先于隐式约定（MUST）：不得依赖「某元素总在列表末尾」「某事件一定先于另一事件」之类的隐式顺序约定来表达业务语义；应以显式的状态/操作表达。
- 避免中转与反向依赖（MUST NOT）：不得保留只转发他方、无自身逻辑的中转方法；不得在对象与其创建者、或协作者之间形成环形/反向调用。被多方共享的无状态逻辑应收敛到中立的归属点，由各方平级直调。
- 消除死代码（MUST NOT）：不得为兼容性、对称性或形式完整而保留无生产调用方的方法、工厂或仅服务旧测试的公开 API；删除后须同步收敛其唯一调用方。
