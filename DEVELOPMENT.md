# SimpleRAG 开发与架构说明

## 1. 目标与约束

SimpleRAG 的核心目标是在个人电脑上提供可用的中英文文档/代码语义检索，并允许用户选择 OpenAI 兼容服务完成带引用问答。

当前实现遵守以下边界：

- 文档扫描、分块、embedding 和检索默认在本机完成。
- Java 17 + Swing，单 Maven 模块，不引入 Spring 或依赖注入框架。
- ONNX 推理由 LangChain4j 在 JVM 进程内完成，不需要 Python。
- 远程 RAG 只发送召回片段，并且只允许使用明确为最新的索引。
- SQLite、内存索引和磁盘索引必须通过 revision 与 manifest 建立可验证关系。

## 2. 为什么进行了 revision/状态改造

旧实现把同一知识库的状态分散在三处：

```text
SQLite knowledge_source
内存 SemanticSearchEngine
indexes/<knowledgeBaseId>.bin
```

添加或删除数据源后，数据库先变化，再异步重建并覆盖索引文件。失败、取消、应用退出或知识库切换都可能让三处状态互相矛盾。旧快照也只记录模型显示名，无法证明模型文件、向量维度和分块版本兼容。

改造后的关键不变量是：

```text
KnowledgeBaseId
SourceRevision
SourceSetHash
EmbeddingModelSignature
EmbeddingDimension
ChunkingVersion
IndexFormatVersion
```

缺失或不匹配任一必要字段时，索引不能被视为完全有效。

## 3. 数据模型与 migration

`DatabaseManager` 维护 `schema_version`，migration 在事务中按顺序执行。当前 schema 为版本 3。

`knowledge_base` 新增：

```text
source_revision INTEGER NOT NULL DEFAULT 0
published_index_revision INTEGER
index_status TEXT NOT NULL DEFAULT 'EMPTY'
last_index_error TEXT NOT NULL DEFAULT ''
last_verified_source_hash TEXT NOT NULL DEFAULT ''
last_verified_at INTEGER
freshness_reason TEXT NOT NULL DEFAULT ''
```

发布记录保存在：

```sql
CREATE TABLE knowledge_index (
    knowledge_base_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    file_name TEXT NOT NULL,
    source_set_hash TEXT NOT NULL,
    embedding_model_signature TEXT NOT NULL,
    embedding_dimension INTEGER NOT NULL,
    chunking_version INTEGER NOT NULL,
    index_format_version INTEGER NOT NULL,
    built_at INTEGER NOT NULL,
    PRIMARY KEY (knowledge_base_id, revision)
);
```

添加或删除 `knowledge_source` 时，以下操作位于同一 SQLite 事务：

1. 修改数据源记录。
2. `source_revision = source_revision + 1`。
3. `index_status = 'DIRTY'`。
4. 清空最近索引错误并记录“数据源配置已变化”的 freshness 原因。

重复添加同一路径或删除不存在的路径不会虚增 revision。

## 4. 索引身份与向量兼容

`IndexSnapshot` 格式版本为 4，并包含 `IndexManifest` 与文件级 `DocumentIndexEntry`。每个 entry 记录：

```text
root + relativePath
size + modifiedAt + contentHash(SHA-256)
readerVersion + chunkingVersion
chunkIds
```

`IndexManifest` 继续记录 revision、源集合、模型和格式的全局身份：

```java
public record IndexManifest(
        String knowledgeBaseId,
        long sourceRevision,
        String sourceSetHash,
        String embeddingModelSignature,
        int embeddingDimension,
        int chunkingVersion,
        int indexFormatVersion,
        long builtAt
) {}
```

`EmbeddingModelSignature` 包含 provider 类型、模型名、模型文件签名、维度和预处理版本。LangChain4j ONNX adapter 对 ONNX 文件与 tokenizer 文件计算 SHA-256，因此“显示名相同但模型文件不同”不会被误判为兼容。

`SemanticScorer` 是搜索和高亮共享的兼容性与向量评分组件。只有 manifest 签名匹配、索引维度有效、所有文档向量维度一致，查询向量才会参与余弦计算。查询向量维度不一致时会直接降级为词法模式。

旧版 snapshot 没有完整 manifest。恢复时可以读取片段用于保守的词法恢复，但状态会变为 `INCOMPATIBLE`，向量检索和远程 RAG 均被禁止，直到重建。

## 5. 索引状态机

```java
public enum IndexStatus {
    EMPTY,
    READY,
    DIRTY,
    BUILDING,
    FAILED,
    INCOMPATIBLE
}
```

状态转换的主要路径：

```text
EMPTY --添加数据源--> DIRTY
READY --数据源/外部文件变化--> DIRTY
DIRTY --开始构建--> BUILDING
BUILDING --发布成功--> READY
BUILDING --失败--> FAILED
BUILDING --取消/过期--> DIRTY
任意恢复状态 --manifest/模型/格式不匹配--> INCOMPATIBLE
```

`READY` 必须同时满足：

```text
published_index_revision == source_revision
manifest.sourceRevision == source_revision
manifest.sourceSetHash == 当前数据源签名
```

`IndexIdentity.sourceSetHash` 会对规范化数据源路径、目录存在状态，以及其中普通文件的相对路径、大小和修改时间进行哈希，并跳过常见生成目录。应用启动、构建发布前和运行期 reconciliation 都使用同一身份算法。

### 5.1 运行期 freshness

`SourceFreshnessMonitor` 是 application output port，生产 adapter 为 `FileSystemSourceFreshnessMonitor`。它为每个源目录及现有子目录注册 Java NIO `WatchService`，新建目录后继续递归注册，并组合两条检测路径：

```text
低延迟 watcher 事件
周期性 FreshnessReconciler 完整 sourceSetHash 核对
```

普通文件创建、修改或删除事件会立即把 monitor proof 固定为 `CHANGED`，直到下一次构建重新建立基线。即使文件大小相同、mtime 被恢复，也不会因为快速 fingerprint 相同而重新放行。空目录创建等不能直接证明内容变化的事件会 debounce 后核对；`OVERFLOW`、watch key 失效、根目录不可访问或核对异常会进入 `VERIFYING`/`UNAVAILABLE`，远程调用采用保守拒绝。

monitor session 使用递增 epoch。切换知识库或发布新 revision 后，旧 session 的延迟回调会被丢弃，不能覆盖新状态。监听器发现变化时会在匹配当前 revision 的条件更新中递增 `source_revision`、标记 `DIRTY` 并使活动 `IndexHandle` stale，但不写索引文件。这样下一次构建使用新的 revision 文件名，失败时不会覆盖仍被数据库引用的旧 snapshot。

## 6. 版本化构建与原子发布

开始构建时，应用创建不可变的 `IndexBuildRequest`：

```java
public record IndexBuildRequest(
        String knowledgeBaseId,
        long sourceRevision,
        List<Path> sources,
        String sourceSetHash,
        EmbeddingModelSignature modelSignature
) {}
```

发布顺序：

1. 从 repository 读取并捕获 request。
2. 条件更新数据库状态为 `BUILDING`；若当前 revision 已发布，同时原子分配下一个 revision。
3. 读取上一已发布 snapshot，并创建独立 `SemanticSearchEngine` builder。
4. 扫描当前文件并计算 `FileFingerprint`。
5. `IncrementalIndexPlanner` 纯比较得到 `added/modified/deleted/reused`。
6. 复用未变化文件的 chunks/embeddings，只读取、分块和向量化新增/修改文件。
7. 删除项不进入结果，builder 组装完整的新 snapshot 与 manifest。
8. 再次计算 source set hash，拒绝构建期间发生的外部文件变化。
9. 写 `<revision>.bin.tmp` 并关闭输出流。
10. 使用 `ATOMIC_MOVE` 发布为 `<revision>.bin`；文件系统不支持原子移动时构建失败，不执行非原子覆盖。
11. 在 SQLite 事务中再次校验 `source_revision` 和状态仍为 `BUILDING`，写 `knowledge_index` 并更新发布指针。
12. 事务提交后，才替换当前内存 `IndexHandle`。

开始构建时 monitor 基线切换到 `IndexBuildRequest.sourceSetHash`。发布成功后则以 manifest 中的 hash 启动新 session，并立即完整核对；因此最终 hash 计算与 SQLite 发布之间发生的文件变化也不会被新 monitor 基线掩盖。

磁盘路径：

```text
%USERPROFILE%\.simplerag\indexes\<knowledgeBaseId>\<revision>.bin
```

构建结果过期时，数据库条件发布返回 false，新 revision 文件会被删除，旧发布索引保持不变。

## 7. 失败、取消与启动恢复

- 扫描或 embedding 失败：记录 `FAILED`，保留旧 `published_index_revision`。
- 保存或原子移动失败：不更新数据库发布记录，也不替换内存索引。
- 用户取消或线程中断：状态回到 `DIRTY`，删除临时文件。
- 构建期间数据库 revision 变化：旧结果作为 stale task 丢弃。
- 应用在 `BUILDING` 时退出：下次启动将其恢复为失败状态，不会永久显示“构建中”。
- 启动时清理 `.tmp` 与数据库未引用的 revision 文件。
- 已发布文件缺失或损坏：状态变为 `INCOMPATIBLE`，要求重建。

这些规则保证故障最多留下可清理的未引用文件，不会让数据库指向半成品。

## 8. RAG freshness 与隐私

`AskUseCase` 在召回和任何 HTTP 请求之前执行 freshness 校验：

```text
status == READY
published_index_revision != null
published_index_revision == source_revision
任务 knowledgeBaseId/sourceRevision == 当前 IndexHandle
SourceFreshnessMonitor proof == VERIFIED
proof knowledgeBaseId/sourceRevision == 当前任务
```

任一条件不满足都抛出本地错误。`AskUseCase` 在召回前检查一次，并在 citations 准备完成、调用 `ChatModel` 前再次检查，缩小异步文件事件期间的发送窗口。`DIRTY`、`BUILDING`、`FAILED`、`INCOMPATIBLE`、`VERIFYING`、`UNAVAILABLE` 和 `STOPPED` 状态不会静默使用旧片段调用远程 API。

允许调用时只发送最多 6 个召回片段的路径、行号、内容和用户问题，不发送整个知识库。API Key 使用 AES-GCM 加密存储；其安全级别是应用级本地保护，不是操作系统凭据保险库。

多轮对话由独立的 `application.conversation` 模块管理（见第 17 节）。每一轮问答都会重新执行 freshness 检查与知识检索；对话历史只保留 user/assistant 文本，不会沿用上一轮的引用片段。失败或用户取消的轮次不会写入会话历史。

## 9. 后台任务身份

`IndexHandle` 和 Swing controller 统一绑定：

```text
knowledgeBaseId + sourceRevision
```

`KnowledgeController.TaskIdentity` 在启动搜索、高亮或问答时捕获。后台结果回到 UI 前再次比较当前 identity；知识库切换或 revision 变化后，旧搜索结果、旧高亮、旧引用和旧回答都会被丢弃。

`BackgroundTaskCoordinator` 是 `SwingWorker` 的唯一创建位置，统一负责取消、identity 比较、EDT progress/completion 回调和异常解包。`MainFrame.clearWorkspace` 只通过 `TaskHandle` 取消搜索、高亮和问答；索引构建自身继续检查线程中断。

## 10. 架构与依赖方向

当前仍是单 Maven 模块的模块化单体：

```text
com.simplerag
├─ bootstrap
│  └─ AppCompositionRoot
├─ application
│  ├─ port.in
│  │  ├─ ManageKnowledgeBases
│  │  ├─ ManageKnowledgeSources
│  │  ├─ RebuildKnowledgeIndex
│  │  ├─ SearchKnowledge
│  │  ├─ AskKnowledge
│  │  ├─ ManageApiSettings
│  │  └─ DesktopReadModel
│  ├─ port.out
│  │  ├─ KnowledgeBaseRepository
│  │  ├─ SettingsRepository
│  │  ├─ IndexRepository
│  │  ├─ TextEmbedder
│  │  ├─ ChatModel
│  │  ├─ SecretStore
│  │  └─ SourceFreshnessMonitor
│  ├─ conversation
│  │  ├─ ChatMessage / ChatRequest
│  │  ├─ ConversationContext / ConversationSession
│  │  └─ ConversationStore（内存会话，按 knowledgeBaseId 索引）
│  ├─ freshness
│  │  ├─ SourceFingerprint / FreshnessSnapshot
│  │  └─ FreshnessGate
│  ├─ runtime
│  │  ├─ ActiveKnowledgeContext
│  │  ├─ ActiveKnowledgeRuntime
│  │  └─ IndexLifecycle
│  ├─ dto
│  │  ├─ DocumentReference / SearchResultView / CitationView
│  │  └─ IndexBuildProgress / IndexBuildResult / AskResultView
│  └─ usecase
│     ├─ KnowledgeBaseUseCases / KnowledgeSourceUseCases
│     ├─ IndexBuildUseCase / SearchUseCase / AskUseCase
│     └─ ApiSettingsUseCase / DesktopQueryService
├─ adapter.in.swing
│  ├─ MainFrame
│  ├─ DesktopWorkspaceController（页面工作流协调）
│  ├─ KnowledgePanel / SearchPanel / AskPanel / StatusBar
│  ├─ BackgroundTaskCoordinator / DesktopFileGateway
│  ├─ KnowledgeController
│  ├─ SearchController
│  ├─ AskController
│  └─ Theme / ThemeBootstrap
├─ adapter.out
│  ├─ sqlite
│  ├─ filesystem (revision index + WatchService/reconciliation)
│  ├─ onnx
│  ├─ openai
│  └─ security
├─ model
├─ search
└─ rag
```

依赖方向：

```text
Swing -> input ports -> use case -> output ports <- adapters
```

只有 `AppCompositionRoot` 在生产代码中创建 SQLite、文件索引、ONNX、HTTP 和密钥 adapter，并显式共享唯一 `ActiveKnowledgeRuntime` 与 freshness monitor。`MainFrame` 不直接持有应用服务或基础设施，而是通过三个页面 controller 调用输入端口。

`SemanticSearchEngine` 是轻量组合对象。扫描、读取、分块、词法特征、查询分析、词法评分、语义评分、混合排名和高亮分别由 `DocumentScanner`、`DocumentReaderRegistry`、`ChunkerRegistry`、`LexicalFeatureExtractor`、`QueryAnalyzer`、`LexicalScorer`、`SemanticScorer`、`RankingPolicy` 与 `SemanticHighlightService` 承担。

## 11. ONNX 与模型下载

本地模型为 `paraphrase-multilingual-MiniLM-L12-v2` 的量化 ONNX 版本，输出 384 维句向量。

`Langchain4jOnnxEmbeddingProvider` 使用：

```text
OnnxEmbeddingModel(onnxPath, tokenizerPath, PoolingMode.MEAN)
```

LangChain4j 的传递依赖提供 Java ONNX Runtime 和 DJL Hugging Face tokenizer。模型懒加载且线程安全。

`ModelDownloader` 位于 `adapter.out.onnx`，使用 JDK `HttpClient` 下载 `tokenizer.json` 与 `model_quint8_avx2.onnx`，临时文件完成后原子移动。入口：

```powershell
.\setup-semantic-model.cmd
```

## 12. 检索与高亮

文档处理：

- 源码按 36 行窗口、8 行重叠分块。
- 普通文档按自然段和长度聚合。
- 单文件最大 2 MB。
- 保留绝对路径、所属 root、行号和修改时间。

词法特征包括 Unicode NFKC 归一化、camelCase/snake_case 拆分、中文二元/三元特征、TF-IDF、文件名加权和少量中英文概念归一。

`RankingPolicy` 当前版本为 2：

```text
普通查询 = 0.78 * 语义分数 + 0.22 * 词法分数
代码/位置查询 = 0.58 * 主题语义分数 + 0.42 * 词法与元数据分数
```

`QueryAnalyzer` 会识别 code/location intent，并从“请帮我找……相关代码在哪”“where is the implementation”等自然问法中提取真正主题。`semanticText` 用于查询 embedding，避免“代码、实现、在哪、哪个文件”稀释主题；词法 query 同样只保留主题，但 intent 标志继续传给 scorer。

`LexicalScorer` 对定位查询额外比较完整 path/fileName，对代码查询奖励源码扩展名；当查询包含类、方法或函数标识符时，`class/interface/record/def/function/name(` 声明得到高权重。该策略只改变查询期排序，不改变 snapshot 格式，无需重建索引。

语义高亮会把命中片段拆成段落、句子、单行和四行代码窗口，批量计算向量相似度，加入轻微长度惩罚并选取最多两个不重叠区域。同一查询下的定位结果按 `chunkId@limit` 缓存。

## 13. 测试策略

快速测试使用 JUnit 5：

```powershell
mvn.cmd -q test
```

当前一致性测试覆盖：

- 旧 schema migration 保留已有知识库。
- 数据源变更原子递增 revision 并变为 `DIRTY`。
- 成功构建发布到 revision 文件并进入 `READY`。
- 构建期间 revision 变化时拒绝发布。
- 模型签名不匹配时搜索和高亮不调用 query embedding。
- embedding 失败时保留旧发布 revision 并阻止 RAG。
- 外部文件变化在恢复时被识别为 `DIRTY`。
- `READY` 后同会话修改或删除文件会在限定时间内变为 `DIRTY`。
- 删除敏感文件后远程 HTTP 请求次数保持为 0。
- 新建子目录会递归注册，后续文件变化仍可检测。
- `OVERFLOW` 触发完整 reconciliation，监控关闭或根目录不可访问时 gate 保守拒绝。
- 构建期间文件事件不能覆盖较新的 `DIRTY`，旧 monitor 回调也不能污染新 `READY`。
- 切换知识库后旧 identity 的结果被拒绝。
- 取消构建保留旧发布版本且不残留 `.tmp`。
- 启动时把中断的 `BUILDING` 恢复为失败状态并清理 `.tmp`。
- 只修改一个文件时，embedding 输入只包含该文件的新 chunks。
- 删除文件后，新 snapshot 不包含其 entry 或任何 chunk。
- 模型、reader 或 chunker 版本变化时，planner 自动把必要文件归入 modified。
- 同一文件集合的增量构建与全量构建产生等价 entries、chunks 和 embeddings。
- 增量 embedding 失败时，旧 published revision 和旧 revision 文件保持不变。
- 索引快照反序列化拒绝对象图之外的类，且在其 `readObject` 执行前拒绝。
- 凭据文件与凭据目录不进入索引，并逐条上报为已跳过。
- 嵌套数据源被折叠，同一文件不会被索引两次。
- 扫描文件数与目录深度触顶时停止遍历并显式上报，不静默截断。
- 并行读取与串行读取产生逐位一致的 chunk 顺序与文件条目。
- size/mtime 哈希快路径与强制全量校验产生相同的文件条目。
- 带 BOM 的 UTF-8 与 UTF-16LE/BE 文档被正确解码，而非当作二进制丢弃。

另有纯应用层测试使用内存 repository、内存 index repository、fake embedder、fake chat model 和 fake secret store 运行完整重建与问答用例，不启动 Swing、SQLite、ONNX 或 HTTP 服务。多轮对话模块由 `ConversationModulesTest` 覆盖 revision 绑定、token/轮次裁剪与请求不可变性；`AskPanelTest` 覆盖问答页独立构造。
该组测试还注入索引保存失败和数据库发布失败，验证两类故障都不会移动已发布 revision 或替换活动索引。

ArchUnit 检查：

- application 不依赖 Swing 或具体 adapter。
- Swing 不依赖 SQLite、ONNX、OpenAI 或文件系统 adapter。
- 具体基础设施只在 bootstrap/adapter 范围内组装。

完整脚本：

```powershell
.\build-and-test.cmd
```

除 JUnit/ArchUnit 外，脚本还保留两个原入口作为回归基线：

- `SemanticSearchEngineTest`：真实 ONNX 跨语言检索、语义高亮与快照恢复。
- `CourseFeaturesTest`：临时 SQLite、多知识库、AES-GCM、模拟 `/models`、JSON/SSE RAG 与引用。

预期末尾输出：

```text
Vector semantic check: enabled and cross-language match passed
SemanticSearchEngineTest: all checks passed
CourseFeaturesTest: knowledge-base CRUD and RAG API checks passed
```

## 14. 当前限制

- PDF 只读取文本层；图片、扫描件和纯图片 PDF 不做 OCR。
- Office 仅支持 OOXML（DOCX/PPTX/XLSX），不解析旧版 DOC/PPT/XLS 二进制格式、宏或嵌入对象内容。
- 索引使用内存线性扫描，适合个人规模知识库；大量片段可后续接入 HNSW。
- 当前 watcher/reconciliation 只监控本机当前活动知识库；切换到其他知识库时会重新加载索引并执行完整身份校验。
- API Key 尚未接入 Windows Credential Manager。
- `KnowledgeService` 仍保留兼容 facade，供既有命令行回归和构建流程复用；生产桌面组合中的搜索、问答和 API 设置已经直接装配独立用例。
- 多轮对话会话仅保存在内存（`ConversationStore`），应用退出后不会恢复；SQLite 持久化按计划单独提交。

## 15. 第二阶段结构稳定化结果

### 15.1 活动运行态

```text
ActiveKnowledgeContext
  knowledgeBase + sourceRevision + IndexStatus
  FreshnessSnapshot + IndexHandle
            |
            v
ActiveKnowledgeRuntime（唯一原子写入入口）
            |
            v
IndexLifecycle（restore/beginBuild/invalidate/publish/fail/refresh）
```

context 构造时校验 knowledge-base metadata 与 handle identity 完全一致。发布转换还要求 `publishedIndexRevision == sourceRevision`；跨知识库或跨 revision 转换会被拒绝。

### 15.2 Swing 页面与 DTO 边界

`KnowledgePanel`、`SearchPanel` 和 `AskPanel` 自行拥有控件、模型和渲染器，均有不创建 `JFrame` 的独立构造测试。`StatusBar` 单独管理全局状态。`DesktopWorkspaceController` 协调页面工作流，`MainFrame` 只保留窗口组合、模式导航和关闭流程。Swing 只消费 application DTO，不依赖 `DocumentChunk`、`SemanticSearchEngine` 或 `IndexHandle`。

`BackgroundTaskCoordinator` 捕获 `knowledgeBaseId + sourceRevision`，在 EDT 应用结果前重新比较 identity。`DesktopFileGateway` 隔离 `java.awt.Desktop`。

### 15.3 repository 与事务

output port 已拆分为：

```text
KnowledgeBaseRepository
KnowledgeSourceRepository
IndexPublicationRepository
FreshnessRepository
SettingsRepository
```

生产环境仍由同一 `AppRepository` 聚合实现，并通过 `SqliteTransactionManager` 执行跨表事务。添加/删除 source 与 revision/`DIRTY` 更新不能部分提交；索引 manifest 与发布指针仍在同一条件事务内发布。

### 15.4 架构决策与追踪

- ADR 位于 [`docs/adr`](docs/adr)。
- 工程问题、实现类和自动化证据位于 [`docs/REQUIREMENTS_TRACEABILITY.md`](docs/REQUIREMENTS_TRACEABILITY.md)。
- `ArchitectureTest` 固定三层依赖、Swing DTO 边界、用例隔离、检索策略依赖和 `SwingWorker` 所有权。

## 16. 第三阶段增量索引结果

### 16.1 文件级身份与纯规划策略

`FileFingerprint` 对每个受支持文件计算最终 SHA-256 身份；`DocumentIndexEntry` 把该身份、reader/chunker 版本与已发布 chunk IDs 绑定。当文件的 size 与 mtime 都与上一已发布 entry 一致时，会沿用该 entry 的 hash 而不重读文件内容（见 23.3）。`IncrementalIndexPlanner` 不访问文件系统、数据库或 embedding adapter，只比较上一 entries 与当前 fingerprints，输出 `added/modified/deleted/reused`，可直接使用小对象图测试。

### 16.2 完整 snapshot 的增量生成

`IncrementalIndexBuilder` 按当前扫描顺序组装文件：reused 项从上一 snapshot 取回原 chunks 和 embeddings，added/modified 项才经过 reader、chunker 和批量 embedding，deleted 项自然从结果中消失。added/modified 项的读取与分块在线程池中并行执行，但结果严格按原扫描下标回收，因此 chunk 顺序与身份和串行读取完全一致（见 23.3）。最终仍生成包含全部当前文件的完整 revision snapshot（当前格式 v5），不原地修改活动索引。

复用的全局前提是 embedding model signature 兼容；每个文件还必须同时匹配内容 hash、大小、mtime、reader version 和 chunking version。任一条件变化都会重新处理必要文件。旧 v1-v3 snapshot 没有文件级 entries，会保守执行一次全量重建。

### 16.3 发布与失败语义

watcher 确认外部文件变化时条件递增 `source_revision`；对已经 `READY` 的 revision 主动重建时，`beginIndexBuildRevision` 也会原子分配下一 revision。因此增量构建总能写入新的 `<revision>.bin`。保存、embedding、取消或数据库条件发布失败时，`published_index_revision` 仍指向旧文件，活动 `IndexHandle` 也不会切换。

自动化证据位于 `IncrementalIndexTest` 与 `RuntimeFreshnessTest.failedIncrementalBuildKeepsPreviouslyPublishedRevisionFile`，覆盖单文件向量化、删除、版本回退、全量等价和失败保留旧 revision。

## 17. 多轮对话与问答页聊天气泡 UI

### 17.1 已完成范围

- 多轮对话做成独立 application 模块，与检索/freshness/索引生命周期解耦。
- 知识问答页改为接近主流产品的聊天气泡 UI（用户右对齐、助手左对齐）。
- 继续修气泡布局：消息行按内容高度收缩，长回答按聊天列宽铺开，消除 BoxLayout 纵向拉伸造成的大片留白。

### 17.2 多轮对话模块（内存）

新增包 `application.conversation`：

| 类型 | 职责 |
| --- | --- |
| `ChatMessage` | user/assistant/system 消息值对象；估算 token（粗估 chars/4） |
| `ConversationSession` | 单会话历史；绑定 `knowledgeBaseId + sourceRevision` |
| `ConversationContext` | 裁剪策略：默认最多 12 轮、约 3000 token |
| `ConversationStore` | 按知识库索引的内存会话表；revision 变化时替换会话 |
| `ChatRequest` | 不可变出站请求：当前问题 + 历史 + **本轮** citations |

不变量与边界：

- 历史绑定 `knowledgeBaseId + sourceRevision`；切换知识库或 revision 变化会打开新会话，旧历史不可复用。
- 历史只保留 user/assistant 文本，**不保留**引用片段或文件路径上下文。
- 每一轮 `AskUseCase` 仍重新做 freshness 检查与知识检索；历史不得重新引入旧 citations。
- 默认预算：最多 12 轮、约 3000 token（粗估 chars/4）；超出时从最旧完整 user 轮次裁剪。
- 失败轮次与用户取消不写入历史；成功后才 `appendUser` / `appendAssistant`。
- `ConversationStore` 明确不写 SQLite（本阶段按产品要求单独提交持久化）。

`AskController` 在流式问答前快照 `historyForRequest`，成功回调时再次确认 store 仍持有同一 session 对象后再提交轮次，避免知识库切换竞态把回答写进错误会话。

### 17.3 问答页气泡 UI（`AskPanel`）

页面结构：

```text
API 配置条
对话标题 / 元信息 / 清空对话
┌──────────────────────────────┬────────────┐
│ 气泡 transcript（可滚动）     │ 本轮引用   │
│  用户：右对齐青绿气泡         │ 侧栏列表   │
│  助手：左对齐深色描边气泡     │ 双击打开   │
└──────────────────────────────┴────────────┘
圆角输入框 + 发送/停止
```

交互：

- Enter 发送，Shift+Enter 换行；生成中按钮切换为「停止」。
- 右侧「本轮引用」侧栏只展示当前轮 citations；历史不沿用旧片段。
- 切换知识库 / revision 变化后，会话 UI 同步清空或切换到新 session。
- 流式 delta 追加到助手气泡；失败气泡标红，且不进入多轮历史。

布局修复（消除大片留白）：

| 问题 | 处理 |
| --- | --- |
| `BoxLayout` 将消息行 `maxHeight` 拉到 `Integer.MAX_VALUE` | 行高改为内容 preferred height，消息紧贴排列 |
| 气泡最大宽度写死 720px | 助手多行回答约铺满聊天列宽（~96%）；用户气泡 ~78% 上限并右对齐 |
| 窗口缩放后气泡不重算 | viewport resize 时 `relayoutBubbles()` 重算宽度与行高 |
| 短消息被撑成固定宽卡片 | 短消息按文本宽度收缩，长/多行助手消息优先填满列宽 |

### 17.4 验证

相关自动化：

- `ConversationModulesTest`：revision 绑定、裁剪预算、ChatRequest 不可变、历史不含引用路径。
- `AskPanelTest`：不依赖 `MainFrame` 的独立构造与 API 配置。
- `ArchitectureTest`、`KnowledgeServiceUnitTest`、`RuntimeFreshnessTest`：分层与 freshness/问答路径回归。

手工/构建：

```powershell
mvn.cmd -q -DskipTests compile
mvn.cmd -q -Dtest=AskPanelTest,ConversationModulesTest test
```

`mvn compile` 与上述测试已通过。完整回归仍建议 `.\build-and-test.cmd`。

## 18. 第四阶段检索质量基线

固定评测集位于 `examples/evaluation/retrieval-baseline.json`。每条 case 包含 `query`、`language`、`expectedDocuments`、`expectedPassages`、`mustNotReturn` 和 `category`；数据集顶层声明 Recall@5、MRR@10、nDCG@10 的最低门槛。新增或修改排名策略时应先保留旧报告，再在完全相同的数据集和模型上生成新报告进行对比。

运行评测：

```powershell
mvn.cmd -q -DskipTests package
& "$env:JAVA_HOME\bin\java.exe" -cp "target\SimpleRAG-1.0-SNAPSHOT.jar" `
  com.simplerag.bootstrap.RetrievalEvaluationMain
```

也可依次传入评测集、知识目录和报告路径。默认报告写入 `target/evaluation/retrieval-report.json`，包含 dataset/version、`RankingPolicy.version`、逐查询返回文档，以及以下聚合指标：

| 指标 | 含义 |
| --- | --- |
| Recall@5 | 前 5 个结果覆盖期望文档的比例 |
| MRR@10 | 首个期望文档在前 10 名中的倒数排名 |
| nDCG@10 | 同时考虑文档命中和期望 passage 的位置质量 |
| cold/cached latency | 每条查询首次执行与立即重复执行的平均耗时 |
| indexing time | 当前知识目录完整索引耗时 |
| memory/1000 chunks | 根据文本、身份字段和向量尺寸计算的可重复内存估算 |

`build-and-test.cmd` 已把真实 ONNX 评测作为最终门禁。任一质量指标低于数据集阈值，或 `mustNotReturn` 文档进入前 10，进程都会非零退出。性能指标本阶段进入报告但尚不设跨机器硬阈值，避免硬件差异导致误报；排名变更评审必须显式比较性能字段。

`RetrievalEvaluatorTest` 使用无模型、临时知识文件验证指标公式、阈值、禁止结果、policy version 和 JSON 输出；基础设施组装入口放在 `bootstrap`，因此未放宽现有 ArchUnit 依赖规则。

## 19. 第五阶段文件格式扩展

### 19.1 Reader registry 与统一输出

`DocumentReaderRegistry` 现在注册独立的 `DocumentReader` 策略，scanner 不再维护文件扩展名分支。默认 reader：

| readerId | 格式 | 实现 | 输入上限 |
| --- | --- | --- | --- |
| `plain-text` | Markdown、文本、配置、源码 | BOM 嗅探 + 严格 UTF-8/系统编码回退 | 2 MiB |
| `pdf-text-layer` | PDF 文本层 | PDFBox 2.0.32 | 32 MiB、最多 1000 页、最多 8 MiB 提取文本 |
| `docx` | DOCX | Apache POI 5.3.0 | 32 MiB、最多 20000 个文本单元、8 MiB 提取文本 |
| `pptx` | PPTX | Apache POI 5.3.0 | 32 MiB、最多 1000 张幻灯片、8 MiB 提取文本 |
| `xlsx-streaming` | XLSX | POI SAX/event reader | 32 MiB、256 个工作表、10 万行、100 万单元格、8 MiB 文本 |
| `html-main-content` | HTML/HTM | jsoup 1.18.3 | 5 MiB、20000 个正文块、8 MiB 提取文本 |

所有 reader 返回统一的 `ReadDocument -> DocumentSection -> DocumentTextUnit`：

```text
documentId
path + root + extension
readerId + readerVersion
logical section/page
source-addressable text units
warnings
```

`ChunkerRegistry` 只处理统一输出。普通文本继续产生 `L12-24`；PDF 产生 `第 3 页 · L1-18`；DOCX/HTML 产生 `章节：标题 · 段落7-12`；PPTX 产生 `幻灯片 4 · 行1-6`；XLSX 产生 `工作表：Config · 行2-9`。`DocumentReference`、搜索结果、问答引用和远程 RAG prompt 都使用同一个 `sourceLocation`。

### 19.2 增量索引与格式版本

索引格式升级为 v5。`DocumentIndexEntry` 除文件 fingerprint 外还保存每个文件实际使用的 `readerId + readerVersion`。`IncrementalIndexPlanner` 按文件比较 reader 身份：只升级 PDF reader 时只重建 PDF，未变化的纯文本、DOCX 等仍可复用 chunks/embeddings。分块版本升级为 2，以纳入 section 边界和新的 source location 身份。

v4 及更早 snapshot 不会被当作 v5 直接复用；加载后按现有不兼容流程要求重建，避免旧 chunk 缺少页码/section 信息。

### 19.3 失败隔离与资源安全

- 加密/需要密码的 PDF 明确跳过；没有文本层的 PDF 提示未启用 OCR。
- PDFBox 使用 mixed memory/temp-file 模式，并限制页数和提取文本量。
- OOXML 统一启用 `ZipSecureFile` inflate ratio、单 entry 展开大小和 XML 文本限制。
- XLSX 使用 SAX/event 模式，不把完整 workbook materialize 到内存。
- HTML 只解析本地文件，不访问外部资源；移除 script/style/nav/footer/aside/form，并优先提取 main/article/role=main。
- 损坏、过大、无法访问或提取为空的文档只产生 `IndexBuildWarning`；其他文件继续构建完整 snapshot。
- 构建完成后 Swing 显示跳过数量和逐文件原因。警告不会绕过临时文件、原子移动、SQLite 条件发布或 freshness gate。

本阶段仍不启动 OCR、LibreOffice 或其他外部进程。

### 19.4 验证

`DocumentReaderTest` 运行时生成 PDF、加密 PDF、DOCX、PPTX、XLSX 和 HTML fixture，验证文本提取与页码/section 定位；同时验证损坏 PDF 只被跳过且健康文档仍进入 snapshot。`DocumentScannerTest` 覆盖 reader 大小上限的结构化警告，`IncrementalIndexTest` 覆盖单个 reader 版本变化只重建对应格式。

快速回归：

```powershell
mvn.cmd -q test
```

完整验证仍使用：

```powershell
.\build-and-test.cmd
```

## 20. 第六阶段规模与性能

### 20.1 先测量再选择索引结构

本阶段没有引入 HNSW。当前固定课程数据集远低于约 10 万 chunks，真实 ONNX 质量回归的 cold query 为 9.315 ms、cached query 为 1.065 ms，尚无证据表明线性扫描是主要瓶颈。继续使用精确扫描也避免了 ANN recall 损失、额外文件身份和发布一致性问题；未来只有在固定大数据集 profile 证明内存扫描占主导时，才评估与同一 `IndexManifest + revision` 绑定的 ANN 文件。

`IncrementalIndexBuilder` 的 `IndexReport.stageMillis` 记录：

```text
scan
fingerprint
readAndChunk
embedding
assemble
total
```

发布完成的诊断事件同时记录 files、chunks 和这些阶段耗时。该数据不会改变构建流程：增量复用、完整 snapshot、临时文件、原子移动、SQLite 条件发布和 freshness gate 保持原样。

### 20.2 模型签名缓存

旧路径在进程启动后的首次 `embeddingProvider.signature()` 会完整读取 ONNX 与 tokenizer 并计算 SHA-256。`ModelFileSignatureCache` 现在把每个文件的规范路径、size、mtime、filesystem identity 和 SHA-256 写入 `%USERPROFILE%\.simplerag\cache\model-signatures.json`。元数据任一变化就重新读取完整文件；缓存 JSON 损坏时也直接重算，不把不可解析内容当作可信签名。缓存更新使用同目录临时文件和原子移动。

`PerformanceBenchmarkMain` 固定记录 dataset path/signature、文件数、OS、Java、logical processors、max heap，以及 legacy full hash 和 steady-state cache hit。默认输出 `target/performance/performance-report.json`。

2026-07-12 实测：

| 项目 | 值 |
| --- | --- |
| 数据集 | `examples/knowledge`，5 files，SHA-256 `a4281c26b86537f6b3e51e1beef8fad134fa04698ce9ab8eb3d06546b58e18de` |
| 硬件/JVM | Windows 11 10.0，Java 17.0.8，16 logical processors，max heap 4,242,538,496 bytes |
| 优化前 | 115.5649 ms |
| 优化后 | 23.7596 ms |
| 加速比 | 4.864x |
| 正确性 | `signatureEquivalent=true` |

同轮真实 ONNX 质量报告已扩充为 8 个查询（含自然语言代码定位和符号定义定位）：Recall@5 1.000、MRR@10 1.000、nDCG@10 0.991，禁止结果未进入前 10。`ModelFileSignatureCacheTest` 覆盖缓存命中和文件变化失效。

## 21. 第七阶段安全与可运维性

### 21.1 凭据与远程发送边界

`WindowsCredentialManagerSecretStore` 使用 JNA 调用 Windows `CredWriteW/CredReadW/CredDeleteW`，把 API Key 保存为当前 Windows 用户的 Generic Credential `SimpleRAG/API`。SQLite `api.encrypted_key` 对新保存值只持有 `wincred:v1:SimpleRAG/API` marker。已有 AES-GCM ciphertext 仍可读取；非 Windows 或 Credential Manager 调用失败时才使用 `SecretCodec` fallback，并只记录 backend 类型，不记录 key 或错误参数。

`ApiConfig` 拒绝非 HTTP(S)、缺少 host 或带 `user:password@host` 的 URL。规范 host（含显式端口）用于 allowlist 和发送确认，不记录完整 URL path/query。

远程问答顺序为：

```text
identity/READY gate
  -> local-only policy gate
  -> current-turn retrieval
  -> citations published to UI
  -> freshness gate
  -> RemoteSendReview
  -> blocking user authorization on EDT
  -> ChatRequest / HTTP
```

`RemoteSendReview` 只包含 knowledge-base identity/name、revision、host、trusted 标志和本轮 citation views。确认框显示准确 chunk 数与文件/sourceLocation，可选择仅本次、信任 host 并发送、取消。即使 host 已信任，每一轮仍显示确认；信任只改变风险标记。每知识库策略 `rag.local_only.<knowledgeBaseId>` 在 `AskUseCase` 内执行，UI checkbox 不是唯一防线。

### 21.2 本地诊断事件与导出

`InMemoryDiagnosticLog` 是容量 300 的进程内 ring buffer。已接入事件：

- index build started/completed/failed/cancelled；
- freshness changed；
- stale task discarded；
- vector fallback reason；
- RAG blocked reason；
- remote adapter latency、host、operation 与 error category；
- credential backend/fallback 状态。

事件属性只允许身份、revision、状态、host、计数、耗时、阶段和异常类别。`DiagnosticEvent` 对 Bearer、Authorization 和 API Key 形态做二次脱敏并限制 message 长度。OpenAI adapter 不记录 request body、prompt、response body、citation text 或 key。

`DiagnosticReportService` 位于 application 层，读取 `ActiveKnowledgeRuntime` 和 `DiagnosticLog`，生成 schema v1 JSON：当前/发布 revision、index/freshness 状态、最后核对时间、manifest 摘要、Java/OS 和事件。Swing 只展示服务返回的 JSON，不依赖 `IndexHandle`/`SemanticSearchEngine`，因此现有 ArchUnit 边界仍成立。导出使用 `Files.writeString(..., UTF_8)`。

### 21.3 验证

```powershell
mvn.cmd -q test
mvn.cmd -q package
& "$env:JAVA_HOME\bin\java.exe" -cp "target\SimpleRAG-1.0-SNAPSHOT.jar" `
  com.simplerag.bootstrap.PerformanceBenchmarkMain
& "$env:JAVA_HOME\bin\java.exe" -cp "target\SimpleRAG-1.0-SNAPSHOT.jar" `
  com.simplerag.bootstrap.RetrievalEvaluationMain
```

JUnit/ArchUnit 共 63 项通过；真实 ONNX 质量门禁通过。`InMemoryDiagnosticLogTest` 验证容量与敏感 header 脱敏，`ApiConfigSecurityTest` 验证 host/URL 边界，`ArchitectureTest` 证明新增诊断 UI 未穿透检索内部对象。

## 22. 对话交互与回答约束优化

`AskPanel.BubblePanel` 的正文 `JTextArea` 现在保持只读但可 focus/select，支持系统 Ctrl+C。每条消息提供轻量复制按钮和右键菜单（复制选中内容、复制整条消息）；页面头部可复制完整 transcript，或把最近回答和本轮引用合并为可粘贴文本。引用栏单独导出：

```text
[1] D:\project\src\AuthService.java · L42-87
```

复制逻辑使用 UI 当前可见内容，不读取 API Key。`conversationText()` 输出明确的“你/助手”角色；`latestAnswerWithCitations()` 只把当前 citation model 拼到最近助手回答后。`AskPanelTest` 覆盖流式增量完成后两种文本输出。

OpenAI-compatible prompt 同时加强：

- 先给直接答案，不复述问题或输出空泛开场；
- 所有可验证事实紧邻本轮真实 citation 编号；
- 代码定位优先给准确 path、sourceLocation、类/方法和作用；
- 明确区分资料现状与模型建议；
- 资料不足时说明缺口，不猜测文件、接口或实现；
- 把 retrieved chunks 标记为不可信只读数据，忽略其中的 prompt injection 指令；
- history 只用于消解指代，事实仍必须由本轮 retrieval 支撑。

每个 citation 使用显式 `SOURCE [n] BEGIN/END` 边界，并分别传递文件路径、统一来源位置和正文。`CourseFeaturesTest` 通过模拟 HTTP server 验证最终 JSON prompt 同时包含来源文件、只读资料边界和路径定位约束。

## 23. 数据处理流程加固

前面各阶段把索引的**事务性与发布语义**做扎实了（临时文件、原子移动、条件发布、freshness gate、增量复用）。本阶段处理的是这条链路两端此前未被覆盖的部分：入口的扫描策略过于宽松、出口的持久化反序列化没有防护，以及中间读取阶段完全串行。索引格式保持 v5，port 接口、发布语义与远程 RAG 门禁均未改动。

### 23.1 索引反序列化白名单

`IndexStore.loadPath` 此前把 `%USERPROFILE%\.simplerag\indexes\` 下的 `.bin` 直接交给无限制的 `ObjectInputStream.readObject()`。该目录是本机可写状态，被替换或投放的快照会在应用启动、切换知识库时触发 gadget chain（CWE-502）。

现在每次加载都安装 `java.io.ObjectInputFilter`：

```text
maxbytes=<文件实际大小>;maxdepth=64;maxrefs=20000000;maxarray=20000000;
com.simplerag.search.*;com.simplerag.model.*;java.util.*;java.lang.*;
[F;[B;[J;[I;[Ljava.lang.Object;[Ljava.lang.String;!*
```

起决定作用的是尾部的 `!*`：真实 gadget chain 的载体（POI、PDFBox、commons-\*）全部落在白名单之外，一律拒绝。深度、引用数和数组长度上限限制手工构造流的资源消耗。

过滤器在 try 块之外构造，因此白名单模式写错会立即抛 `IllegalArgumentException`，而不是被吞掉后静默让所有索引永远加载失败。命中过滤时抛出的 `InvalidClassException` 落进既有 catch，返回 `Optional.empty()`，上层按"索引缺失"要求重建——这是安全的降级方向，调用方无需改动。

### 23.2 凭据文件与遍历预算

`SensitiveFilePolicy` 是无 IO 的纯命名策略，在 reader 查找**之前**生效：

| 规则 | 覆盖 |
| --- | --- |
| 文件名 | `.env`、`id_rsa`/`id_dsa`/`id_ecdsa`/`id_ed25519`、`.netrc`/`_netrc`、`.npmrc`、`.pypirc`、`credentials`、`.htpasswd`、`.git-credentials`、`secrets.yaml`/`.yml`/`.json` |
| 扩展名 | `pem`、`key`、`p12`、`pfx`、`jks`、`keystore`、`kdbx`、`ppk`、`asc`、`gpg` |
| 名称前缀 | `.env.`（覆盖 `.env.local`、`.env.production`） |
| 目录名 | `.ssh`、`.gnupg`、`.aws`、`.azure`、`.kube` |

这条规则的必要性来自项目自身的威胁模型：召回片段正文会随远程 RAG 发出，而 plain-text reader 收 `.properties`/`.conf`/`.json`/`.yaml`，本机密钥因此存在被切成 chunk 再送出机器的路径。命中规则的文件产出 `skipped=true` 的逐条警告——它们本可索引，跳过属于策略决定，应计入构建报告的跳过数并对用户可见。

`ScanBudget` 限制单次扫描的文件数（默认 5 万）、总字节（默认 8 GiB）和目录深度（默认 32）。快照全量驻留内存，误选驱动器根目录此前会在构建可被取消之前耗尽堆。触顶时停止遍历并产出 `skipped=true` 警告；深度上限同时消除了 Windows 目录联接可能造成的无限递归。

`DocumentScanner.scan` 还会折叠被包含的数据源根：`C:\x` 与 `C:\x\y` 同时配置时，后者被丢弃，避免同一文件在两个相对路径下各索引一次导致 chunk 与召回重复。

原先被完全静默丢弃的两类文件现在聚合上报，均为 `skipped=false`（它们从不可索引，不应污染"本可索引却失败"的计数）：

```text
跳过 143 个不支持格式的文件（png 88、jpg 30、class 25）
跳过 4 个符号链接或联接点（不跟随，避免遍历环路与越权读取）
```

### 23.3 并行读取与哈希快路径

`IncrementalIndexBuilder` 的读取阶段改为线程池并行，池大小 `max(1, min(可用核心数 - 1, 8))`。正确性依赖两点：readers 每次调用新建 `PDDocument`/`OPCPackage` 且 `ChunkerRegistry` 全静态无状态，因此只有结果收集顺序需要固定——结果按原扫描下标回收，chunk 顺序、chunk 身份与警告顺序都与串行读取逐位一致，`incrementalSnapshotMatchesFullRebuild` 因此仍是有效门禁。取消语义通过显式的 cancelled 结果向上传递，不会把已取消的构建静默降级成部分索引。

`FileFingerprint.capture(document, previous)` 增加快路径：size 与 mtime 都与上一已发布 entry 一致时沿用其 hash，跳过整文件读取；任一不一致仍算真 hash。这是 git/rsync 的同款判据。此前每次构建都要对每个文件做全量 SHA-256，包括那些马上就会被判定为"复用"的文件。

该优化对增量等价性透明：被修改文件的 size/mtime 必变，新增文件无 previous entry，两者都走真 hash；未变文件沿用的旧 hash 本身就是真 hash。`simplerag.index.verifyContentHash=true` 强制全量校验，测试用它证明两条路径结果一致。

`IndexReport.stageMillis` 新增 `fingerprint` 阶段（此前哈希开销被并入 `readAndChunk`），使这项优化在应用自身的诊断报告里可见。

2026-07-27 实测（Windows 11，Java 17，16 logical processors，5 次取最优）：

| 语料 | readAndChunk 串行 → 并行 | fingerprint 全量 → 快路径 |
| --- | --- | --- |
| 项目源码，137 files / 965 KB | 94 → 35 ms（2.69x） | 42 → 22 ms（1.91x） |
| 复制语料，2740 files / 19 MB | 919 → 542 ms（1.70x） | 769 → 397 ms（1.94x） |
| 大文件语料，50 files / 54 MB | 758 → 244 ms（3.11x） | 74 → 10 ms（7.40x） |

中间一行的并行加速反而更低：几千个小文本文件里每文件的 open/stat 开销占主导，文件系统层自身在串行化，CPU 并行帮不上忙。第三行则确认哈希快路径的收益随**字节数**而非文件数放大，这正是它针对的场景（含大 PDF/Office 文档的知识库）。

### 23.4 编码与格式覆盖

`PlainTextDocumentReader` 先嗅探 BOM（`EF BB BF` → UTF-8，`FF FE` → UTF-16LE，`FE FF` → UTF-16BE）并剥离，无 BOM 时维持原有的"严格 UTF-8 → 平台默认"两级回退。此前 UTF-8 签名会把 `U+FEFF` 泄漏进首个词法单元污染 TF-IDF 特征，UTF-16 文档则解码成控制字符后被 `looksBinary` 丢弃。

`PlainTextDocumentReader.VERSION` 因此从 1 提升到 2。按 19.2 的按 reader 收敛规则，这只触发 plain-text 负责格式的重建，PDF/DOCX 等仍可复用。

扩展名补齐：`csv`、`tsv`、`proto`、`graphql`、`gql`、`tf`、`tfvars`、`hcl`、`lua`、`r`、`pl`、`pm`、`ex`、`exs`、`erl`、`dart`、`groovy`；文件名补齐 `gemfile`、`rakefile`、`procfile`、`vagrantfile`、`justfile`、`taskfile`。其中代码类同步加入 `ChunkerRegistry.CODE_EXTENSIONS` 以走重叠窗口分块。

`ChunkerRegistry.CHUNKING_VERSION` **不提升**：新扩展名此前从未入过索引，没有存量条目的分块结果发生变化，提升它只会触发一次无意义的全库重建。

### 23.5 新增系统属性

| 属性 | 默认 | 作用 |
| --- | --- | --- |
| `simplerag.index.includeSensitiveFiles` | `false` | 置 `true` 关闭凭据文件排除（知识库本身就是凭据样例文档时使用） |
| `simplerag.index.verifyContentHash` | `false` | 置 `true` 强制全量 SHA-256，绕过 size/mtime 快路径 |
| `simplerag.index.readThreads` | 核心数 − 1，上限 8 | 覆盖读取并发；置 `1` 恢复完全串行 |
| `simplerag.index.maxFiles` | `50000` | 单次扫描文件数上限 |
| `simplerag.index.maxTotalBytes` | `8 GiB` | 单次扫描总字节上限 |
| `simplerag.index.maxDepth` | `32` | 目录遍历深度上限 |

### 23.6 验证

```powershell
mvn.cmd -q test
.\build-and-test.cmd
```

JUnit/ArchUnit 共 79 项通过。新增 `IndexStoreSecurityTest`（3 项）；`DocumentScannerTest` 从 1 项扩到 7 项；`IncrementalIndexTest`、`DocumentReaderTest` 各新增 1 项。

`IndexStoreSecurityTest` 的关键设计是 `com.simplerag.probe.UnexpectedGadget`——一个故意置于白名单之外的可序列化类，在 `readObject` 中置位静态标志。断言 `deserialized == false` 才能证明过滤器拦在**类解析阶段**；仅断言 `Optional.empty()` 无法区分"过滤器生效"和"最终类型不匹配"，两者都会返回空。

回归门禁全部保持：`IndexConsistencyTest` 11/11、`RuntimeFreshnessTest` 5/5、`ArchitectureTest` 10/10。真实 ONNX 质量门禁 Recall@5 1.000、MRR@10 1.000、nDCG@10 0.991，说明 reader 版本提升与扩展名补齐未影响检索质量。

新增类全部位于 `..search..` 包内并复用既有的 `IndexBuildWarning` 上报通道，未新增依赖、未改动 port 接口，因此现有 ArchUnit 规则无需放宽。


## 24. 混合检索、重排与分块 v3（2026-08-05）

检索链路拆为独立 BM25 和 dense candidate generators。两路各取最多 50 个候选，以 RRF k=60 融合；默认策略 RRF_RERANK 再把前 20 个候选交给 SecondStageReranker。当前 FeatureReranker 综合归一化 BM25、dense、RRF、文件元数据、声明与代码意图信号，不需要远程服务；接口保留了替换 ONNX Cross-Encoder 的边界。SemanticSearchEngine 暴露 BM25、DENSE、RRF、RRF_RERANK 四种策略供同语料消融。

ChunkerRegistry.CHUNKING_VERSION 提升到 3。普通文档以约 320 token 为目标、40 token 重叠并优先在自然段边界结束；代码识别 class/interface/record/enum、常见函数和方法以及 JavaScript arrow function 声明，长符号才退化到 token 窗口。embedding 输入增加 fileName 和 sourceLocation，使文件名、父章节、符号与行号参与语义表示。

ContextSelector 在生成上下文前执行 MMR，多样性选择中每个文档最多两个片段；随后按同父章节扩展相邻块，拒绝重叠行号并把单个扩展上下文限制为 2800 字符。IterativeRetrieval 额外拒绝同文件重叠达到 50% 的引用范围，且每个文档最多累计三个引用。

评测集扩到 45 条并增加 dev/test、category、hard negatives 和 answerable 字段。报告新增 Recall@20、HitRate@5、Precision@5、文档唯一率、分类聚合、hard-negative 排序信号和 P50/P95；RetrievalEvaluationMain 同时输出默认策略报告和四策略消融报告。本机真实 ONNX 门禁结果为 Recall@5 1.000、Recall@20 1.000、HitRate@5 1.000、MRR@10 1.000、nDCG@10 0.956。

## 25. 推理模型下的自主检索修复与思维链外显（2026-09-01）

### 25.1 问题：自主检索从未真正触发过

用户反馈"AI 自己检索自己用没有触发过"。接线是对的（`AppCompositionRoot` → `AskController` → `AskUseCase.askStream` → `IterativeRetrieval.collect` → `OpenAiCompatibleClient.planRetrieval`），`IterativeRetrievalTest` 与 `OpenAiCompatibleClientTest` 当时也全绿——故障只发生在运行时，且**只发生在带思维链的推理模型上**。

失效链条是：规划轮写死 `PLANNER_MAX_TOKENS = 512` → 模型的思维链先把这份预算耗尽 → 响应带 `finish_reason=length` → `parseRetrievalDecision` 判为 `unavailable` → `IterativeRetrieval` 静默 `break`。

结果是一个只对强模型生效的退化：**模型自主生成检索词的能力从第 2 轮才存在，而第 2 轮从未成功过**。第一轮永远用用户原话检索，看起来一切正常。

这条链路上没有任何一处读取 `reasoning_content`，所以思维链既烧掉了 token 预算，又一个字都没到用户眼前——这也是它能长期隐身的原因。

### 25.2 第二层：缓冲响应让预算修复变成超时

把上限提到 4096 后，真实诊断报告给出的不是成功而是新的失败：

```text
"message" : "retrieval-plan",
"outcome" : "HttpTimeoutException",
"latencyMs" : "60028"
```

决定性对比在同一份报告里：同一模型的回答调用 `chat-stream` **6.6 秒**产出 526 个 completion token（约 80 tok/s），规划轮却 60 秒没有返回。

两条差异共同解释了它。其一，规划轮当时是非流式的，而 `HttpRequest.timeout()` 对缓冲响应覆盖的是**整个响应体**；回答调用走 `BodyHandlers.ofInputStream()`，超时只算到响应头，所以从不撞这堵墙。其二，按实测速率，思考满 4096 token 恰好约 51 秒——512 → 4096 并没有解决问题，只是把"被截断"换成了"想得更久"，然后撞上时钟。

单纯再抬超时无法收敛：一轮问答最多 3 次规划，每次一分钟。

### 25.3 规划轮：流式化并默认关闭思考

`planRetrieval` 现在发 `stream:true`（含 `stream_options.include_usage`）。这既绕开整响应超时，又把 `PLANNER_TIMEOUT`（60s）**重新用作读取截止时间**——绕过请求超时不能换来一个可以无限挂起的连接，所以 60 秒仍是硬上限，只是它现在计的是"真的没有数据"而不是"模型还在思考"。

第一次尝试默认带 `enable_thinking:false` + `reasoning_effort:"low"`。规划只需要吐出一个很小的 JSON，思维链在这里买不到任何东西，却要花掉整整一分钟墙钟。这两个字段是厂商私有扩展而非 OpenAI schema 的一部分，严格网关会直接 4xx；因此回退方向是：**被拒绝就用标准 schema 重发一次**，只有在那条回退路径上、且结果仍被截断时，才再走一次"关思考重试"。

`readPlannerResponse` 同时接受两种响应形状：老实流式的 SSE 帧，以及**悄悄忽略 `stream:true` 直接返回单个 JSON 对象**的服务端。"OpenAI 兼容"不保证其中任何一种，且缓冲那条路径正好被既有的几条规划测试覆盖。

`PLANNER_MAX_TOKENS` 保留在 4096：回退路径上思考仍是开着的，需要这份余量。

### 25.4 思维链外显

新增 `application.conversation.AnswerDelta`，把原先的 `Consumer<String> onDelta` 换成带 stage 的事件流：

| stage | 含义 |
| --- | --- |
| `PLANNING` | 检索规划的思考，以及**它自己生成的检索词** |
| `ANSWER_THINKING` | 生成最终答案前的思维链 |
| `ANSWER` | 答案正文 |

该类型放在 `application.conversation`（与 `ChatMessage`/`RetrievalDecision` 同包），因为 port.out 和 adapter 都要用它，而 ArchUnit 禁止 application 依赖 adapter。签名贯通 `AskKnowledge`、`ChatModel`、`AskUseCase`、`KnowledgeService`、`AskController` 与 `DesktopWorkspaceController`。

适配层按两种厂商约定解析：`extractReasoning` 依次尝试 `reasoning_content`（DeepSeek / Qwen / vLLM / 硅基流动）和 `reasoning`（OpenRouter）；`ThinkTagSplitter` 处理把思维链内联在 `content` 里的 `<think>…</think>` 服务端（Ollama / LM Studio）。后者必须跨 SSE 分片保持状态——`<thi` 和 `nk>` 常常分属两帧，把半个标签当正文发出去会污染每一轮回答。它还有一条克制规则：**只有在尚未产出任何正文时才认开标签**，此后出现的 `<think>` 更可能是被索引文档里的字面量，吞掉它会吃掉答案。

`RagAnswer` 与 `AskResultView` 各增 `reasoning` 字段，供 SSE 兜底到非流式时补上思考。**`text()` 始终不含思维链**——多轮历史、剪贴板与引用导出全部依赖它。

### 25.5 让"自主检索"变成可观测的

`IterativeRetrieval` 每轮规划后主动播报，不依赖模型的流式支持——这些内容直接由 `decision.queries()` 拼出：

```text
〔第 2 轮检索规划〕
证据里只有 Chunker，缺少发布环节
〔第 2 轮检索〕
  关键词：ChunkerRegistry 注册
  假设文档：索引发布时会写入 manifest
```

规划失败同样被明确命名（`〔检索规划不可用：…〕`）。这正是当初让问题隐身的盲点：`UNAVAILABLE` 与 `ANSWER` 都停止循环，但只有后者意味着"模型认为证据够了"，而用户此前无法区分二者。

UI 侧 `AskPanel.BubblePanel` 在正文上方加折叠思考区，流式时展开、正文一开始自动收起。`measure()` 必须计入其高度——该面板自报 preferred size，没被算进去的部分不会被绘制。复制按钮与右键菜单继续只取正文。

### 25.6 验证

```powershell
mvn.cmd -q test
```

JUnit/ArchUnit 共 163 项通过。新增 `ThinkTagSplitterTest`（6 项，含跨分片标签、流末残缺标签、答案内字面量 `<think>`）；`OpenAiCompatibleClientTest` 8 → 13 项（SSE 思维链分流、内联 `<think>`、流式规划决策跨帧重组、网关拒绝私有字段后回退）；`IterativeRetrievalTest` 9 → 11 项（规划播报、失败命名）；`AskPanelTest` 2 → 3 项（思考区收起且不进入正文与 transcript）。

一条既有断言 `"stream":false` 被改为 `"stream":true`：它断言的正是本节推翻的前提。

`ArchitectureTest` 10/10 保持，未放宽任何依赖规则。

远端行为只能在真实网关上确认，判据是诊断报告里 `retrieval-plan` 事件的 `outcome=ok` 且单轮出现多条；`thinking-switch-rejected` 表示网关不接受私有字段并已回退，`stream-deadline` 表示流式读取仍触顶。

### 25.7 已知遗留

`KnowledgeService` 仍保留一份实现同一 `AskKnowledge` 接口的单次检索版 `askStream`（自述 "Single-shot path: one search, one send"），与 `ChatModel.planRetrieval` 默认返回 `answer()` 一样，都是"换错一行装配就整体静默失效"的暴露面。本次只做签名适配，未删除。
