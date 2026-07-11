# P0 差距优化方案

## Summary

针对从"资料扫描→图谱构建→QA问答→方案生成"全流程的 3 个 P0 差距进行修复，使系统更接近两个战略目标：
1. 建立图谱，指导从需求到编码的过程
2. 取代人工，自动分析需求，给出可落地方案

三个 P0 差距：
- **G1 跨层链接完整**：影响子图 BFS 白名单漏掉 `EXPOSED_BY` 和 `MAPS_TO`，导致影响分析路径断裂
- **G2 文档接入图谱**：事实抽取路径仍走纯文本切块，文档结构信息（标题/章节）未进入图谱
- **G6 方案到代码级**：方案步骤只有文件级粒度，缺少代码片段，离"取代人工"差距最大

## Current State Analysis

### G1 当前状态
- `EdgeType.java` 已定义 `EXPOSED_BY`、`MAPS_TO`、`IMPLEMENTED_BY`、`HANDLED_BY` 共 36 种边类型
- 边已在 `GraphBuilder`、`BusinessGraphBuilder`、`FeatureMappingStep` 中创建
- `TraversalDirection`、`FeatureSliceBuilder`、`GapFinderService` 遍历时已使用
- **缺口**：`ImpactSubgraphService.java:39-47` 的 `EDGE_WHITELIST` 只有 8 个边类型，漏掉了 `EXPOSED_BY` 和 `MAPS_TO`

### G2 当前状态
- `StructureAwareChunkService` 和各 `Partitioner` 已实现（Task 4/5）
- `DocExtractStep.java:848-883` 的 `vectorizeDocument` 方法通过 Feature Flag 控制是否使用结构感知切块
- **缺口**：事实抽取路径 `extractFromChunksWithCoverage`（第 318-414 行）始终走纯文本 `splitContent`，不调用 `StructureAwareChunkService`，文档结构信息（标题层级、章节路径）未进入事实抽取上下文

### G6 当前状态
- `SolutionPlanStep.java` 有 8 个字段：title/description/filePath/symbolName/evidenceIds/actionType/testDescription/rollbackDescription
- `solution-planning.txt` prompt 约束每步含文件路径+符号+证据 ID，但不要求生成代码片段
- `SolutionVerifier.java` 已实现 6 类校验（文件/符号/高风险/测试/证据/阻塞问题），但无代码片段校验
- **缺口**：缺少 `codeSnippet` 字段，prompt 未要求生成代码片段，verifier 未校验代码片段与图谱中已有符号签名的一致性

## Proposed Changes

### Change 1: G1 — 补全影响子图边类型白名单

**文件**：`backend/src/main/java/io/github/legacygraph/service/requirement/ImpactSubgraphService.java`

**What**：在 `EDGE_WHITELIST` 中添加 `EXPOSED_BY` 和 `MAPS_TO`

**Why**：`EXPOSED_BY` 连接 Feature→Page/ApiEndpoint，`MAPS_TO` 连接 BusinessObject→Table。缺少这两个边类型导致影响分析无法穿越业务层→功能层→API 层和业务对象→数据表的跨层路径。

**How**：
```java
static final List<String> EDGE_WHITELIST = List.of(
        EdgeType.CALLS.name(),
        EdgeType.READS.name(),
        EdgeType.WRITES.name(),
        EdgeType.DATA_FLOW.name(),
        EdgeType.HANDLED_BY.name(),
        EdgeType.IMPLEMENTED_BY.name(),
        EdgeType.EXPOSED_BY.name(),      // 新增：Feature→Page/ApiEndpoint
        EdgeType.MAPS_TO.name(),          // 新增：BusinessObject→Table
        EdgeType.BELONGS_TO.name(),
        EdgeType.DEPENDS_ON.name());
```

### Change 2: G2 — 事实抽取接入文档结构信息

**文件 1**：`backend/src/main/java/io/github/legacygraph/task/step/DocExtractStep.java`

**What**：在 `extractFromChunksWithCoverage` 方法中，当 `partitionEnabled=true` 时，使用 `DocumentPartitionService` + `StructureAwareChunkService` 替代纯文本 `splitContent`，将 headingPath 前缀注入每个 chunk 的内容中。

**Why**：当前事实抽取走纯文本切块，LLM 无法获知文档结构（如"这个段落属于'结算需求-验收条件'章节"），导致抽取的事实缺少上下文归属。

**How**：
- 在 `extractFromChunksWithCoverage` 方法开头增加 `partitionEnabled` 分支
- 开启时：调用 `partitionAndChunk(content, filePath)` 获取结构化切块
- 每个 chunk 的 content 前缀已包含 `[headingPath[0] > headingPath[1]]` 格式
- 将切块传入 LLM 抽取流程，替代 `splitContent` 的结果
- 关闭时：保留现有 `splitContent` 路径不变

**文件 2**：`backend/src/main/resources/application.yml`

**What**：将 `legacygraph.document.partition.enabled` 默认值从 `false` 改为 `true`

**Why**：结构感知切块已通过充分测试（Task 4/5 共 123 个测试），可以默认开启

### Change 3: G6 — 方案步骤增加代码片段

**文件 1**：`backend/src/main/java/io/github/legacygraph/dto/solution/SolutionPlanStep.java`

**What**：新增 `codeSnippet` 和 `codeLanguage` 字段

**Why**：当前方案步骤只告诉"改哪个文件的哪个方法"，不告诉"改成什么"。新增代码片段字段后，方案可提供接近可执行的代码级指导。

**How**：
```java
/** 代码片段（MODIFY 时为修改后的代码，CREATE 时为新代码，DELETE 时为空） */
private String codeSnippet;

/** 代码语言（java/xml/sql/vue/ts 等） */
private String codeLanguage;
```

**文件 2**：`backend/src/main/resources/prompts/solution-planning.txt`

**What**：修改 prompt，要求每个 MODIFY/CREATE 步骤生成代码片段

**Why**：让 LLM 基于影响子图中的已有符号签名和调用关系，生成可落地的代码片段

**How**：
- 在 prompt 约束中增加：每个 MODIFY 步骤必须包含 `codeSnippet`（修改后的完整方法/类代码）和 `codeLanguage`
- CREATE 步骤必须包含完整的代码片段
- DELETE 步骤的 codeSnippet 为空
- 约束：代码片段必须基于影响子图中已有符号的签名，不得虚构不存在的类或方法
- 新增模板变量 `{impactSignatures}`：传入影响子图中关键节点的符号签名（类名、方法签名、字段列表），作为代码生成的上下文

**文件 3**：`backend/src/main/java/io/github/legacygraph/service/solution/SolutionPlanner.java`

**What**：
- 新增 `loadImpactSignatures` 方法，从影响子图结果中提取关键节点的符号签名
- 新增模板变量 `impactSignatures`
- 将签名信息传入 LLM 调用

**Why**：LLM 生成代码片段需要知道已有符号的签名（方法参数、返回类型、字段列表），否则会虚构不存在的签名

**How**：
```java
// 从 ImpactResult 中的 impactedNodes 提取签名
// 查询 Neo4jGraphDao 获取节点的 properties（signature/parameters/returnType/fields）
// 组装为 JSON: [{nodeKey, nodeType, nodeName, signature, fields}]
```

**文件 4**：`backend/src/main/java/io/github/legacygraph/service/solution/SolutionVerifier.java`

**What**：新增第 7 类校验 `checkCodeSnippetConsistency`

**Why**：验证代码片段中引用的类名/方法名在图谱中存在（MODIFY 步骤），或符合命名规范（CREATE 步骤）

**How**：
- MODIFY 步骤：从 codeSnippet 中提取类名和方法名，检查是否在影响子图的 impactedNodes 中
- CREATE 步骤：检查 codeSnippet 非空
- 错误码：`CODE_SNIPPET_INCONSISTENT`（MODIFY 引用了不存在的符号）、`CODE_SNIPPET_EMPTY`（CREATE/MODIFY 缺少代码片段）

### Change 4: 补充测试

**文件**：
- `backend/src/test/java/io/github/legacygraph/service/requirement/RequirementImpactSubgraphServiceTest.java` — 新增 EXPOSED_BY 和 MAPS_TO 边遍历测试
- `backend/src/test/java/io/github/legacygraph/task/step/DocExtractStepTest.java` — 新增 partitionEnabled=true 时事实抽取走结构化切块的测试
- `backend/src/test/java/io/github/legacygraph/service/solution/SolutionVerifierTest.java` — 新增 codeSnippet 一致性校验测试
- `backend/src/test/java/io/github/legacygraph/service/solution/SolutionPlannerTest.java` — 新增 loadImpactSignatures 测试

## Assumptions & Decisions

1. **G6 代码片段粒度**：生成方法级代码片段（完整方法代码），不生成类级或行级 diff。理由：方法级是可审查的最小单元，太粗（类级）缺乏指导性，太细（行级 diff）LLM 准确率低。
2. **G6 代码片段不自动执行**：代码片段作为方案的一部分供开发者参考，不自动创建 PR。自动执行是 G8 的工作，本次不做。
3. **G2 默认开启结构感知切块**：已通过 123 个测试验证，风险可控。如果出问题会自动降级到旧路径（DocExtractStep 已有 try-catch 降级）。
4. **G1 白名单扩展不删除现有边类型**：只新增，不删除，保证向后兼容。
5. **不修改 SolutionPlanStep 的 JSON 序列化兼容性**：新增字段使用默认值，旧数据反序列化不受影响。

## Verification

1. `mvn clean test` 全量通过，0 Failures，0 Errors
2. ImpactSubgraphService 白名单包含 10 个边类型
3. DocExtractStep 在 `partitionEnabled=true` 时事实抽取走 StructureAwareChunkService
4. SolutionPlanStep 新增 codeSnippet/codeLanguage 字段
5. solution-planning.txt prompt 要求生成代码片段
6. SolutionVerifier 新增 CODE_SNIPPET 一致性校验
7. 前端 SolutionReview.vue 展示代码片段（使用代码高亮组件）

## Tasks

1. G1: ImpactSubgraphService 白名单补全 EXPOSED_BY + MAPS_TO + 测试
2. G2: DocExtractStep 事实抽取接入结构感知切块 + application.yml 默认开启 + 测试
3. G6-1: SolutionPlanStep 新增 codeSnippet/codeLanguage 字段
4. G6-2: SolutionPlanner 新增 loadImpactSignatures + 模板变量
5. G6-3: solution-planning.txt prompt 增加代码片段生成约束
6. G6-4: SolutionVerifier 新增 codeSnippet 一致性校验 + 测试
7. G6-5: 前端 SolutionReview.vue 展示代码片段
8. 集成验证: mvn clean test 全量通过
