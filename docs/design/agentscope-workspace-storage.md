# AgentScope Workspace 存储设计

> 本文只定义 Workspace 的存储、隔离和写入并发边界。ChatRun 调用 AgentScope 的会话身份、完成语义与执行锁边界，
> 对话执行边界以 [对话运行时设计](chat-runtime.md) 为准。

## 1. 设计目标

AgentScope Workspace 属于部署级基础设施，不属于应用自身配置。一个服务实例中的全部应用统一使用
`lambda.fusion.ai.workspace.storage.type` 指定的存储方式：

- `LOCAL`：沿用节点本地目录，适用于单节点部署。
- `MYSQL`：使用 AgentScope MySQL 分布式存储扩展，适用于多节点部署。
- `POSTGRES`：使用 AgentScope PostgreSQL 分布式存储扩展，适用于多节点部署。

本配置只管理 AgentScope Workspace。RAG 原始文档、向量库和 Agent state store 仍由各自配置管理。

## 2. 配置

```yaml
lambda:
  fusion:
    ai:
      workspace:
        root: ${AI_WORKSPACE_ROOT:./data/ai-workspaces}
        storage:
          type: ${AI_WORKSPACE_STORAGE_TYPE:LOCAL}
          mysql:
            datasource: ${AI_WORKSPACE_MYSQL_DATASOURCE:master}
          postgres:
            datasource: ${AI_WORKSPACE_POSTGRES_DATASOURCE:ai-postgres}
      memory:
        flush:
          mode: ${AI_MEMORY_FLUSH_MODE:THROTTLED}
          min-gap: ${AI_MEMORY_FLUSH_MIN_GAP:10m}
```

`lambda-fusion-ai` 的通用默认值为 `LOCAL`，保证既有单节点项目无配置升级。启动示例模块默认选择
`MYSQL`，与其既有 MySQL Agent state store 配置保持一致。

长期记忆仍由 AgentScope 的 flush 与 consolidation 负责写入，记忆与会话压缩未指定专用模型时均沿用 Agent
主模型。Lambda Fusion 不在 AgentScope 的 `Model` 外增加输出截断判断、超时或响应缓存适配层；模型完成状态的校验
与副作用提交应由 AgentScope 在记忆写入和上下文替换边界统一处理。AgentScope 2.0.1 对非空但被截断的响应尚未
统一校验，升级到官方修复版本后由底层直接生效，上层不维护平行语义。

远程 Workspace 必须同时使用分布式 Agent state store。若 Workspace 为 `MYSQL`/`POSTGRES`，而
`state-store.type` 仍为 `MEMORY` 或 `FILE`，系统启动时直接失败，避免集群节点间状态与文件行为不一致。

AgentScope 分布式扩展会在首次初始化时创建 `agentscope_store`；启用沙箱快照时还会使用
`agentscope_snapshots`。目标数据库必须预先存在，连接账号需要具备相应建表和读写权限。启动示例已直接
引入 MySQL 扩展；使用 PostgreSQL 的部署还必须在最终启动模块中引入 `agentscope-extensions-postgresql`。

## 3. 存储与隔离

本地模式保持原路径：

```text
{workspace.root}/tenants/{tenantId}/apps/{appId}
```

远程模式以稳定的 `MD5("app:" + appId + ":t:" + tenantId)` Agent ID 建立命名空间，租户与应用不会共享 Workspace。
HOST 模式下，以下 AgentScope 管理内容进入分布式 BaseStore：

- `AGENTS.md`、`MEMORY.md`、`tools.json`
- `memory/`、`skills/`、`subagents/`、`knowledge/`、`plans/`
- `agents/{agentId}/sessions/`、`agents/{agentId}/tasks/`
- AgentScope 内部消息总线 `.agentscope/` 和 Lambda Fusion 审计目录 `.audit/`

沙箱模式继续使用 AgentScope 原生沙箱文件系统，运行数据通过相同 `DistributedStore` 的快照能力持久化，
不改成 HOST 的文件路由。节点本地 `.index/` 仅用于可重建索引，不进入共享存储，也不对管理 API 暴露。

初始化模板保存在当前节点：

```text
{workspace.root}/.remote-templates/{storageType}/tenants/{tenantId}/apps/{appId}
```

模板只是远程文件不存在时的只读初始内容，不作为运行期共享数据来源。远程文件系统使用 AgentScope 的
`RemoteFilesystem`、`OverlayFilesystem` 和 `CompositeFilesystem` 组合，并把所有本地回退层限制在应用模板目录，
防止未路由路径访问宿主机文件系统。

## 4. 并发模型

稳定 Agent ID 只用于 Workspace 命名空间和短写操作的互斥键，不作为整次 Agent 执行锁。

- HOST Workspace：模型调用、工具调用、记忆整理和整个 Agent Flux 外层不持有 Lambda Fusion Workspace 锁。
  远程文件系统的单文件读写遵循 AgentScope `RemoteFilesystem` 和底层 `BaseStore` 的语义；若某个可变工具确实需要
  读改写原子性，只在该次工具写入范围内增加窄锁，不串行整个应用的对话。
- 沙箱 Workspace：完整使用 AgentScope `SandboxManager` 的 acquire、快照和 release 生命周期，不再叠加同键应用锁。
- 管理端写入：`WorkspaceStorage#withWriteLock` 只包裹一次实际写操作，防止同一管理资源的并发覆盖；不能把该锁
  扩大到模型或记忆调用。
- 审计写入：等待 AgentScope 源流结束、Sandbox 快照与 release 完成后，再以短写操作记录审计。审计失败不改变
  已提交的 ChatRun 业务终态。

同一应用的不同用户允许并发调用模型。Agent 会话状态由 AgentScope 按 `(userId, sessionId)` 保护，Workspace
共享文件的冲突则由具体文件系统能力或实际写工具的窄临界区处理，不能用应用级全流程串行掩盖。ChatRun 仅按
同一 Session 的实例级 `drainedSignal` 顺序启动相邻 Run，保证前一 Run 的最终记忆尾部和审计结束后再启动下一条；
HITL 新 phase 由同一实例在旧 phase 已排空后启动。该非阻塞尾链不是 Workspace 锁，也不会影响同一应用的其他用户。

## 5. 切换与运维约束

存储类型由配置文件决定，不提供应用级选择，也不提供运行期修改接口。多节点必须使用完全相同的配置，
变更时应停止全部节点后统一修改并完整重启。

切换存储类型采用“新存储即新 Workspace”语义：

- 不迁移旧数据；
- 不在新旧存储之间同步；
- 不删除旧存储；
- 回切原配置时可重新看到原存储中的内容。

因此，切换前应由运维人员确认接受一个全新的 Workspace。应用删除也不扫描或清理其他存储类型中的历史
Workspace；远程存储的数据保留策略由数据库运维负责。
