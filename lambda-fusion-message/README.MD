# Lambda Fusion Message

Lambda Fusion Message 是 Lambda Fusion 框架中的消息中心模块，基于 Lambda Cloud 构建，提供 **站内信、通知公告、消息模板、多渠道投递** 的一体化消息能力，覆盖「发消息 → 触达用户 → 外部渠道投递」的完整链路。

## 模块概述

本项目是 Lambda Fusion 框架的消息中心模块，专为企业级微服务应用设计，提供统一的消息发送编排、用户消息管理、公告发布与外部渠道投递能力。作为 Lambda Cloud 生态的一部分，与权限（authority）、配置（config）等模块无缝集成，通过插件式渠道架构支持站内、邮件、短信、群机器人等多种触达方式。

### 核心特性

- **站内信**：消息主表 + 用户消息实例（已读/删除），支持系统/用户/业务三类消息与 info/important/urgent 三级
- **通知公告**：全员 / 指定用户 / 指定组织 / 指定角色四种可见范围，支持草稿、发布、撤回、置顶、生效时间窗
- **消息模板**：标题/正文模板 + `${var}` 占位符渲染，业务幂等编码，渠道附加参数
- **多渠道投递**：站内（inbox）/ 邮件（email）/ 短信（sms）/ 群机器人 webhook（bot）统一渠道 SPI
- **投递记录**：外部渠道投递落 `la_message_deliveries`，支持失败重试
- **SSE 推送**：站内投递后向在线用户推送刷新信号；SSE 缺失时自动降级为纯落库
- **远程发送**：经 Dubbo 暴露 `RemoteMessageService`，供 ai 等其他服务跨服务发消息
- **多租户支持**：基于租户ID的消息数据隔离
- **插件式渠道架构**：实现 `MessageChannel` SPI 即可接入新渠道

## 技术栈

### 核心技术
- **Java 21+**
- **Spring Boot 4.x**
- **MyBatis Plus** - ORM 框架
- **Lambda Cloud** - 基础框架依赖

### 数据存储
- **MySQL/PostgreSQL** - 关系型数据库
- **Redis** - 缓存（经 core 集成）
- **Liquibase** - 数据库版本管理

### Lambda Cloud Starter 集成
- **lambda-fusion-core** - 框架核心库
- **lambda-cloud-starter-security** - 安全框架
- **lambda-cloud-starter-mybatis** - MyBatis 集成
- **lambda-cloud-starter-datasource** - 数据源管理
- **lambda-cloud-starter-logger** - 日志框架
- **lambda-cloud-starter-liquibase** - 数据库版本管理
- **lambda-cloud-starter-sse** - SSE 推送（可选）
- **lambda-cloud-starter-sms** - 短信发送（可选）
- **lambda-cloud-starter-dubbo** - Dubbo RPC 框架（可选）

### 外部依赖
- **spring-boot-starter-mail** - 邮件发送（可选）
- **lambda-fusion-authority-api** - 远程用户/组织/角色解析（可选）
- **lambda-fusion-message-api** - 消息远程发送契约

## 项目结构

```
lambda-fusion-message/
├── src/main/java/com/lambda/fusion/
│   ├── autoconfig/
│   │   └── MessageAutoConfiguration.java     # Spring Boot 自动配置入口
│   └── message/
│       ├── MessageConfigure.java             # 模块装配类（渠道按需装配）
│       ├── MessageProperties.java            # 配置属性绑定
│       ├── MessageConstants.java             # 常量定义
│       ├── notification/                     # 站内信
│       │   ├── controller/                   # MessageController / MyMessageController
│       │   ├── service/                      # 消息与用户消息服务
│       │   ├── mapper/                       # MessageMapper / UserMessageMapper
│       │   └── model/                        # MessageEntity / UserMessageEntity / VO
│       ├── announcement/                     # 通知公告
│       │   ├── controller/                   # AnnouncementController / MyAnnouncementController
│       │   ├── service/                      # 公告与已读服务
│       │   ├── mapper/                       # AnnouncementMapper / AnnouncementReadMapper
│       │   └── model/                        # AnnouncementEntity / AnnouncementReadEntity / VO
│       ├── template/                         # 消息模板
│       │   ├── controller/                   # MessageTemplateController
│       │   ├── service/                      # 模板服务（${var} 渲染）
│       │   ├── mapper/                       # MessageTemplateMapper
│       │   └── model/                        # MessageTemplateEntity / VO
│       ├── dispatch/                         # 发送编排与投递
│       │   ├── controller/                   # MessageDeliveryController
│       │   ├── service/                      # MessageDispatchService / MessageDeliveryService
│       │   ├── resolver/                     # 收件人/联系方式解析器
│       │   ├── adapter/                      # RemoteMessageServiceAdapter
│       │   └── model/                        # MessageSendCommand / MessageEnvelope / 投递实体
│       ├── channel/                          # 渠道 SPI 与实现
│       │   ├── MessageChannel.java           # 渠道 SPI 接口
│       │   ├── MessageChannelRegistry.java   # 渠道注册中心
│       │   ├── inbox/                        # 站内渠道 + SSE 通知器
│       │   ├── email/                        # 邮件渠道
│       │   ├── sms/                          # 短信渠道
│       │   └── bot/                          # 群机器人 webhook 渠道
│       └── exception/                        # MessageBusinessException / MessageErrorCode
└── src/main/resources/
    └── META-INF/
        ├── spring/...AutoConfiguration.imports  # 自动配置注册
        └── db/changelogs/
            └── lambda-message-changelog.xml     # Liquibase 数据库迁移 + 菜单初始化
```

## 核心功能详解

### 1. 站内信 (Notification)

**主要功能：**
- 系统/用户/业务三类消息，info/important/urgent 三级
- 消息主表 + 用户消息实例分离（一条消息、多个收件人）
- 已读/批量已读/全部已读/删除
- 未读数实时统计

**核心类：**
- `MessageService` / `UserMessageService` - 站内信业务逻辑
- `MessageController` / `MyMessageController` - 管理端/用户侧 API
- `MessageEntity` / `UserMessageEntity` - 消息与用户消息实例实体

### 2. 通知公告 (Announcement)

**主要功能：**
- 四种可见范围：all 全员 / user 指定用户 / org 指定组织 / role 指定角色
- 发布状态机：草稿 → 已发布 → 已撤回
- 生效时间窗（`effectiveFrom` / `effectiveTo`），空表示发布即生效/长期有效
- 置顶与排序
- 组织/角色范围经 authority-api 远程解析为具体用户

**核心类：**
- `AnnouncementService` / `AnnouncementReadService` - 公告业务逻辑
- `AnnouncementController` / `MyAnnouncementController` - 管理端/用户侧 API

### 3. 消息模板 (Template)

**主要功能：**
- 标题/正文模板 + `${var}` 占位符渲染
- 模板编码（`code`）作为业务幂等标识
- 渠道附加参数 JSON（如短信 templateId）
- 启用/停用状态控制

**核心类：**
- `MessageTemplateService` - 模板渲染与业务逻辑
- `MessageTemplateController` - 模板管理 API

### 4. 多渠道投递 (Channel & Dispatch)

**支持的渠道类型：**

#### 站内渠道 (inbox)
- 默认渠道，始终可用
- 落 `la_user_messages` 用户消息实例
- 投递后经 SSE 推送刷新信号（事件名 `notification`）
- SSE 缺失时降级为纯落库，用户进页面拉取补偿

#### 邮件渠道 (email)
- 需 classpath 存在 `spring-boot-starter-mail` 且 `email.enabled=true`
- 收件人邮箱经 `MessageContactResolver` 由 authority-api 远程解析
- 投递结果写 `la_message_deliveries`

#### 短信渠道 (sms)
- 需 classpath 存在 `lambda-cloud-starter-sms` 且 `sms.enabled=true`
- 收件人手机号远程解析，支持多运营商按 `isp` 挑选
- 投递结果写 `la_message_deliveries`

#### 群机器人渠道 (bot)
- 支持 DINGTALK / WECOM / FEISHU（协议互不兼容）
- 配置一个或多个群机器人 webhook 地址
- 投递结果写 `la_message_deliveries`（地址脱敏）

**核心类：**
- `MessageChannel` - 渠道 SPI 接口（插件架构）
- `MessageChannelRegistry` - 渠道注册中心
- `MessageDispatchService` - 发送编排（落库 → 渠道路由 → 投递）
- `MessageDeliveryService` / `MessageDeliveryRetryService` - 投递记录与重试

### 5. 远程消息发送 (Remote)

**主要功能：**
- 经 Dubbo 暴露 `RemoteMessageService`，供其他服务（如 ai）跨服务发消息
- 显式携带 `tenantId` 与 `sender`：后台任务（如定时任务）无登录上下文，发送人由调用方传入
- `tenantId` 可空：无租户上下文时按无租户/平台级消息投递
- `existsRecent` 判重：支持「N 天一次」类提醒去重（如密码过期 7 天一条）

**核心类：**
- `RemoteMessageService` - 远程发送契约（message-api）
- `RemoteMessageServiceAdapter` - 远程调用适配器
- `RemoteMessageCommand` - 跨服务传输对象

> 其他服务**不得直接引用本模块实现类**，统一经 `message-api` 远程调用（对齐 authority-api 的 `RemoteUserService` 模式）。

## API 接口

### 消息管理 API（`/message`，管理端）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/message` | 消息管理分页 |
| POST | `/message/send` | 发送系统消息 |

### 我的消息 API（`/message/my`，用户侧）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/message/my` | 我的消息分页 |
| GET | `/message/my/unread-count` | 未读数 |
| POST | `/message/my/read` | 批量标记已读 |
| POST | `/message/my/read-all` | 全部标记已读 |
| DELETE | `/message/my/{id}` | 删除我的消息 |

### 公告管理 API（`/announcement`，管理端）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/announcement` | 公告管理分页 |
| POST | `/announcement` | 创建公告 |
| PUT | `/announcement/{id}` | 更新公告 |
| DELETE | `/announcement/{id}` | 删除公告 |
| POST | `/announcement/{id}/publish` | 发布公告 |
| POST | `/announcement/{id}/revoke` | 撤回公告 |

### 我的公告 API（`/announcement/my`，用户侧）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/announcement/my` | 我的公告分页（生效中且范围命中） |
| GET | `/announcement/my/unread-count` | 我的公告未读数 |
| POST | `/announcement/my/{id}/read` | 标记公告已读 |
| POST | `/announcement/my/read-all` | 全部公告标记已读 |

### 消息模板 API（`/message/template`，管理端）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/message/template` | 模板管理分页 |
| POST | `/message/template` | 创建模板 |
| PUT | `/message/template/{id}` | 更新模板 |
| DELETE | `/message/template/{id}` | 删除模板 |

### 投递记录 API（`/message/delivery`，管理端）

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/message/delivery` | 投递记录分页 |
| POST | `/message/delivery/{id}/retry` | 重试失败的投递记录 |

## 配置说明

前缀 `lambda.fusion.message`，全部可省略使用默认值：

```yaml
lambda:
  fusion:
    message:
      # 是否启用模块（默认 true）
      enabled: true
      # 是否启用 SSE 推送（站内投递后推送刷新信号）；关闭或 SSE 缺失时降级为纯落库
      push-enabled: true
      # 邮件渠道（需同时配置 spring.mail.*）
      email:
        enabled: false
        from:           # 发件人显示名，空则用 SMTP 默认
      # 短信渠道（需同时配置 lambda.sms.*）
      sms:
        enabled: false
        isp:            # 运营商 ALIYUN/TENCENT/MOCK，空取容器内首个
      # 群机器人 webhook 渠道
      bot:
        enabled: false
        type: DINGTALK  # DINGTALK / WECOM / FEISHU
        webhooks: []    # 群机器人地址列表（含 token）
```

渠道均为**按需装配**：只有对应 starter 在 classpath 且开关开启时才创建渠道 Bean，缺失时自动降级，不影响站内信主流程。

## 使用说明

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-message</artifactId>
</dependency>
```

### 2. 启用框架

模块使用 Spring Boot 自动配置机制，只需添加依赖即可自动启用，无需额外注解：

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

模块通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动加载 `MessageAutoConfiguration`。

### 3. 配置数据库

确保数据库连接配置正确，Liquibase 会自动创建所需的表结构（`la_messages` 等 6 张表）及管理端菜单。

## 开发指南

### 服务内发送消息

管理端 / 服务内发送统一走 `MessageDispatchService`：

```java
MessageSendCommand command = new MessageSendCommand();
command.setTitle("系统升级通知");
command.setContent("今晚 22:00 停机维护");
command.setReceivers(List.of("13303820660"));
command.setChannels(List.of("inbox", "sms"));   // 空则仅站内信
command.setCategory("system");
command.setLevel("important");
Message message = messageDispatchService.send(command);
```

发送流程：落库消息主表 → 按渠道路由投递 → 站内渠道写用户消息实例并 SSE 推送，外部渠道写投递记录。

### 跨服务发送消息（Dubbo）

其他服务（如 ai）经 `message-api` 的 `RemoteMessageService` 远程调用：

```java
@DubboReference(check = false)
private RemoteMessageService remoteMessageService;

RemoteMessageCommand command = new RemoteMessageCommand();
command.setSender("13303820660");              // 后台任务须显式传入发送人
command.setReceivers(List.of("13303820660"));
command.setTitle("密码即将过期");
command.setBizType("PASSWORD_EXPIRE");
command.setBizId(userId);

remoteMessageService.send(command);                              // 发送
boolean sent = remoteMessageService.existsRecent(
        "PASSWORD_EXPIRE", userId, 7);                           // 近 7 天是否已发（用于「N 天一次」去重）
```

### 使用消息模板

```java
// 1. 创建模板（管理端或 API）
POST /message/template
{
  "code": "PASSWORD_EXPIRE",
  "name": "密码过期提醒",
  "title": "密码将于 ${days} 天后过期",
  "content": "请尽快修改密码，剩余 ${days} 天",
  "contentType": "text"
}

// 2. 发送时指定模板编码，标题/正文按模板渲染
command.setTemplateCode("PASSWORD_EXPIRE");
command.setTemplateParams(Map.of("days", 7));
```

### 自定义消息渠道

实现 `MessageChannel` SPI 即可接入新渠道（一条消息、多个渠道）：

```java
@Component
public class WebhookMessageChannel implements MessageChannel {

    @Override
    public String channel() {
        return "webhook";
    }

    @Override
    public void send(MessageEnvelope envelope, Collection<String> receivers) {
        // 自定义投递逻辑，外部渠道经 la_message_deliveries 落记录
    }

    @Override
    public boolean retry(MessageDeliveryEntity delivery) {
        // 失败重试（站内渠道不支持）
        return false;
    }
}
```

### 发布公告

```java
// 1. 创建公告（草稿）
POST /announcement
{
  "title": "版本更新公告",
  "content": "V2.0 已上线",
  "scopeType": "all",          // all/user/org/role
  "isTop": 1
}

// 2. 发布（全员可见并 SSE 广播刷新未读数）
POST /announcement/{id}/publish
```

## 数据表

| 表 | 说明 |
|----|------|
| `la_messages` | 消息主表 |
| `la_user_messages` | 用户消息实例表（收件人维度，已读/删除状态） |
| `la_announcements` | 通知公告表 |
| `la_announcement_reads` | 公告已读记录表 |
| `la_message_templates` | 消息模板表 |
| `la_message_deliveries` | 外部渠道投递记录表 |

## 工作原理

### 消息发送流程

```
MessageDispatchService.send(command)
    ↓
模板渲染（templateCode 存在时）
    ↓
落库 la_messages 消息主表
    ↓
按 channels 路由到各 MessageChannel
    ↓
┌─ inbox：写 la_user_messages + SSE 推送（事件 notification）
├─ email/sms/bot：解析联系方式 → 投递 → 写 la_message_deliveries
    ↓
返回消息 VO
```

### SSE 推送降级

1. **推送优先**：站内投递后向在线用户推送 `notification` 事件，客户端收到后重新拉取未读数与消息列表（正文不走路由）
2. **自动降级**：SSE starter 缺失或 `push-enabled=false` 时降级为纯落库
3. **拉取补偿**：用户进入页面时主动拉取，保证不丢消息

### 公告范围解析

1. **全员（all）**：当前租户内所有用户
2. **指定用户（user）**：直接按用户名列表
3. **指定组织（org）/ 指定角色（role）**：经 authority-api `RemoteUserService` 远程解析为具体用户集合
4. **已读追踪**：`la_announcement_reads` 记录阅读人，未读数按范围命中的生效公告计算

### 渠道按需装配

各渠道 Bean 通过 `@ConditionalOnClass` + `@ConditionalOnProperty` 双重条件装配：classpath 存在对应 starter 且开关开启才创建。联系方式解析器（手机号/邮箱）仅在 authority-api 存在时装配，经 Dubbo `RemoteUserService` 解析，authority 未就绪时由解析器在实际调用阶段降级处理。

## 前端菜单

管理端菜单（消息管理 / 公告管理 / 消息模板 / 投递记录，顶级目录「消息管理」与系统管理平级）由 `META-INF/db/changelogs/lambda-message-changelog.xml` 中的 changeSet 自动初始化，并授权给 `ROLE_ADMIN` / `ROLE_SYSTEM` / `ROLE_DEV`。配套前端页面见 `_web/packages/system-ui/src/views/system/message/`。
