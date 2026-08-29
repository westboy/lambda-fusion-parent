# RAG 文档切割与来源契约

## 1. 数据流

知识库文档统一经过以下管线：

```text
原文件存储 → 抽取完整文本 → 解析文档切割策略 → 生成带来源元数据的 chunk
                                                     ├─ Lucene 全文索引
                                                     └─ 向量索引
```

文件 Reader 只负责 PDF、Word、文本等格式解析，不再决定业务切割语义。切割由
`DocumentChunker` 统一处理，避免不同调用方分别解释 chunk。实际写入哪一种派生索引由知识库的
`retrieval_mode` 决定。

## 2. 文档切割策略

每个 `ai_knowledge_document` 通过 `chunk_strategy` 持久化策略，重新入库默认沿用原策略。

| 策略 | 语义 |
|---|---|
| `AUTO` | 短文档整篇保留；检测到至少两个标题时按章节；否则按段落 |
| `WHOLE` | 整篇作为一个 chunk，调用方应确保文档长度适合嵌入模型 |
| `HEADING` | 识别 Markdown、中文章节、英文章节和数字层级标题；超长章节继续按段落切割 |
| `PARAGRAPH` | 按段落边界和目标长度切割 |
| `TOKEN` | 按 AgentScope 的近似 token 规则切割 |

`HEADING` 当前依据抽取文本中的标题语法识别章节，不读取 PDF 字号或 Word Heading 样式；只有视觉样式、
没有标题编号或关键字的文档会回退段落切割，后续需要在格式解析层补充版式元数据。

模块级参数：

```yaml
lambda:
  fusion:
    ai:
      rag:
        chunking:
          chunk-size: 512
          overlap-size: 50
          whole-document-max-chars: 2000
```

`TOKEN` 策略下 `chunk-size` 与 `overlap-size` 表示近似 token 数；其他策略下表示字符数。

上传接口的 multipart 表单可传 `chunkStrategy`，默认 `AUTO`。重新入库接口可选传入新策略，
不传则沿用文档行中的策略。

## 3. Chunk 来源元数据

每个新入库 chunk 在向量 payload 和 Lucene StoredField 中保持同一套来源契约：

- `kbId`
- `tenantId`
- `fileName`
- `chunkIndex`（零基）
- `chunkCount`
- `sectionPath`（存在标题结构时）

检索返回必须继续携带 `docId` 与 `chunkId`。自动 RAG 注入与 Agentic 检索工具复用同一来源格式，
向模型明确展示来源文件、文档 ID、章节、分块位置、分数及其语义（`COSINE` / `BM25` / `RRF`）。

旧 PGVector 数据已包含 `fileName`，升级后无需重新入库即可显示文件来源；`chunkCount` 和
`sectionPath` 只有重新入库后才会补齐。

## 4. 内容检索策略

`ai_knowledge_base.retrieval_mode` 控制知识内容如何召回，不要与应用侧控制“何时调用检索”的
`ai_app.rag_mode`（`GENERIC` / `AGENTIC` / `BOTH`）混淆。

| 策略 | 派生索引 | 嵌入模型 | 返回分数 |
|---|---|---|---|
| `FULL_TEXT` | Lucene 倒排索引 | 不需要 | `BM25` |
| `VECTOR` | AgentScope 向量索引 | 必需 | `COSINE` |
| `HYBRID` | Lucene + 向量索引 | 必需 | `RRF` |

`HYBRID` 分别扩大两路候选集，然后按 Reciprocal Rank Fusion 融合排名；BM25 与余弦分数的量纲不同，
不得直接相加或互相应用阈值。知识库检索策略或嵌入模型发生变化时，已有 `READY` 文档会进入重新入库流程，
以重建目标策略需要的索引。

默认策略和单机 Lucene 配置如下：

```yaml
lambda:
  lucene:
    enabled: true
    directory: ${user.home}/.agentscope/fusion/rag/lucene
  fusion:
    ai:
      rag:
        default-retrieval-mode: VECTOR  # FULL_TEXT / VECTOR / HYBRID
```

`lambda.lucene.enabled=false` 时不会装配全文索引；FULL_TEXT/HYBRID 知识库会在入库或检索时返回明确的配置错误。
Lucene 索引位于本地文件系统，是可以从原文档重建的派生数据，适合单机部署，不支持多个实例共同写入同一网络目录。

## 5. 当前检索边界

- 全局默认最终检索条数为 5，表示 chunk 数而不是文档数。
- 默认分数阈值为 0.5，只用于 VECTOR/HYBRID 的向量候选，是余弦相似度下限，不是正确概率。
- 自动注入字符预算默认 4000；单个超长 chunk 会保留来源头并截断正文。
- VECTOR/FULL_TEXT 单路召回的候选数量与最终返回数量共用 `limit`；HYBRID 会扩大候选集后再截断。

按文档限制 chunk 数必须与扩大候选召回配套实施；只在当前 Top-K 上去重会造成结果不足，
因此按文档分组与 rerank 仍应作为后续完整闭环实现。
