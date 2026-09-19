# AI 对话多节点部署基线

> 适用范围：`lambda-fusion-ai` 对话运行（ChatRun）的多节点部署。设计语义与边界以
> [docs/design/chat-runtime.md](../design/chat-runtime.md) 为准；本文只给部署配置与验证清单。

## 1. 部署模型

每个 Run 仍是**单节点执行**：负载均衡把首次请求分配到某个节点，数据库是业务状态与并发裁决的
唯一来源（幂等键、终态 CAS、快照版本围栏）。不实现执行接管、租约或节点发现；节点失联留下的
运行由对账任务（`ChatRunReconciler`）按时间阈值收敛，跨节点停止由执行节点的检查点感知终态后
协作式中断（延迟 ≤ 一个检查点间隔）。

## 2. 前置配置（多节点必须）

| 配置 | 要求值 | 原因 |
| --- | --- | --- |
| `lambda.fusion.ai.state-store.type` | `REDIS` / `MYSQL` / `POSTGRES` | MEMORY/FILE 是单节点实现；HITL 跨节点恢复与停止清理依赖共享 Agent 状态 |
| `lambda.fusion.ai.workspace.storage.type` | `JDBC` | 工作区文件多节点共享（自演化审计、跨节点重建） |
| 沙箱后端 | KUBERNETES / E2B / DAYTONA / AGENTRUN | 本地 Docker 沙箱是节点绑定资源 |
| LB 会话亲和 | 按会话 cookie 或一致性哈希 | SSE 事件窗口是进程内的；续连落到非执行节点只能读数据库快照（行为见 §3） |

推荐追加的容量与超时规划（按节点规格调整）：

```yaml
lambda:
  fusion:
    ai:
      chat:
        run:
          max-active-runs: 200            # 单节点并发执行上限（配合 LLM 提供商限流）
          max-events: 4096                # 单 Run 事件窗口；堆压力按 maxActiveRuns × maxBytes 估算
          max-bytes: 8388608
          terminal-ttl-seconds: 600
          reconcile:
            enabled: true                 # 对账：孤儿运行 + 待交互超时收敛
            interval-seconds: 60
            orphan-timeout-seconds: 3600  # 必须 > max-run-duration-seconds（启动期强制校验）
            await-interaction-timeout-seconds: 86400
```

HikariCP 连接池建议显式 sizing（检查点/终结走独立事务，高峰并发占连接），例如
`maximum-pool-size: 30` 起。

## 3. 行为速查（多节点语义）

- **SSE 续连落到执行节点**：事件窗口回放 + 实时续接（游标衔接）。
- **SSE 续连落到其它节点**：返回最近一次数据库快照并结束连接；前端有限重连后提示用户显式放弃重试。
- **跨节点停止**：任意节点提交 `STOPPED` 终态；执行节点在下一个检查点（默认 ≤15s）感知并
  协作式中断本地 Agent，宽限期后强制取消。
- **节点崩溃**：遗留 `RUNNING` 由对账任务收敛为 `FAILED/INTERRUPTED`（默认阈值 1 小时）；
  待交互（待确认/待输入）超时收敛为 `FAILED/AWAIT_TIMEOUT`（默认 24 小时）。
- **HITL 确认跨节点**：确认请求可落在任意节点——从持久化快照 + 共享 AgentScope 状态重建上下文。

## 4. 双节点验证清单

部署两个节点（共享 DB/Redis）后逐项验证：

1. **基本链路**：节点 A 发起对话，SSE 流正常，终态落库正确（`status/finish_reason/snapshot_json 清空`）。
2. **续连亲和**：对话中断网重连，命中原节点时事件无缝续接；强制落到另一节点时返回数据库快照并结束。
3. **跨节点停止**：A 节点发起长对话，对 B 节点调 stop——A 节点的 Agent 在一个检查点间隔内被中断，
   Run 终态为 `STOPPED`，日志出现 `已被外部终结，本地活动源流执行协作式中断`。
4. **HITL 跨节点**：触发待确认工具后，对 B 节点提交 confirm，正常推进到下一 phase。
5. **崩溃收敛**：kill 执行中的节点，等待 `orphan-timeout-seconds` 后 Run 被对账收敛
   （日志 `Run孤儿运行已收敛`），同会话可发起新对话。
6. **幂等**：同一 `clientRequestId` 重发不产生第二个 Run；并发 confirm 只有一个生效。
7. **对账不误杀**：活跃运行（检查点持续刷新）与未超时的待交互运行都不被收敛。

## 5. 故障排查速查

| 现象 | 排查 |
| --- | --- |
| 续连后看不到实时流 | 请求是否落到执行节点（LB 亲和是否生效）；`CHAT_RUN_EVENTS_EXPIRED` 即落到非执行节点 |
| Run 长期 RUNNING | 对账任务是否启动（启动日志 `Run对账任务已启动`）；`orphan-timeout-seconds` 配置 |
| confirm 报状态冲突 | 并发重复确认（正常幂等拦截）；或上一阶段交接超时（检查旧源流是否卡死） |
| 会话锁死无法新对话 | 存在非终态 Run；查 `ai_chat_run` 该会话记录，对账阈值是否过大 |
