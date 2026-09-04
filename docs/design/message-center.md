# 消息中心（lambda-fusion-message）设计

> 状态：初稿（包结构 + 建表 + 分期）
> 相关阅读：`.rule/engineering-contract.md`（§3 自动配置、§5 多租户、§6 Liquibase、§9 Entity、§10 包结构）、`.rule/package-structure.md`

## 1. 背景与定位

现有体系的消息能力缺口：

- `lambda-cloud-starter-sse` 提供的是**传输通道**：内存连接、fire-and-forget、无持久化、无离线补偿，集群模式只覆写了 `broadcast`（定向发送仅限本节点）。
- authority 中 SSE 只用于在线状态判断（`UserDetailAssembler.isOnline` + 在线日志）。
- 前端铃铛通知（`basic.vue` 密码过期提醒）是纯前端假数据，没有后端消息源。

**定位**：`lambda-fusion-message` 做**消息的持久化与编排中心**——站内信、系统消息、通知公告是消息域的本体；SSE 退化为其中一个投递渠道（在线推送的"触发器"），短信、邮件、机器人（钉钉/企微/飞书 webhook）以渠道 SPI 形式后续接入。

## 2. 核心决策

### 2.1 写扩散与读扩散并存（按消息形态选择）

| 形态 | 机制 | 理由 |
| --- | --- | --- |
| 站内信 / 系统消息 / 定向通知 | **写扩散**：主表 `la_messages` + 每用户投递实例 `la_user_messages` | 收件人明确、需要独立已读/删除状态；`la_user_messages` 是"该用户已读/未读"的**单一事实来源** |
| 通知公告 | **读扩散**：`la_announcements` + 轻量已读表 `la_announcement_reads` | 全员/大范围公告预展开写扩散成本过高；范围解析在读取时进行，已读态单独记录 |

### 2.2 站内信不建投递记录表

站内渠道的投递事实就是 `la_user_messages` 本身，不再建 `la_message_deliveries` 记录站内投递（工程契约 §20.1 单一事实来源）。`la_message_deliveries` 仅在接入**外部渠道**（邮件/短信/机器人）时新建，承载各渠道独立的发送状态、重试与错误信息。

### 2.3 渠道 SPI：一条消息、多个渠道

- 核心域输出 `MessageEnvelope`（标题、内容、级别、收件人集合），渠道实现负责投递并回报结果。
- **inbox 渠道内置**：写 `la_user_messages` + 通过 SSE 推送刷新信号。SSE 通过 `ObjectProvider` 注入（optional 依赖），缺失时优雅降级为纯落库（用户下次进入页面拉取）。
- 邮件/短信/机器人渠道次期接入：厂商 SDK 用 optional 依赖，未配置凭据时渠道不装配（契约 §3.3/§19）。
- SSE 事件只作**刷新信号**，不承载消息正文：客户端收到 `notification` 事件后拉取未读数与最新消息。这样即使集群定向推送缺失（已知限制，见 §8），也只是推送不到，不丢数据。

### 2.4 SSE 依赖方向

`lambda-fusion-message → lambda-cloud-starter-sse`（optional，正向）。不在 SSE 内反向感知消息模块；authority 的在线判断不受影响。

### 2.5 外部渠道复用 cloud-starter 传输层

短信渠道直接复用 `lambda-cloud-starter-sms`（`SmsMessageSender`：阿里云/腾讯云/Mock 三实现，`lambda.sms.*` 条件装配），message 模块只提供 `SmsMessageChannel` 适配层，负责两件事：

1. **视角映射**：渠道 SPI 是内容视角 `send(envelope, targets)`，starter 是运营商视角 `sendMessage(phone, templateId, params)`——适配层把 envelope 映射为报备模板 ID + 模板参数；
2. **联系方式解析**：收件人是 username，短信/邮件需要手机号/邮箱。`MessageContactResolver` SPI 的默认实现经 `lambda-fusion-authority-api` 的 `RemoteUserService.getByUsername` 获取（`RemoteUser` 已含 `mobile`/`email` 字段，无需扩展接口）——依赖方式沿用 ai 模块先例，authority 侧经 Dubbo 暴露、客户端条件装配（契约 §7.2/§7.3），消息模块不直接依赖 authority 实现。

注意：starter 中若 `lambda.sms.aliyun.enabled` 与 `lambda.sms.tencent.enabled` 同时为 true，会注册两个 `SmsMessageSender` Bean——适配层经 `ObjectProvider.stream()` 按 `isp()`/配置挑选，不做按类型裸注入。

## 3. 包结构

遵循「模块根级共享 + 子域内分层」（`.rule/package-structure.md`），基础包 `com.lambda.fusion.message`：

```
com.lambda.fusion.message
├── MessageConstants                    模块常量：渠道标识、SSE 事件名、分类/级别枚举约定
├── exception/
│   ├── MessageBusinessException        模块级业务异常
│   └── MessageErrorCode                错误码枚举（禁止裸 code）
├── channel/                            渠道层：SPI 与内置渠道
│   ├── MessageChannel                  SPI 接口：渠道标识 + send(envelope, targets)
│   ├── MessageChannelRegistry          渠道注册与按渠道路由（收集所有 MessageChannel Bean）
│   └── inbox/
│       └── InboxMessageChannel         站内渠道：写 la_user_messages + SSE 推送（@Component）
├── dispatch/                           发送编排子域
│   ├── service/
│   │   ├── MessageDispatchService      发送入口：渲染 → 解析收件人 → 渠道分发
│   │   └── impl/MessageDispatchServiceImpl
│   ├── resolver/
│   │   ├── MessageRecipientResolver    SPI：按范围解析收件人（scopeType → 用户名集合）
│   │   ├── MessageContactResolver      SPI：username → 手机号/邮箱（默认实现经 authority-api RemoteUserService，次期）
│   │   └── impl/                       AllRecipientResolver / RoleRecipientResolver /
│   │                                   OrgRecipientResolver / UserRecipientResolver
│   └── model/                          MessageSendCommand、MessageEnvelope 等
├── notification/                       站内信子域（消息 + 用户消息）
│   ├── controller/
│   │   ├── MessageController           管理端：发送系统消息、查询、撤回
│   │   └── MyMessageController         用户侧：我的消息分页、未读数、已读、删除
│   ├── service/ + impl/
│   │   ├── MessageService              消息主表（继承 AbstractCrudService）
│   │   └── UserMessageService          投递实例：标记已读、未读数、用户侧隐藏
│   ├── mapper/                         MessageMapper、UserMessageMapper
│   └── model/
│       ├── entity/                     MessageEntity、UserMessageEntity
│       ├── query/                      MessageQuery、MyMessageQuery（继承 PageQuery）
│       └── vo/                         Message、MyMessage 等（无后缀 VO）
├── announcement/                       通知公告子域（读扩散）
│   ├── controller/
│   │   ├── AnnouncementController      管理端：公告 CRUD、发布/撤回
│   │   └── MyAnnouncementController    用户侧：生效中公告分页、已读标记
│   ├── service/ + impl/                AnnouncementService、AnnouncementReadService
│   ├── mapper/                         AnnouncementMapper、AnnouncementReadMapper
│   └── model/                          entity/ query/ vo/（同上分层）
└── listener/                           按需（如登录后补拉事件），本期不建

com.lambda.fusion.autoconfig
└── MessageAutoConfiguration            @AutoConfiguration 入口，注册到 AutoConfiguration.imports
com.lambda.fusion.message
├── MessageConfigure                    @Configuration：条件装配渠道 Bean、SSE 可选注入
└── MessageProperties                   @ConfigurationProperties(prefix = "lambda.fusion.message")
```

要点：

- 横切能力归属子域，不建 `commons/`、不建顶层 `vo/`/`entity/` 集中包（契约 §10.3）。
- `resolver/`、`listener/`、`assembler/` 为按需目录，无对应职责时不建空目录。
- 站内信与公告是两个子域而非一个：公告有独立的发布生命周期（草稿/发布/撤回）与生效时间窗，读写模型与站内信不同；合并会造出 `category` 判断分支遍布两套语义。

## 4. 自动配置与依赖

### 4.1 三件套

| 件 | 内容 |
| --- | --- |
| `MessageAutoConfiguration` | `com.lambda.fusion.autoconfig`，注册 `META-INF/spring/...AutoConfiguration.imports`（末尾无空行） |
| `MessageConfigure` | `@Configuration`；`MessageChannelRegistry`、inbox 渠道等条件 Bean 收口在此；整体开关 `@ConditionalOnProperty(prefix="lambda.fusion.message", name="enabled", matchIfMissing=true)` |
| `MessageProperties` | 前缀 `lambda.fusion.message`：`enabled`、`push.enabled`（SSE 推送开关）、`announcement.maxPageSize` 等 |

### 4.2 pom 依赖（初稿）

```xml
<dependencies>
    <dependency>
        <groupId>com.lambda.fusion</groupId>
        <artifactId>lambda-fusion-core</artifactId>          <!-- AbstractCrudService/PageQuery/AuthUtils -->
    </dependency>
    <dependency>
        <groupId>com.lambda.cloud</groupId>
        <artifactId>lambda-cloud-starter-liquibase</artifactId>
    </dependency>
    <dependency>
        <groupId>com.lambda.cloud</groupId>
        <artifactId>lambda-cloud-starter-sse</artifactId>
        <optional>true</optional>                             <!-- 推送是增强，缺失时降级为纯落库 -->
    </dependency>
    <dependency>
        <groupId>com.lambda.fusion</groupId>
        <artifactId>lambda-fusion-authority-api</artifactId>  <!-- 联系方式解析（RemoteUser.mobile/email），P2 起用 -->
    </dependency>
</dependencies>
```

- 不引入 redis/cache：未读数走 `(RECEIVER, IS_READ, IS_DELETED)` 索引的 count 查询，本期量级不需要缓存（§20.1 不预埋）。
- 次期短信渠道复用 `lambda-cloud-starter-sms`（optional 依赖，未配置 `lambda.sms.*` 时 Mock/不装配）；邮件渠道厂商 SDK 一律 optional + 未配置不装配。

### 4.3 Liquibase

新增 `lambda-fusion-message/src/main/resources/META-INF/db/changelogs/lambda-message-changelog.xml`（命名必须匹配 `lambda-\w*-changelog.xml`）；每个 changeSet 带 `<preConditions onFail="MARK_RAN"><not><tableExists .../></not></preConditions>`，与 authority 现有写法一致。同步维护 `docs/sql/la_message-*.sql`（契约 §6.2）。

## 5. 建表初稿（本期 4 张）

通用约定（对齐 authority 既有表）：主键 `ID varchar(32)`（雪花串）；列名全大写；审计四件套 `CREATED_BY varchar(64)` / `CREATED_AT datetime` / `UPDATED_BY varchar(64)` / `UPDATED_AT datetime`；`ENABLED int`（0/1）。实体继承 `BaseEntity`（自动填充审计字段）；`tenant_id` 由 MyBatis 租户插件按契约 §5.1 填充与隔离，业务代码不手工设置。

### 5.1 la_messages — 消息主表（写扩散源）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| ID | varchar(32) PK | |
| TENANT_ID | varchar(32) | 租户；NULL 表示平台级消息 |
| CATEGORY | varchar(24) | 消息分类：`system` 系统消息 / `user` 用户消息 / `biz` 业务通知（DictEnum，可扩展） |
| LEVEL | varchar(16) | 级别：`info` / `important` / `urgent` |
| TITLE | varchar(255) | 标题，NOT NULL |
| CONTENT | text | 正文 |
| CONTENT_TYPE | varchar(16) | `text` / `html` / `markdown`，默认 `text` |
| SENDER | varchar(64) | 发送者 username；系统消息为 `system` |
| BIZ_TYPE | varchar(64) | 来源业务类型（如 `password-expiry`、`approval`），可空 |
| BIZ_ID | varchar(64) | 来源业务单据 ID，可空；与 BIZ_TYPE 组合作业务幂等键 |
| REMARKS | varchar(280) | 备注 |
| 审计四件套 | | |

索引：`idx_msg_tenant_category (TENANT_ID, CATEGORY, CREATED_AT)`、`uk_msg_biz (BIZ_TYPE, BIZ_ID)`（可空列的普通索引，业务层负责幂等判断）、`idx_msg_sender (SENDER)`。

### 5.2 la_user_messages — 用户消息实例表（写扩散投递态）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| ID | varchar(32) PK | |
| MESSAGE_ID | varchar(32) NOT NULL | 关联 la_messages.ID |
| TENANT_ID | varchar(32) | 与主表一致，冗余以支持按收件人直接隔离查询 |
| RECEIVER | varchar(64) NOT NULL | 收件人 username |
| IS_READ | int | 0 未读 / 1 已读，默认 0 |
| READ_TIME | datetime | 首次已读时间 |
| IS_DELETED | int | 用户侧删除（隐藏），默认 0；物理清理由后续归档策略处理 |
| 审计四件套 | | |

索引：`uk_um_message_receiver (MESSAGE_ID, RECEIVER)`（防重复投递，幂等保障）、`idx_um_receiver_read (RECEIVER, IS_READ, IS_DELETED)`（未读数与我的消息查询主索引）。

### 5.3 la_announcements — 通知公告表（读扩散）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| ID | varchar(32) PK | |
| TENANT_ID | varchar(32) | NULL 表示平台级公告 |
| TITLE | varchar(255) NOT NULL | |
| CONTENT | text | |
| CONTENT_TYPE | varchar(16) | 同消息主表 |
| SCOPE_TYPE | varchar(24) | 范围：`all` / `org` / `role` / `user` |
| SCOPE_TARGETS | text | 范围目标 JSON 数组（组织 ID / 角色 ID / username 列表）；`all` 时为 NULL |
| PUBLISH_STATUS | int | 0 草稿 / 1 已发布 / 2 已撤回，默认 0 |
| PUBLISH_TIME | datetime | 发布时间 |
| EFFECTIVE_FROM | datetime | 生效开始，可空（发布即生效） |
| EFFECTIVE_TO | datetime | 生效结束，可空（长期有效） |
| IS_TOP | int | 置顶 0/1，默认 0 |
| ORDER_NO | int | 排序号 |
| ENABLED | int | 默认 1 |
| REMARKS | varchar(280) | |
| 审计四件套 | | |

索引：`idx_ann_tenant_status (TENANT_ID, PUBLISH_STATUS, PUBLISH_TIME)`、`idx_ann_effective (EFFECTIVE_FROM, EFFECTIVE_TO)`。

> 可见性判定（读取时解析，不落投递表）：`PUBLISH_STATUS=1` 且当前时间在生效窗内，且租户匹配（TENANT_ID 为 NULL 或等于当前租户），且范围匹配（`all` 或当前用户命中 SCOPE_TARGETS 解析结果）。范围解析复用 `dispatch/resolver` 的 `MessageRecipientResolver` SPI，避免两套解析逻辑。

### 5.4 la_announcement_reads — 公告已读表

| 列 | 类型 | 说明 |
| --- | --- | --- |
| ID | varchar(32) PK | |
| ANNOUNCEMENT_ID | varchar(32) NOT NULL | 关联 la_announcements.ID |
| TENANT_ID | varchar(32) | |
| READER | varchar(64) NOT NULL | 阅读人 username |
| READ_TIME | datetime | |
| 审计四件套 | | CREATED_AT 之外的更新字段实际不再变化，保留以统一继承 BaseEntity |

约束：`uk_ar_announcement_reader (ANNOUNCEMENT_ID, READER)`。

### 5.5 次期表（本期不建，仅方向）

- `la_message_deliveries`：外部渠道（email/sms/bot/webhook）投递记录——MESSAGE_ID、CHANNEL、TARGET、STATUS、RETRY_COUNT、LAST_ERROR、SENT_AT、TEMPLATE_CODE/TEMPLATE_PARAMS。接入首个外部渠道时随该渠道一起设计定稿。
- `la_message_templates`：模板与变量渲染，随模板功能需求定稿。

## 6. API 初稿

### 6.1 用户侧（登录用户）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/message/my` | 我的消息分页（`MyMessageQuery extends PageQuery`，按 RECEIVER=当前用户 + 租户插件隔离） |
| GET | `/message/my/unread-count` | 未读数（铃铛红点） |
| POST | `/message/my/read` | 批量标记已读 `{ids: [...]}` |
| POST | `/message/my/read-all` | 全部已读 |
| DELETE | `/message/my/{id}` | 用户侧删除（IS_DELETED=1） |
| GET | `/announcement/my` | 生效中公告分页（含 IS_TOP/ORDER_NO 排序） |
| POST | `/announcement/my/{id}/read` | 标记公告已读 |

### 6.2 管理端（有权限入口）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/message/send` | 发送系统消息（MessageSendCommand：category/level/title/content/收件人范围）→ DispatchService 编排 |
| GET | `/message` | 消息管理分页 |
| GET/POST/PUT/DELETE | `/announcement` | 公告 CRUD |
| POST | `/announcement/{id}/publish` / `/revoke` | 发布 / 撤回 |

Controller 只编排 Service，分页收敛到 Service（契约 §8.3）；转换走 `ConverterResolver`。

## 7. 前端联动（_lambda_fusion_web）

- 铃铛从假数据改为真实数据：登录后调 `/message/my/unread-count` 初始化红点；`setupSseAutoConnect` 已建立的 SSE 连接上监听 `notification` 事件（事件名入 `MessageConstants`），收到即重新拉取未读数与消息列表（推拉结合，正文不走路）。
- 密码过期提醒（`basic.vue`）保留为前端本地通知，不迁移到消息中心——它是 userinfo 派生的会话级提醒，落库反而制造平行事实。
- 公告：新增公告列表页/详情弹窗（可按 IS_TOP 弹强提醒，细节前端再定）。

## 8. 已知限制与后续

1. **集群定向推送缺失**：`ClusterSseEmitterManager` 只覆写 `broadcast`，跨节点 `sendEvent` 到不了目标用户。本期靠"推拉结合"兜底（漏推由拉取补偿）；若后续要求强实时，需在 starter-sse 增加 RTopic 定向路由（独立改造，不在本模块内做）。
2. **离线补偿**：用户上线时前端拉取即视为补偿；不实现 Last-Event-ID 回放（服务端本就未下发 event id）。
3. **归档策略**：`la_user_messages` 只做软删，物理清理与 `la_messages` 的归档策略（TTL 或按量归档）待消息量上来后单独立项。
4. **外部渠道**：邮件 → 短信（复用 `lambda-cloud-starter-sms` + `SmsMessageChannel` 适配层，见 §2.5）→ 机器人（webhook 型）按此顺序接入；机器人若需"接收消息"（反向），收件人模型预留 `receiverType` 的决策放到接入时再定（§20.1 不预埋）。

## 9. 分期实施

| 阶段 | 内容 |
| --- | --- |
| P1（本期） | 模块骨架（三件套 + pom）；`lambda-message-changelog.xml` 建 4 张表；notification + announcement 两子域 CRUD 与用户侧接口；inbox 渠道 + SSE 推送；前端铃铛与公告页 |
| P2 | 消息模板（表 + 渲染）；`la_message_deliveries` 与邮件渠道；短信渠道（`lambda-cloud-starter-sms` + `SmsMessageChannel` + `MessageContactResolver`，传输层现成、仅需适配） |
| P3 | 机器人 webhook 渠道；归档策略；（视需要）starter-sse 集群定向路由 |
