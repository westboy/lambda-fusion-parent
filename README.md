<div align="center">

<p align="center">
	<img alt="logo" src="assets/logo.png" width="250" height="250">
</p>

# 🚀 Lambda Fusion Framework

**企业级基础能力 × AI 智能平台，开箱即用的全栈微服务开发框架**

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2025.1.x-brightblue.svg)](https://spring.io/projects/spring-cloud)
[![JDK](https://img.shields.io/badge/JDK-21+-orange.svg)](https://www.oracle.com/java/technologies/javase-downloads.html)
[![AgentScope](https://img.shields.io/badge/AgentScope-2.x-ff69b4.svg)](https://github.com/agentscope-ai/agentscope)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

</div>

---

## 📋 项目简介

Lambda Fusion 是一套「企业级基础能力 + AI 智能平台」的全栈微服务开发框架，基于 Spring Boot 4.x / Spring Cloud 2025.1 / JDK 21 构建，AI 能力由 **AgentScope 2.x** 多智能体运行时驱动。

采用 **契约驱动** 设计，边界清晰，天然适合 **AI Coding**。

在 [lambda-cloud-parent](https://gitee.com/westboy/lambda-cloud-parent) 基座之上，框架将两条主线能力统一沉淀为开箱即用的 `lambda-fusion-*` starter：

- 🏢 **企业级基础能力**：权限、多租户、配置、消息、字典、数据源、附件、日志、动态表单、工作流
- 🤖 **大模型应用开发**：智能体、知识库 RAG、LLM 管理、子代理、技能市场、MCP、通道网关


## 🏢 企业级基础能力

业务系统的生产级底座，覆盖后台管理的高频刚需：

| 能力 | 说明                                                               |
|------|------------------------------------------------------------------|
| 🔐 **权限体系** | 基于 Sa-Token 的完整 RBAC：用户、角色、组织、资源（菜单/按钮/API）统一管理，客户端应用鉴权、SSO 单点登录 |
| 🏠 **多租户** | 轻量 tenant_id 字段级隔离，天然复用 RBAC 能力                                 |
| 🛡️ **细粒度授权** | server / client 双端 API 权限元数据统一注册与校验，叠加数据权限授权树，实现接口级 + 数据行级双重鉴权   |
| ⚙️ **动态配置** | 数据库配置源统一托管，支持本地热更新与 Nacos 配置中心同步发布，配置变更免重启生效                     |
| 📚 **数据字典** | 静态枚举与动态字典双模式，枚举类扫描自动注册入库，字典翻译开箱即用                                |
| 🗄️ **动态数据源** | server / client 多数据源统一注册与管理，基于 Dubbo 的服务间数据源分发，多库接入对业务透明         |
| 📤 **附件管理** | 统一封装七牛云 / S3 兼容对象存储，文件上传、访问控制等能力开箱即用                             |
| 📨 **消息中心** | 站内信、通知公告、消息模板、多渠道投递（站内/邮件/短信/群机器人），支持 SSE 推送与 Dubbo 跨服务发送 |
| 📝 **操作日志** | `@OperationLog` 注解采集 + 异步落库 + 多存储后端（内置 MySQL），分布式部署经 Dubbo 统一落库与查询 |
| 🧾 **动态表单** | 可视化设计器（基础与数据 / 表单与详情 / 列表设计三步向导）+ 零代码动态 CRUD；单表 / 树表 / 关联表，主从支持内联 / 台账 / 扁平三种呈现；发布快照不可变、支持版本回滚；目标表可绑定平台主库或动态数据源 |
| 🔁 **工作流** | 基于 warm-flow 引擎的审批能力装配层：流程定义管理 + 审批操作 REST 出口 + 单据集成 SPI（关联与状态回写），带登录态与租户隔离，动态表单可挂载为流程单据 |

## 🤖 AI应用开发能力

 基于 **AgentScope 2.x** 构建，覆盖 **模型接入 → 知识增强 → Agent 编排 → 通道落地** 的完整链路：

### 🧠 智能应用

| 形态 | 说明 |
|------|------|
| 💬 **CHAT 型** | 轻量对话应用，SSE 流式输出，开箱即用 |
| 🕹️ **AGENTIC 型** | 对齐 AgentScope harness 完整能力：workspace / 技能 / 子代理 / 记忆 / 沙箱；`selfEvolve` 进一步开启文件工具与自演化 |

### 🧰 核心能力

| 能力              | 说明 |
|-------------------|------|
| ♻️ **自演化**     | 应用在运行过程中持续自我优化演进，每轮对话后审计 workspace 变更 |
| 🖥️ **多沙箱后端** | 代码与工具执行环境隔离，支持 HOST / Docker / K8s / E2B / Daytona / AgentRun 按需切换 |
| 📚 **知识库 RAG** | 支持 Lucene / OceanBase 全文、内存 / pgvector / OceanBase 向量及 RRF 混合召回；FULL_TEXT 模式无需嵌入模型，应用侧提供 `GENERIC` / `AGENTIC` / `BOTH` 三种注入模式 |
| 🧠 **LLM 管理**   | 大模型统一接入与集中管理（CHAT / EMBEDDING / 图像 / 视频 / 语音），API Key 加密存储 |
| 🧩 **子代理**     | DB 驱动的子代理体系，定义持久化、运行可编排 |
| 🛠️ **技能市场**   | 技能注册、沉淀与复用（MYSQL / POSTGRES / GIT / NACOS 仓库源 + 技能包导入），Agent 能力可插拔扩展 |
| 🔌 **MCP 接入**   | 原生支持 Model Context Protocol，接入开放的 MCP 工具生态 |
| 📨 **通道网关**   | 钉钉 / 飞书 / 企业微信通道适配，智能体能力直达 IM 工作场景 |
| ⏰ **定时任务**   | Quartz 集群调度的无头 Agent 定时执行，执行记录留存并站内信通知创建人 |
| 📎 **聊天附件**   | 图片 / 文档上传与签名预览，大文档按需读取，workspace 产物一键发布为附件 |
| 🤝 **人机协同**   | HITL：敏感工具执行前人工确认、Agent 挂起式向用户提问 |
| 🔗 **应用发布**   | publishCode 独立 URL 分发智能应用，匿名资料页 + 登录访问校验 |

## 🏗️ 项目架构

模块化设计，双引擎并列，各模块职责清晰、按需引入：

```
lambda-fusion-parent/
├── 🤖 lambda-fusion-ai/                   # AI 智能平台（基于 AgentScope 2.x）
├── 📦 lambda-fusion-bom/                  # BOM 依赖管理
├── 🎯 lambda-fusion-core/                 # 核心模块（分页/CRUD/树/字典/身份）
├── 🔐 lambda-fusion-authority-api/        # 权限 API（Dubbo 远程认证 + Sa-Token 适配）
├── 🔐 lambda-fusion-authority/            # 权限管理（用户、角色、组织、资源、租户、客户端）
├── 🛡️ lambda-fusion-permission/           # 权限控制（聚合模块）
│   ├── 🛡️lambda-fusion-permission-api/          # API 权限元数据（client/server）
│   └── 🛡️lambda-fusion-permission-datascope/    # 数据权限授权树
├── ⚙️ lambda-fusion-config/               # 配置管理（dbconfig + 热更新 + Nacos 发布）
├── 📚 lambda-fusion-dictionary/           # 数据字典（静态/动态字典、枚举扫描）
├── 🗄️ lambda-fusion-datasource/           # 动态数据源管理（server/client、Dubbo 分发）
├── 🧾 lambda-fusion-form/                 # 动态表单（设计器三步向导/快照版本/动态 CRUD）
├── 🔁 lambda-fusion-workflow/            # 工作流（warm-flow 引擎装配/审批 REST/单据集成 SPI）
├── 📨 lambda-fusion-message-api/          # 消息 API（Dubbo 远程发消息契约）
├── 📨 lambda-fusion-message/              # 消息中心（站内信/公告/模板/多渠道投递）
├── 📝 lambda-fusion-logger-api/           # 操作日志 API（Dubbo 远程落库契约）
├── 📝 lambda-fusion-logger/               # 操作日志（@OperationLog 采集 + 异步落库）
├── 📤 lambda-fusion-oss/                  # 附件管理（七牛/S3）
└── 🚀 lambda-fusion-startup/              # 可运行演示应用（端口 20005）
```

## 🛠️ 技术栈

| 技术 | 版本     | 说明 |
|------|----------|------|
| ☕ **Java** | 21+      | 最新 LTS 版本，性能优异 |
| 🍃 **Spring Boot** | 4.0.2    | 微服务开发框架 |
| ☁️ **Spring Cloud** | 2025.1.x | 微服务生态组件 |
| 🤖 **AgentScope** | 2.x      | AI 多智能体运行时（harness / 模型 / 沙箱 / 通道 / 技能 / RAG） |
| 🔎 **Lucene** | 10.5.1   | 单机中文全文检索与 BM25 召回 |
| 🐘 **pgvector** | Latest   | PostgreSQL 向量检索（知识库 RAG） |
| 🌊 **OceanBase** | 4.3.5+   | MySQL 模式：一台 OB 兼任主库与 AI 向量（VECTOR / HNSW）、全文（IK 分词）检索，使用后无需再安装 MySQL 与 PostgreSQL |
| 🔐 **Sa-Token** | Latest   | 轻量级权限认证框架 |
| 💾 **MyBatis Plus** | Latest   | 增强版 ORM 框架 |
| 🔄 **MapStruct** | Latest   | 高性能对象映射 |
| 🌶️ **Lombok** | Latest   | 简化 Java 代码 |
| ☁️ **Nacos** | Latest   | 配置中心 / 注册中心 |
| ☁️ **Dubbo** | Latest   | 远程认证 / 数据源 / 权限元数据同步 |
| ☕ **Caffeine** | Latest   | 高性能本地缓存 |
| 🔧 **Hutool** | Latest   | Java 工具类库 |

## 🎨 预览与体验

<div align="center">
<table>
  <tr>
    <td align="center"><img src="docs/images/img_14.png" width="420"/></td>
    <td align="center"><img src="docs/images/img_1.png"  width="420"/></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/img_16.png"  width="420"/></td>
    <td align="center"><img src="docs/images/img_17.png"  width="420"/></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/img.png" width="420"/></td>
    <td align="center"><img src="docs/images/img_8.png" width="420"/></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/images/img_9.png" width="420"/></td>
    <td align="center"><img src="docs/images/img_10.png" width="420"/></td>
  </tr>
</table>
</div>


## 🚦 快速开始

> **想直接运行演示应用？** 查看 📖 [快速启动指南](docs/quick-start/quick-start.md) —— 使用 `lambda-fusion-startup`，只需配置环境变量即可启动，含配套 **[前端(lambda-fusion-web)](https://gitee.com/westboy/lambda-fusion-web)** 联调说明。

### 环境要求

- JDK 21+
- Maven 3.8+
- MySQL 8.0+（或 OceanBase 4.3.5+ MySQL 模式）
- PostgreSQL 12+（可选，仅 pgvector 向量检索需要）
- Redis 6.0+

### 引入依赖

在项目 `pom.xml` 中添加：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.lambda.cloud</groupId>
            <artifactId>lambda-fusion-bom</artifactId>
            <version>${lambda-fusion.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

> 若工程已基于 `lambda-cloud-project-parent:2026.1.1-SNAPSHOT`，其 `dependencyManagement` 已 import `lambda-fusion-bom`，可省略上面的 BOM import，直接按需引入模块依赖（无需版本号）。

### 使用模块

按需引入，自由组合：

```xml
<!-- AI 智能平台（基于 AgentScope 2.x） -->
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-ai</artifactId>
</dependency>

<!-- 权限管理 -->
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-authority</artifactId>
</dependency>

<!-- 配置管理 -->
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-config</artifactId>
</dependency>

<!-- 数据字典 -->
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-dictionary</artifactId>
</dependency>

<!-- 动态表单 -->
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-form</artifactId>
</dependency>

<!-- 工作流 -->
<dependency>
    <groupId>com.lambda.cloud</groupId>
    <artifactId>lambda-fusion-workflow</artifactId>
</dependency>
```

> 基座 `lambda-cloud-parent`、`lambda-cloud-project-parent` 需先安装到本地仓库或私服，再构建本仓库。

### 构建与测试

本仓库没有 Maven wrapper，请使用系统 `mvn`（Maven 3.8+、JDK 21）。

```bash
# 构建 + 安装到本地仓库（在仓库根目录执行）
mvn clean install

# 仅构建单个模块及其 fusion 依赖（-am = 同时构建依赖）
mvn -pl lambda-fusion-authority -am clean install

# 仅编译（同时触发 Spotless 检查 + SpotBugs 检查，见「代码风格与静态检查」）
mvn -pl lambda-fusion-config compile

# 运行某模块的全部测试
mvn -pl lambda-fusion-ai test

# 运行单个测试类 / 单个方法
mvn -pl lambda-fusion-ai test -Dtest=AgentGraphTest
mvn -pl lambda-fusion-ai test -Dtest=AgentGraphTest#shouldRoute
```

---

### ⛳ 反馈

- 微信 `capf4xj`
- QQ 交流群：`47736663` — [点击加入](https://qm.qq.com/q/EBIJVZBVGE)

<img src="assets/img.png" alt="capf4xj" width="180"/>

## 📖 生态依赖

- **[lambda-cloud-parent](https://gitee.com/westboy/lambda-cloud-parent)** — 核心基座，封装底层自动化配置与基础工具类
- **[lambda-cloud-project-parent](https://gitee.com/westboy/lambda-cloud-project-parent)** — 统管项目依赖版本与 Maven 构建标准
- **[lambda-fusion-web](https://gitee.com/westboy/lambda-fusion-web)** — 基于 Vben Admin 构建的配套前端界面

## 📚 相关资源

- 🎯 [实战项目示例](https://gitee.com/westboy/lambda-fusion-admin)

## 📄 开源协议

本项目基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 开源协议。

**⭐ 如果这个项目对你有帮助，请给个 Star 支持一下！**❤️
