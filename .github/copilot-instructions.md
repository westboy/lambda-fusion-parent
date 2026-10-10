# Lambda Fusion - GitHub Copilot 指令

> 本文件是 GitHub Copilot 在本仓库工作的中文规则速查。
> **完整权威规则以 `.rule/engineering-contract.md` 为准，冲突时以 `.rule/` 为准。** 规则正文不得仅在本文件修改，须同步 `.rule/`。

## 规则入口

动手前依次读取（`.rule/` 为唯一主规则来源）：

1. `.rule/project-charter.md` - 项目宪章（规则等级 AS-IS / SHOULD / MUST NOT、迁移态、例外）
2. `.rule/engineering-contract.md` - 工程契约（可执行条款）
3. `.rule/contract-index.md` - 主题索引 + 关键词索引
4. `.rule/package-structure.md` - 标准包分层细则

规则优先级：`MUST NOT`（禁止）> `MUST`（强制）> 宪章解释 > `AGENTS.md`（仅入口）。

## 红线速查（MUST NOT，任何情况下不得违反）

- 不得在业务模块 POM 硬编码依赖 `<version>`（由 parent POM / BOM 统一）。
- 不得重复实现 `lambda-fusion-core` 已有能力：分页（`PageQuery`/`PageView`）、CRUD（`AbstractCrudService`+`ConverterResolver`）、树（`TreeBuilder`）、身份（`AuthUtils`）、字典（`DictEnum`/`@DictMapper`）、实体（`BaseEntity`）。
- 不得手写实体↔VO 转换（用 `ConverterResolver`/MapStruct）、不得自定义分页模型。
- 不得把 `optional=true` 可选依赖改强依赖（缺失须优雅降级）；改前必核对条件装配。
- 不得新增按租户切换独立数据源（单一共享库 + `tenant_id` 字段级隔离）。
- 普通租户内 CRUD 以 MyBatis 租户插件为默认机制，不得再手工 `setTenantId`、重复拼租户 Wrapper 或绕过插件；后台任务从已校验领域对象传播 `TenantContextHolder`。authority 租户/组织/用户管理等显式目标租户或受控跨租户流程允许手工限定租户或使用 `@InterceptorIgnore(tenantLine=true)`，但必须有权限入口、可信目标租户来源和范围约束（详见契约 §5）。
- 业务 Service/Component 保持 `@Service`/`@Component`；条件或可替换基础设施 Bean 才在 `Configure` 中定义，不得把条件注解散落到业务 Service。
- 优先最小充分设计：已有领域事实、状态/阶段号、唯一索引和统一异常机制可满足时，不增双实现、命令账本、局部 Advice 或未来能力空壳。
- 不得使用不匹配 `lambda-\w*-changelog.xml` 的 Liquibase 命名（不会被加载）。
- 不得删除、改 ID 或改写基线中既有 Liquibase changeSet；结构变化只能追加新 changeSet。
- 不得在业务域包内放 `@AutoConfiguration`（统一在 `com.lambda.fusion.autoconfig`）；不得遗漏 `AutoConfiguration.imports` 注册或末尾留空行。
- 不得手动编辑 `lombok.config`（antrun 每次构建重新生成）。
- 不得硬编码密钥/Token、不得提交含真实凭据的 `.evn`。
- 不得绕过 Spotless/SpotBugs 提交（禁止 `--no-verify`）、不得 force push 主分支。

## 自动配置三件套（MUST）

每个业务模块：`com.lambda.fusion.autoconfig.XxxAutoConfiguration`（`@AutoConfiguration`，注册到 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`）+ `com.lambda.fusion.<domain>.XxxConfigure`（`@Configuration`）+ `XxxProperties`（`@ConfigurationProperties`）。条件装配和可替换基础设施优先 `@ConditionalOnClass`/`@ConditionalOnProperty`/`@ConditionalOnMissingBean`/`ObjectProvider` 并收口 `Configure`；普通业务组件继续使用 stereotype。

配置前缀：authority=`lambda.fusion.authorize`、permission=`lambda.fusion.permission`、datascope=`lambda.fusion.datascope`、config=`lambda.fusion.config`、dictionary=`lambda.fusion.dict`、datasource=`lambda.fusion.datasource`、ai=`lambda.fusion.ai`。

## 构建与提交

```bash
mvn clean install                       # 须先 install lambda-cloud-parent（parent/BOM 为 SNAPSHOT）
mvn -pl <module> -am clean install      # 单模块 + 依赖
mvn compile                             # 已绑定 Spotless(Palantir) + SpotBugs
mvn spotless:apply                      # 格式修复
mvn -pl lambda-fusion-form test         # 按模块执行测试（ai 完整套件，core/form/logger 有针对性测试）
```

提交格式（Conventional Commits）：`<type>(<scope>): <描述>`，type ∈ feat/fix/refactor/style/docs/chore/test/perf，scope 优先完整模块名（如 `lambda-fusion-authority`），描述中英文均可、祈使语气。原子提交，规则文件独立提交，禁止裸 `fix`/`refactor`。提交前 `mvn compile` 通过。

## 模块深度文档

修改模块前先读 `docs/skills/<module>/SKILL.md`（自动配置入口、配置项、关键机制、改造入口）。
