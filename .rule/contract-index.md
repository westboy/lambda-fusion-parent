# 工程契约索引（条款索引 / 关键词索引）

> 本文件是 [engineering-contract.md](engineering-contract.md) 的导航索引：A 段按主题定位条款，B 段按关键词检索。

## A. 条款索引（按主题）

- 依赖/版本治理：工程契约 §1（SNAPSHOT 前置 §1.3 / 关键三方框架升级清单 §1.4）
- 框架能力复用（禁止重复实现）：工程契约 §2
- 模块自动配置（三件套 / 前缀 / 条件装配）：工程契约 §3（前缀表 §3.2）
- 模块边界与可选依赖（优雅降级）：工程契约 §4
- 多租户（单一共享库 + 字段级隔离）：工程契约 §5
- 数据库迁移（Liquibase）：工程契约 §6
- 跨服务（Dubbo / Nacos / Sa-Token）：工程契约 §7
- 基础模型与转换（VO/DTO/Converter / 分页）：工程契约 §8
- Entity / Mapper / Service：工程契约 §9
- 代码组织与命名：工程契约 §10
- 异常与错误码：工程契约 §11
- 配置属性（@ConfigurationProperties / 敏感配置）：工程契约 §12
- 静态检查（Spotless + SpotBugs）：工程契约 §13
- 注解处理器（Lombok / MapStruct / lombok.config）：工程契约 §14
- 测试：工程契约 §15
- 安全红线：工程契约 §16
- 执行与验证：工程契约 §17
- Git 代码提交规范：工程契约 §18（格式 §18.1 / 粒度 §18.2 / 提交前检查 §18.3 / 禁令 §18.4）
- 特性开关与条件装配：工程契约 §19
- 最小充分设计与变更审计：工程契约 §20（并发与单一事实来源 §20.3）
- **标准包分层结构：package-structure.md**

## B. 关键词索引（用于检索）

- 禁止硬编码版本 / parent POM / BOM / `lambda-cloud-starter-dependencies` / `lambda-fusion-bom` / SNAPSHOT 前置 install / AgentScope 升级兼容性清单（事件序列、HITL 状态、挂起恢复、防腐层核对）
- `PageQuery<T>` / `PageView` / `AbstractCrudService<E,V,M>` / `ConverterResolver` / MapStruct / 禁止手写转换 / 禁止自定义分页模型
- `TreeBuilder` / `TreeNodeUtils` / `TreeDataFilter`
- `AuthUtils` / `UserDetails` / 禁止重复解析 Sa-Token 上下文
- `DictEnum` / `@DictMapper` / `BaseEntity` / `@EqualsAndHashCode(callSuper=true)` / `@TableName("la_*")`
- `@AutoConfiguration` / `*Configure` / `*Properties` / `AutoConfiguration.imports` / 末尾无空行 / `com.lambda.fusion.autoconfig`
- 配置前缀 / `lambda.fusion.authorize`（authority）/ `lambda.fusion.permission` / `lambda.fusion.datascope` / `lambda.fusion.config` / `lambda.fusion.dict` / `lambda.fusion.datasource` / `lambda.fusion.ai` / cloud-starter 前缀 `lambda.oss` `lambda.security` `lambda.liquibase` `lambda.sse` `lambda.cache`
- `@Service` / `@Component` / `@Bean` 边界 / `@ConditionalOnClass` / `@ConditionalOnProperty` / `@ConditionalOnMissingBean` / `ObjectProvider` / optional=true / 优雅降级 / 条件装配收口 Configure
- `tenant_id` / `TenantContextInterceptor` / `TenantContextHolder` / 单一共享库 + 字段级隔离 / `mybatis-plus.tenant.enabled=true` / 普通租户 CRUD 默认使用租户插件 / 显式目标租户 / authority 租户开通与管理 / 受控跨租户流程 / `setTenantId` 与 `@InterceptorIgnore(tenantLine=true)` 例外边界 / 后台租户上下文传播
- Liquibase / `META-INF/db/changelogs` / `lambda-*-changelog.xml` / `lambda-datasource-changelog.xml` 最先 / `lambda-additional-changelog.xml` 最后 / 命名严格匹配 / 禁止改写既有 changeSet
- Dubbo / `ServiceBean` / `ReferenceBean` / `@ConditionalOnClass` / `RemoteAuthenticationService` 继承 `StpInterface` / `AuthorityConfigure.DubboServiceConfiguration` / `TenantManager` 条件创建 / Nacos 可选
- Entity / Mapper / `BaseMapper` / `LambdaBaseMapper` / `model/` 禁业务逻辑 / Controller 禁直连 Mapper
- 包结构 / 模块根级共享 + 子域内分层 / `exception/` / `{Domain}Constants` / `controller` `mapper` `model` `service/impl` / 禁止 `commons/` 包 / 禁止旧式 `vo` `dto` `entity` 集中包
- 异常 / 模块级 `exception/` / `{Domain}BusinessException` / 错误码枚举 / 禁止裸 code
- `@ConfigurationProperties` / 嵌套静态类 / `@Validated` / 敏感配置环境变量 / `.evn`（已 gitignore）/ 禁止 `@Value` 散落 / 禁止提交真实密钥
- Spotless / Palantir Java Format / v2.67.0 / 不格式化 Javadoc / `mvn spotless:apply` / SpotBugs / compile 阶段 / `spotbugs-exclude.xml` / `spotbugs.skip` / 禁止绕过
- 注解处理器 / Lombok / MapStruct / `lombok-mapstruct-binding` / `spring-boot-configuration-processor` / `lambda-cloud-processor` / `lombok.config` antrun 生成 / 禁止手编
- 测试 / `surefire.useModulePath=false` / `mvn -pl lambda-fusion-ai test` / agent/graph/evaluator/node / `lambda-cloud-starter-test` / 禁止反射 private / 禁止无效陪跑测试
- 安全红线 / 禁止硬编码密钥 Token / `.evn` 不提交 / 禁止绕过鉴权租户隔离 / 敏感日志禁入
- 执行验证 / 冲突先澄清 / 缺失提修订 / `mvn compile` 通过 / 提交前 `mvn verify`
- Git 提交 / Conventional Commits / type（feat/fix/refactor/style/docs/chore/test/perf）/ scope 优先完整模块名 / subject 中英文 / 原子提交 / 规则文件独立提交 / 禁止裸 fix/refactor / 禁止 --no-verify / 禁止 force push 主分支
- 特性开关 / `@ConditionalOnProperty` / `matchIfMissing` / 回退装配安全降级 / Nacos `@RefreshScope` / 禁止 if 配置判断 / 禁止 feature flag 库
- 最小充分设计 / 既有事实来源 / 禁止无需求双实现 / 禁止预埋 Redis 多实例空壳 / 禁止重复 ControllerAdvice / 行锁唯一索引阶段号优先 / 自动化修改错误复盘
- 单一事实来源 / 禁止平行状态机 / 并发边界单一表达 / 锁与原子类不得并用 / TOCTOU / 锁内校验唯一权威 / 显式语义优先 / 禁止隐式顺序约定 / 禁止中转转发方法 / 禁止反向依赖 / 死代码清理 / 禁止仅测试用公开 API
- 第一性原理（宪章 §4.5，上位判据）/ 溯因与权威事实 / 最小因果与必要性 / 可证伪与验证优先 / 分解到不可再分 / 条款冲突时回到宪章 §7.5
- PDCA 闭环（宪章 §4.6，过程闭环）/ Plan 假设+验证手段 / Do 小步增量 cycle / Check 验证闭环（未验证不关闭）/ Act 偏差沉淀为规则 / 例外与迁移态回评 / 与第一性原理在「验证」挂接
- 模块深度文档 / `docs/skills/<module>/SKILL.md` / 自动配置入口 / 改造入口
- 例外/过渡条款 / 宪章 §7 登记 / `lambda-fusion-oss` 目录名 vs pom `<name>`=upload / changelog=`lambda-upload-changelog.xml` 命名分歧（待对齐）/ 仅 `lambda-fusion-ai` 有测试（待补）
