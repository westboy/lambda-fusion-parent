# OceanBase 快速启动指南（lambda-fusion-startup）

本指南是 [快速启动指南](quick-start.md) 的 **OceanBase 版本**：使用 OceanBase（MySQL 模式，4.3.5+）后，**无需再安装 MySQL 与 PostgreSQL**——一台 OB 同时承担**主库**（业务数据 + Liquibase 建表 + 配置源）与 **AI 知识库向量检索**（原生 `VECTOR` + HNSW 索引），环境只剩 **OceanBase + Redis**（外加 Nacos）。

JDK / Maven / IDE 的准备与主指南完全相同，此处不再重复，只讲与 OB 相关的差异步骤。

> OB 兼容 MySQL 协议（默认端口 **2881**），连接串写法与 MySQL 一致，驱动无需更换；用户名格式为 **`用户@租户`**（如 `root@test`）。

---

## 一、环境准备

| 依赖 | 版本 | 说明 |
|------|------|------|
| ☕ JDK | 21+ | 同主指南 |
| 🔧 Maven | 3.8+ | 同主指南 |
| 🌊 OceanBase | 4.3.5+ | MySQL 模式；兼任主库与 AI 向量检索 |
| 🍃 Redis | 6.0+ | 缓存与会话 |

---

## 二、启动步骤

### 步骤 1：启动 OceanBase

本地推荐用 Docker 拉起社区版（首次初始化需要几分钟，`docker logs -f oceanbase-ce` 观察到 `boot success!` 即就绪）：

```bash
docker run -d --name oceanbase-ce -p 2881:2881 -e MODE=mini oceanbase/oceanbase-ce
```

镜像默认自带一个 MySQL 模式租户 `test`（连接账号 `root@test`，默认空密码）；已有 OB 集群的读者直接使用自己的租户与账号即可，生产环境请按 OceanBase 官方文档自建租户与密码。

### 步骤 2：创建业务库

库表由框架 Liquibase 自动创建，只需先建好数据库：

```bash
docker exec -it oceanbase-ce obclient -h127.0.0.1 -P2881 -uroot@test \
  -e "CREATE DATABASE lambda_fusion DEFAULT CHARACTER SET utf8mb4;"
```

### 步骤 3：配置环境变量文件

按主指南步骤 2 的方式复制 `.evn.simple` 为 `.evn`，再把数据库相关变量改为指向 OB（其余 Nacos / Redis / 七牛变量保持不变）：

```
MAIN_DB_URL=jdbc:p6spy:mysql://127.0.0.1:2881/lambda_fusion?nullCatalogMeansCurrent=true
MAIN_DB_USERNAME=root@test
MAIN_DB_PASSWORD=
MAIN_DB_DRIVER_CLASS_NAME=com.p6spy.engine.spy.P6SpyDriver

AI_DB_URL=jdbc:p6spy:mysql://127.0.0.1:2881/lambda_fusion?nullCatalogMeansCurrent=true
AI_DB_USERNAME=root@test
AI_DB_PASSWORD=
AI_DB_DRIVER_CLASS_NAME=com.p6spy.engine.spy.P6SpyDriver
AI_RAG_STORE_TYPE=OCEANBASE
```

各变量说明：

| 变量 | 说明 |
|------|------|
| `MAIN_DB_*` | 主库连接，指向 OB 的 2881 端口；业务建表、动态配置源均落在 OB 上 |
| `AI_DB_*` | 框架要求这组变量存在（AI 数据源必填），纯 OB 场景同样指向 OB 即可 |
| `AI_RAG_STORE_TYPE=OCEANBASE` | 知识库向量检索切换为 OB 后端：原生 `VECTOR` 类型 + HNSW 向量索引，**无需 PostgreSQL**；向量表在创建知识库时自动建表 |

> - OB 向量连接默认复用 `AI_DB_*`；如需让向量库单独使用一个 OB 实例 / 库，可用 `AI_OCEANBASE_JDBC_URL / AI_OCEANBASE_USERNAME / AI_OCEANBASE_PASSWORD` 单独指定。
> - 知识库全文检索默认走 Lucene（本地目录），不依赖数据库；如需切换 OB 全文（IK 分词 + BM25，需 OB 4.3.5+），设置 `lambda.fusion.ai.rag.full-text.type=OCEANBASE`（该配置未暴露为环境变量，需写入 application.yml 或 Nacos）。
> - `root@test` 默认空密码；若你的环境变量工具会忽略空值导致启动报占位符错误，先给账号设置一个密码即可。

### 步骤 4：启动应用

与主指南步骤 1、3、4 完全相同：把 `lambda-fusion-startup` 添加为 Maven 项目 → 在 `FusionApplication` 运行配置中加载 `.evn` → 启动。

- 应用地址：`http://localhost:20005`
- 接口文档（Swagger）：`http://localhost:20005/doc.html`
- **默认登录账号**：账号 `13303820660`，密码 `123456`

---

## 三、验证 OceanBase 生效

1. **建表**：首次启动 Liquibase 自动在 `lambda_fusion` 库中创建全部框架表；
2. **向量表**：登录系统创建知识库并上传文档后，OB 中自动出现该知识库的向量表（`VECTOR` 列 + HNSW 索引），说明知识库 RAG 已完全落在 OB 上。

---

## 四、常见问题

- **Docker 启动 OB 很慢 / 连接被拒绝**：OB 首次初始化需要几分钟，`docker logs -f oceanbase-ce` 看到 `boot success!` 后再启动应用。
- **报租户或用户不存在**：确认用户名带租户（如 `root@test`），且租户为 MySQL 模式（而非 Oracle 模式）。
- **向量检索报错**：确认 `AI_RAG_STORE_TYPE=OCEANBASE` 已配置，且 OB 版本 ≥ 4.3.5。
- **其余通用问题**：见主指南「五、常见问题」。
