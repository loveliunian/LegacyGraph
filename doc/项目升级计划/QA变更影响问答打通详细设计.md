# QA 变更影响问答打通 — 详细设计

> 文档版本：v1.0
> 日期：2026-07-06
> 范围：LegacyGraph QA 问答模块 × 变更影响分析模块
> 依赖文档：`doc/系统关系总览/01-关系总览与映射框架.md`、`02-分层映射详解.md`、`03-QA事实底座.md`、`doc/项目升级计划/QA问答优化方案.md`
> 口径：以当前代码为准（Flyway V1–V38，43 张活跃表）。所有结论标注证据来源；未落地项标 `待实现`。

---

## 1. 背景与目标

### 1.1 问题陈述

当前 QA 问答系统能回答"是什么"类问题（事实 / 结构 / 关系 / 解释），但无法回答"怎么做"类变更行动问题，典型如：

> "给 `lg_change_task` 表加一个 `priority` 字段，需要怎么做？"

根因有三（证据见 §2）：

1. **意图缺失**：`QueryIntent` 枚举无变更影响类意图，"加字段"会被错分到 `EXPLANATION`（不触发 GraphRAG Planner）或 `STRUCTURAL`，靠 LLM 自由发挥，给不出可执行步骤。
2. **链路解耦**：项目已存在完整的变更影响链路（`/change-tasks` → `ChangeTaskService` → `ImpactSubgraphService` + `ChangeImpactAgent`），但 `EnhancedQaAgent` 不引用它（grep 确认无引用），QA 不会路由过去。
3. **能力边界**：即便打通，变更链路本身也有缺陷——`ImpactSubgraphService.extractByNode` 只取 **1 跳邻域**，而文档 `02 §13.4` 要求多跳反查；`ChangeTask.taskType` 只有 `BUGFIX/REFACTOR/UPGRADE`，无 `ADD_COLUMN`，`PatchPlanAgent` 只生成 BUGFIX 补丁。

### 1.2 目标能力

| 编号 | 能力 | 验收口径 |
|---|---|---|
| G1 | QA 识别"加字段/改表/加接口"类变更问题 | 意图分类为 `CHANGE_IMPACT`，准确率 ≥ 90%（评测集） |
| G2 | QA 自动调取变更影响子图并融入回答 | 回答含受影响节点清单 + 证据卡片，可跳转图谱 |
| G3 | 影响子图支持多跳反查 | 沿 `Table←SQL←Mapper←Service←Api←Feature` 反向遍历，深度可达 4 |
| G4 | 变更任务支持 `ADD_COLUMN` 类型并生成执行计划 | 输出 Flyway 脚本 + 各层改动清单，过 `PatchPlanValidator` |
| G5 | 回答置信度分层 | `CONFIRMED` 直接答，`PENDING_CONFIRM` 标注，缺口标"⚠ 暂无法高置信回答" |

### 1.3 范围与非范围

**范围内**：意图扩展、QA 与变更链路打通、多跳影响子图、`ADD_COLUMN` 执行计划、增量回写、评测集。

**非范围**：通用自然语言转 SQL（NL2SQL）、自动合并 PR、跨仓联邦影响传播（`CrossRepoImpactController` 已有，不在本设计增强）。

---

## 2. 现状分析（事实底座）

### 2.1 四条 QA 链路

| # | 链路 | 入口 | 核心 Agent | 数据来源 |
|---|---|---|---|---|
| ① | 简单 RAG | `POST /qa/ask` | `QaAgent` | 向量 top5 + 图 1 跳 |
| ② | **增强流式（主）** | `POST /qa/ask/stream` | `EnhancedQaAgent` | 混合召回 + GraphRAG + LLM 流式 |
| ③ | Graphify 关键词 | `POST /lg/projects/{id}/graphify/questions` | `GraphifyQuestionService` | 纯关键词，无 LLM |
| ④ | 代码理解报告 | `POST /lg/projects/{id}/understanding/reports` | `CodeUnderstandingOrchestrator` | 工具规划 + 执行 |

证据来源：`controller/GraphQaController.java`、`controller/EnhancedQaController.java`、`query/GraphifyQuestionService.java`、`understanding/CodeUnderstandingOrchestrator.java`（CODE）。

### 2.2 主链路（②）意图与 GraphRAG 触发

`QueryIntent` 枚举（`agent/QueryIntent.java`）：

| 意图 | 图谱深度 | requiresPlanner() |
|---|---|---|
| FACT_LOOKUP | 1 | 否 |
| STRUCTURAL | 2 | **是** |
| RELATIONAL | 3 | **是** |
| COMPARATIVE | 2 | 否 |
| TEMPORAL | 1 | 否 |
| EXPLANATION | 2 | 否 |

`requiresPlanner()` 仅 `STRUCTURAL || RELATIONAL` 返回 true（`QueryIntent.java`）。GraphRAG 路径查询深度被 `GraphRagPlanExecutor.clampDepth()`（`:201`）钳制在 [1,4]，`Neo4jGraphDao.findPaths()` limit 20 paths / 20 cards（`GraphRagPlanExecutor.java:123,140`）。

### 2.3 变更影响链路（与 QA 解耦）

完整状态机链路（`service/change/ChangeTaskService.java`）：

```
createTask(OPEN) → refreshImpact(IMPACT_READY) → generatePatch(PATCH_DRAFTED/REVIEW_PENDING)
  → registerGates(VALIDATING) → runValidation(VALIDATION_PASSED/FAILED) → createPr(PR_CREATED)
```

- `refreshImpact`（`ChangeTaskService.java:130`）：`ImpactSubgraphService.extractByNode` 取邻域 → `ChangeImpactAgent.analyze` LLM 分析 → 回写 `riskLevel`。
- `ImpactSubgraphService.extractByNode`（`:39`）：`neo4jGraphDao.queryEdges(projectId, versionId, null, null, targetNodeId, null, null, MAX_NEIGHBORS)`（`:54`），**只取 1 跳**，`MAX_NEIGHBORS=200`（`:30`）。
- `ChangeImpactAgent.analyze`（`:27`）：把 `changeTarget/changeDescription/dependencies` 喂给 `prompts/change-impact.txt`，输出 `ChangeImpactAnalysis{changeType,severity,summary,impactedNodes,affectedTests,regressionScope}`。
- `taskType` 枚举：`BUGFIX/REFACTOR/UPGRADE`（`V9__change_task.sql:12`、`V25` 字典、`entity/ChangeTask.java:36`、`dto/graph/PatchPlan.java:26`）。`generatePatch`（`:181`）switch 只覆盖这三种，`PatchPlanAgent` 仅生成 BUGFIX。

**解耦证据**：`grep -E "ChangeTask|ImpactSubgraph|ChangeImpact|PatchPlan" EnhancedQaAgent.java` 无任何引用。QA 与变更链路共享底层（Neo4j、`LlmGateway`、`KnowledgeClaim`），但无调用关系。

### 2.4 文档与代码偏移汇总

| # | 文档说法 | 代码实际 | 偏移 |
|---|---|---|---|
| O1 | `02 §13.4`：变更影响"沿图谱依赖链反向遍历 `Table←SQL←Mapper←Service←Api←Feature`" | `extractByNode` 只取 1 跳邻域 | 多跳反查未实现 |
| O2 | `03 B2`：变更影响链路为多跳 | 同上 | 同上 |
| O3 | `01 §5`：数据层反向路径多跳 | 同上 | 同上 |
| O4 | 变更任务应支持"加字段"等 schema 变更 | `taskType` 无 `ADD_COLUMN` | 类型缺失 |

> 本设计同时修正 O1–O4 四处偏移。

---

## 3. 总体设计

### 3.1 设计原则

1. **复用优先**：不新建问答链路，在主链路②`EnhancedQaAgent` 内增分支；不新建任务管道，复用 `ChangeTaskService` 状态机。
2. **行动导向**：QA 对变更类问题不仅"解释"，还产出可执行计划（影响清单 + 改动清单 + 建议建任务）。
3. **证据底线**：对齐 `03 Part C2`——有证据才回答，`PENDING_CONFIRM` 标注，缺口不高置信回答。
4. **渐进落地**：P1–P4 分阶段，每阶段独立可验收、可回退。

### 3.2 目标架构

```
用户问"加字段怎么做"
  └ EnhancedQaController /qa/ask/stream (SSE)
    └ EnhancedQaAgent.answerStream
      ├ QueryIntentClassifier → CHANGE_IMPACT (新增意图)
      ├ ChangeImpactQuestionParser → 解析出 (表名, 字段名, 字段类型)  [P1]
      ├ ImpactSubgraphService.extractByNodeMultiHop (新增, 多跳反查)  [P2]
      │   └ Neo4j: Table ←READS/WRITE← SQL ←EXECUTES← Mapper ←CALLS← Service ←HANDLED_BY← Api ←EXPOSED_BY← Feature
      ├ ChangeImpactAgent.analyze → ChangeImpactAnalysis (severity/impactedNodes/regressionScope)
      ├ AddColumnPatchAgent.generate → PatchPlan (Flyway + 各层改动清单)  [P3]
      ├ 证据卡片化 (GraphRagEvidenceCard, 复用现有契约)
      └ LlmGateway.callStream → 流式回答 + "建议创建 ADD_COLUMN 变更任务"
```

### 3.3 改动清单总览

| 阶段 | 改动项 | 文件 | 类型 |
|---|---|---|---|
| P1 | `QueryIntent` 增 `CHANGE_IMPACT` | `agent/QueryIntent.java` | 改 |
| P1 | 意图分类 prompt 增规则 | `resources/prompts/intent-classifier.txt` | 改 |
| P1 | `ChangeImpactQuestionParser` | `agent/ChangeImpactQuestionParser.java`（新） | 新 |
| P1 | `EnhancedQaAgent` 注入 + 分支 | `agent/EnhancedQaAgent.java` | 改 |
| P1 | SSE `impact` 事件 | `EnhancedQaAgent` + 前端 | 改 |
| P2 | `extractByNodeMultiHop` | `service/change/ImpactSubgraphService.java` | 改+新方法 |
| P2 | 反向遍历 Cypher | `dao/Neo4jGraphDao.java` | 新方法 |
| P3 | `taskType` 增 `ADD_COLUMN` | `V39__seed_add_column_dict.sql`（新）+ 注释 | 新+改 |
| P3 | `AddColumnPatchAgent` | `agent/AddColumnPatchAgent.java`（新） | 新 |
| P3 | `prompts/add-column-patch.txt` | `resources/prompts/`（新） | 新 |
| P3 | `ChangeTaskService.generatePatch` switch 增分支 | `service/change/ChangeTaskService.java` | 改 |
| P3 | `PatchPlanValidator` 增 DDL 校验 | `service/change/PatchPlanValidator.java` | 改 |
| P4 | 增量回写 `HAS_COLUMN` | `service/change/ColumnIngestService.java`（新） | 新 |
| P4 | 评测集样本 | `resources/qa_test_set.json` | 改 |

---

## 4. 详细设计

### 4.1 P1 — 意图扩展与链路打通

#### 4.1.1 扩展 `QueryIntent`

```java
public enum QueryIntent {
    FACT_LOOKUP, STRUCTURAL, RELATIONAL, COMPARATIVE, TEMPORAL, EXPLANATION,
    CHANGE_IMPACT;   // 新增：变更影响/执行计划类问题

    public int getRecommendedGraphDepth() {
        return switch (this) {
            case FACT_LOOKUP -> 1;
            case STRUCTURAL -> 2;
            case RELATIONAL -> 3;
            case COMPARATIVE -> 2;
            case TEMPORAL -> 1;
            case EXPLANATION -> 2;
            case CHANGE_IMPACT -> 3;   // 多跳反查
        };
    }

    public boolean requiresPlanner() {
        return this == STRUCTURAL || this == RELATIONAL;
    }

    /** 新增：是否走变更影响专用链路（不走通用 GraphRAG Planner） */
    public boolean requiresChangeImpact() {
        return this == CHANGE_IMPACT;
    }
}
```

> 说明：`CHANGE_IMPACT` 不复用 `requiresPlanner()`，因为它走的是 `ImpactSubgraphService` + `ChangeImpactAgent` 专用链路，而非通用 GraphRAG Planner。两者并行不互斥。

#### 4.1.2 意图分类 prompt 扩展

`resources/prompts/intent-classifier.txt` 增加规则：

```
- CHANGE_IMPACT: 涉及"加字段/改表/加列/删字段/加接口/改接口/重构怎么做/需要改哪些地方"
  这类问题不是查询现状，而是询问变更如何执行。关键词：加、增、改、删、新增、修改、怎么改、需要做哪些改动
```

#### 4.1.3 新增 `ChangeImpactQuestionParser`

解析自然语言变更请求为结构化输入，供下游 Agent 使用。

```java
// agent/ChangeImpactQuestionParser.java（新）
@Data @Builder
public static class ParsedChangeRequest {
    private String changeKind;   // ADD_COLUMN / MODIFY_COLUMN / ADD_API / REFACTOR / UNKNOWN
    private String tableName;     // 目标表名（可空）
    private String columnName;    // 字段名（可空）
    private String columnType;    // 字段类型（可空，如 VARCHAR(64)）
    private String rawQuestion;   // 原始问题
}

public ParsedChangeRequest parse(String question) {
    // 1. LLM 模板 add-column-parse.txt 抽取结构化字段
    // 2. 失败降级为正则匹配（表名 r"lg_\w+"、字段名 r"加.*?(\w+)字段"）
    // 3. 解析不出 changeKind 则标 UNKNOWN，回退到通用 EXPLANATION 链路
}
```

> 降级策略保证：解析失败不阻断问答，回退到现有 EXPLANATION 流程。

#### 4.1.4 `EnhancedQaAgent` 注入与分支

构造器新增两个依赖（`@RequiredArgsConstructor` 自动注入）：

```java
private final ImpactSubgraphService impactSubgraphService;   // 新增
private final ChangeImpactAgent changeImpactAgent;            // 新增
private final ChangeImpactQuestionParser changeImpactParser;  // 新增
```

在 `answerStream` 第 5 步（意图分类，`EnhancedQaAgent.java` 约 `:109`）之后插入分支：

```java
QueryIntent intent = intentClassifier.classify(projectId, question, history);

// 新增：变更影响专用链路
List<GraphRagEvidenceCard> impactCards = Collections.emptyList();
ChangeImpactAnalysis impactAnalysis = null;
if (intent.requiresChangeImpact()) {
    sendEvent(emitter, "thinking", Map.of("stage", "parsing_change"));
    var parsed = changeImpactParser.parse(question);

    if (parsed.getChangeKind() != null
            && !parsed.getChangeKind().equals("UNKNOWN")) {
        sendEvent(emitter, "thinking", Map.of("stage", "extracting_impact"));
        // P2: 多跳影响子图
        ImpactSubgraph subgraph = impactSubgraphService.extractByNodeMultiHop(
            projectId, versionId, resolveTargetNodeId(projectId, parsed));

        sendEvent(emitter, "thinking", Map.of("stage", "analyzing_impact"));
        impactAnalysis = changeImpactAgent.analyze(AgentEnvelope.<...>builder()
            .projectId(projectId).agentType("ChangeImpact")
            .input(ChangeImpactAgent.ChangeImpactInput.builder()
                .changeTarget(parsed.getTableName() + "." + parsed.getColumnName())
                .changeDescription(question)
                .dependencies(subgraph.getDependencySummary())
                .build())
            .build());

        // 影响子图 + 分析结果 → 证据卡片
        impactCards = toEvidenceCards(subgraph, impactAnalysis);

        // SSE 推送影响事件（前端可展示影响树）
        sendEvent(emitter, "impact", Map.of(
            "impactedNodes", impactAnalysis.getImpactedNodes(),
            "severity", impactAnalysis.getSeverity(),
            "regressionScope", impactAnalysis.getRegressionScope()));
    }
    // 失败/UNKNOWN：impactCards 为空，继续走通用检索，LLM 仍能基于图谱回答
}
```

随后在第 9 步（构建证据列表，`:175`）合并 `impactCards`；第 11 步 LLM 上下文追加 `appendChangeImpactContext(impactAnalysis, parsed)`，prompt 指示 LLM 产出"影响清单 + 执行步骤 + 建议建任务"。

#### 4.1.5 SSE 协议扩展

新增 `impact` 事件（与现有 `thinking/evidence/token/complete` 并列）：

| event | data | 说明 |
|---|---|---|
| `impact` | `{impactedNodes, severity, regressionScope, changeKind, tableName}` | 变更影响摘要，前端可渲染影响树 |

`complete` 事件增字段 `changeImpact: {changeKind, severity, suggestCreateTask: boolean}`。

#### 4.1.6 前端改动

- `frontend/src/views/graph/GraphQa.vue`：SSE parser 增 `impact` 事件分发，渲染影响节点列表 + "创建变更任务"按钮（点击调 `POST /change-tasks`，`taskType=ADD_COLUMN`）。
- `frontend/src/views/graph/qaMessageMapper.ts`：兼容 `complete.changeImpact` 字段。

### 4.2 P2 — 多跳影响子图

#### 4.2.1 新增 `extractByNodeMultiHop`

`service/change/ImpactSubgraphService.java` 新增方法，保留原 `extractByNode` 不动（向后兼容，现有 `ChangeTaskService.refreshImpact` 继续用旧方法，P2 验收后切换）：

```java
/**
 * 以目标节点为中心，沿指定方向做有界多跳反向遍历。
 * 修正 02 §13.4 文档偏移：原 extractByNode 仅 1 跳，本方法支持多跳依赖链。
 *
 * @param direction 反向链路类型，决定遍历的边类型与方向：
 *   TABLE_REVERSE  → Table←READS/WRITE←SQL←EXECUTES←Mapper←CALLS←Service←HANDLED_BY←Api←EXPOSED_BY←Feature
 *   API_REVERSE    → Api←HANDLED_BY←Service...（同上截断）
 * @param maxDepth  1–4，默认 3（对齐 QueryIntent.CHANGE_IMPACT.getRecommendedGraphDepth()）
 */
public ImpactSubgraph extractByNodeMultiHop(
        String projectId, String versionId, String targetNodeId,
        TraversalDirection direction, int maxDepth) {
    // 1. 校验目标节点存在
    // 2. 调用 Neo4jGraphDao.findPathsDirected(projectId, versionId, targetNodeId,
    //      direction.edgeTypes(), direction.flow(), clamp(maxDepth,1,4), MAX_PATHS)
    // 3. 聚合路径上的节点/边/受影响文件
    // 4. buildDependencySummary（复用现有 :99，增强为多跳链路文本）
}
```

#### 4.2.2 `Neo4jGraphDao` 新增有向路径查询

现有 `findPaths` 是**无向**有界路径遍历（`GraphRagPlanExecutor.java:123`）。变更影响需要**有向反向**（只沿依赖链上游走，不发散到无关邻居），新增：

```java
// dao/Neo4jGraphDao.java（新方法）
public List<GraphPath> findPathsDirected(String projectId, String versionId,
        String startNodeId, Set<String> edgeTypes, FlowDirection flow,
        int maxDepth, int limit);
```

Cypher 模板（反向遍历，以 Table 为例）：

```cypher
MATCH p = (target:Table {nodeId: $startNodeId})
          <-[:READS|WRITES]-(:SqlStatement)
          <-[:EXECUTES]-(:Mapper)
          <-[:CALLS]-(s:Service)
          <-[:HANDLED_BY]-(:ApiEndpoint)
          <-[:EXPOSED_BY]-(:Feature)
WHERE p.length <= $maxDepth AND target.projectId = $projectId
RETURN DISTINCT p LIMIT $limit
```

> 边类型集合按 `EdgeType.java`（26 种）取 `READS/WRITES/EXECUTES/CALLS/HANDLED_BY/EXPOSED_BY`，方向由 `flow` 参数控制。`maxDepth` 受 `clampDepth` 钳制 [1,4]，`limit` 默认 50 paths（高于 QA 的 20，因为变更影响需要更全）。

#### 4.2.3 `TraversalDirection` 枚举

```java
public enum TraversalDirection {
    TABLE_REVERSE,   // 从 Table 反查到 Feature
    API_REVERSE,     // 从 Api 反查到 Feature/下游调用方
    SERVICE_REVERSE; // 从 Service 反查到 Api/Feature
    // 各自映射 edgeTypes + flow
}
```

#### 4.2.4 切换 `ChangeTaskService`

P2 验收后，`ChangeTaskService.refreshImpact`（`:130`）从 `extractByNode` 切换到 `extractByNodeMultiHop`，使变更任务链路同样受益于多跳反查。

### 4.3 P3 — `ADD_COLUMN` 执行计划

#### 4.3.1 类型扩展（数据模型）

`task_type` 列为 `VARCHAR(32)`（`V9__change_task.sql:12`），`ADD_COLUMN`（10 字符）无需改表结构，仅需：

**新 Flyway 迁移 `V39__seed_add_column_dict.sql`**：

```sql
-- 复用 V25 字典插入风格
INSERT INTO sys_dict_item (id, dict_id, item_value, item_label, item_desc, sort, is_default, status, created_at, updated_at)
SELECT 'fc01a001-0001-4000-a002-000000000004',
       'b201c3d4-5678-9abc-def0-1234567890ab',  -- change_task_type dict_id（同 V25）
       'ADD_COLUMN', '加字段', '新增表字段', 4, false, 'ACTIVE', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_item
                  WHERE dict_id='b201c3d4-5678-9abc-def0-1234567890ab' AND item_value='ADD_COLUMN');
```

注释同步：`entity/ChangeTask.java:36`、`dto/graph/PatchPlan.java:26` 改为 `/** BUGFIX / REFACTOR / UPGRADE / ADD_COLUMN */`。

#### 4.3.2 新增 `AddColumnPatchAgent`

```java
// agent/AddColumnPatchAgent.java（新）
public PatchPlan generate(AgentEnvelope<AddColumnInput> envelope) {
    AddColumnInput input = envelope.getInput();
    // input: tableName, columnName, columnType, nullable, defaultValue, comment
    Map<String,String> variables = Map.of(
        "tableName", input.getTableName(),
        "columnName", input.getColumnName(),
        "columnType", input.getColumnType(),
        "nullable", String.valueOf(input.isNullable()),
        "defaultValue", Objects.toString(input.getDefaultValue(),""),
        "impactedFiles", input.getImpactedFilesSummary());  // 来自 ImpactSubgraph
    return llmGateway.callWithEnvelope(envelope, "add-column-patch",
        variables, PatchPlan.class);
}
```

#### 4.3.3 `prompts/add-column-patch.txt`（新）

```text
你是资深后端架构师。基于表结构变更与影响子图，生成 ADD_COLUMN 执行计划。

## 变更信息
- 表: {tableName}
- 新字段: {columnName} {columnType}  nullable={nullable} default={defaultValue}
- 受影响文件: {impactedFiles}

## 输出要求（严格 JSON，对齐 PatchPlan 契约）
1. patches: 至少包含
   - Flyway 迁移脚本 V{next}__add_{columnName}_to_{tableName}.sql（ALTER TABLE ... ADD COLUMN）
   - 实体类改动（{TableName}.java 加字段）
   - Mapper XML 改动（INSERT/UPDATE/SELECT 增列）
   - Service / Controller DTO 改动（按影响子图，无证据的文件不得编造）
2. impactedFiles: 仅列影响子图内的文件，每项附 reason
3. validationGates: ["STATIC","UNIT","DB","MIGRATION"]
4. newTests: DB 断言（字段存在 + 默认值）+ 实体字段单测
5. riskLevel: 评估（BREAKING → HIGH，新增可空字段 → LOW）
6. manualReviewNeeded: 含 NOT NULL 无默认值 / 涉及唯一索引时 true

## 输出格式
```json
{ "taskType":"ADD_COLUMN", "riskLevel":"LOW", "impactedFiles":[...],
  "patches":[{"filePath":"...","changeType":"CREATE/MODIFY","patchText":"...","evidenceIds":[...]}],
  "newTests":[...], "validationGates":[...], "manualReviewNeeded":false, "generatedBy":"add-column" }
```
只输出 JSON。
```

#### 4.3.4 `ChangeTaskService.generatePatch` 增分支

`ChangeTaskService.java:189` switch 增：

```java
case "ADD_COLUMN" -> plan = addColumnPatchAgent.toPatchPlan(
    taskId, task.getProjectId(), req.getTableName(), req.getColumnName(),
    req.getColumnType(), req.isNullable(), req.getDefaultValue(),
    evidenceIds);   // evidenceIds 来自 ImpactSubgraph.impactedFiles
```

`AddColumnPatchAgentAdapter`（仿 `RefactorAgentAdapter`）做 `PatchPlan` 适配与 `taskType="ADD_COLUMN"` 设置。

#### 4.3.5 `PatchPlanValidator` 扩展

`service/change/PatchPlanValidator.java` 三类校验增 DDL 专项：

| 校验 | 规则 |
|---|---|
| 范围 | patch 文件必须在 `ImpactSubgraph.impactedFiles` 白名单内（现有） |
| 格式 | Flyway 脚本须为有效 `ALTER TABLE ... ADD COLUMN`；其他 patch 须为 unified diff |
| 证据 | 每个 patch 至少一个 `evidenceId`（现有）；Flyway 脚本须引用 Table 节点证据 |
| DDL 专项（新） | NOT NULL 无 default → 强制 `manualReviewNeeded=true`；字段名符合命名规范 |

#### 4.3.6 `ChangeTaskController` 入参扩展

`ChangeTaskController.CreateChangeTaskRequest` / `PatchGenRequest` 增可选字段：`tableName / columnName / columnType / nullable / defaultValue`（仅 `ADD_COLUMN` 类型使用）。

### 4.4 P4 — 闭环：增量回写 + 评测

#### 4.4.1 增量回写 `HAS_COLUMN`

当前 `HAS_COLUMN` 边只在全量扫描时由 `DatabaseMetadataScanService` + `SqlTableExtractor` 写入。新增单字段增量入口：

```java
// service/change/ColumnIngestService.java（新）
public void ingestAddedColumn(String projectId, String versionId,
        String tableName, ColumnSpec column, List<String> evidenceIds) {
    // 1. Neo4jGraphDao.mergeNode(Column) + mergeEdge(Table -[:HAS_COLUMN]-> Column)
    // 2. EvidenceGraphWriter 落证据到 lg_node_evidence / lg_edge_evidence
    // 3. status=CONFIRMED（来源 CODE/DB，置信度 0.85）
    // 4. 触发 lg_graph_write_intent outbox（对齐统一写图路径）
}
```

调用时机：`ADD_COLUMN` 变更任务 `VALIDATION_PASSED` 后，`ChangeTaskService` 调 `columnIngestService.ingestAddedColumn`，使图谱在无需全量重扫的情况下反映 schema 变更。

#### 4.4.2 评测集

`resources/qa_test_set.json` 增 `CHANGE_IMPACT` 类样本（对齐 `03 Part C3` 四类）：

| 样本 | 期望意图 | 期望要素 |
|---|---|---|
| "给 lg_change_task 加 priority 字段怎么做" | CHANGE_IMPACT | 含 Flyway + 实体 + Mapper 改动 + 影响节点 |
| "lg_qa_message 加个 status 字段要改哪些地方" | CHANGE_IMPACT | 含反向影响链 + 建议建任务 |
| "改 user 表的手机号字段长度" | CHANGE_IMPACT | MODIFY_COLUMN，含受影响 SQL |
| "OrderService 有哪些方法"（负样本） | FACT_LOOKUP | 不触发变更链路 |
| "孤立节点 X 的关系"（缺口负样本） | — | 标"⚠ 暂无法高置信回答" |

`QaEvaluationService` 增 `CHANGE_IMPACT` 评测维度：意图准确率、影响节点召回率、执行步骤覆盖率。

---

## 5. 数据模型与配置变更

| 变更 | 类型 | 说明 |
|---|---|---|
| `V39__seed_add_column_dict.sql` | 新增 Flyway | `change_task_type` 字典加 `ADD_COLUMN` |
| `ChangeTask.taskType` 注释 | 改 | 增 `ADD_COLUMN`（无表结构变更） |
| `PatchPlan.taskType` 注释 | 改 | 同上 |
| `NodeType.java` | 不变 | `Column` 已存在 |
| `EdgeType.java` | 不变 | `HAS_COLUMN/READS/WRITES/EXECUTES/CALLS/HANDLED_BY/EXPOSED_BY` 均已存在 |
| `prompts/intent-classifier.txt` | 改 | 增 `CHANGE_IMPACT` 规则 |
| `prompts/add-column-patch.txt` | 新增 | ADD_COLUMN 执行计划模板 |
| `prompts/add-column-parse.txt` | 新增 | 变更请求解析模板 |
| `qa_test_set.json` | 改 | 增 CHANGE_IMPACT 样本 |
| 配置 `qa.change-impact.max-depth` | 新增 | 默认 3，可调 |

---

## 6. 关键流程时序

### 6.1 QA 问"加字段"主流程

```
前端 ─POST /qa/ask/stream→ EnhancedQaController
  EnhancedQaAgent.answerStream:
    1. getOrCreateConversation + saveUserMessage
    2. semanticCache.get（命中则流式返回，结束）
    3. intentClassifier.classify → CHANGE_IMPACT
    4. changeImpactParser.parse → (lg_change_task, priority, VARCHAR(32))
    5. resolveTargetNodeId → Neo4j 查 Table 节点
    6. impactSubgraphService.extractByNodeMultiHop(TABLE_REVERSE, depth=3)
       → ImpactSubgraph{nodeIds, edgeIds, impactedFiles, dependencySummary}
    7. changeImpactAgent.analyze → ChangeImpactAnalysis{severity, impactedNodes, regressionScope}
    8. toEvidenceCards → GraphRagEvidenceCard[]
    9. sendEvent("impact", ...)  ← 新增 SSE 事件
   10. hybridRetrievalService.retrieve（补充文档证据）
   11. buildRetrievalContext + appendChangeImpactContext
   12. llmGateway.callStream("qa-answer-enhanced") → 流式 token
   13. complete{conversationId, messageId, confidence, changeImpact{suggestCreateTask:true}}
   14. semanticCache.put + saveAssistantMessage

前端收到 changeImpact.suggestCreateTask → 显示"创建变更任务"按钮
  用户点击 ─POST /change-tasks (taskType=ADD_COLUMN)→ ChangeTaskController
    → createTask(OPEN) → refreshImpact(IMPACT_READY) → generatePatch(ADD_COLUMN)
      → AddColumnPatchAgent → PatchPlan → PatchPlanValidator → PATCH_DRAFTED
    → registerGates + runValidation → VALIDATION_PASSED
    → columnIngestService.ingestAddedColumn（回写 HAS_COLUMN）  [P4]
    → createPr(PR_CREATED)
```

### 6.2 降级链路

- 意图分类为非 `CHANGE_IMPACT` → 走原 GraphRAG / 通用检索流程，无影响。
- `changeImpactParser` 解析为 `UNKNOWN` → `impactCards` 为空，LLM 基于 GraphRAG 通用检索回答，回答中标注"未识别到具体变更目标，以下为通用建议"。
- `ImpactSubgraphService` 多跳查询超时/异常 → catch，降级为 1 跳（原 `extractByNode`），`stageTimings` 记录 `impact_fallback=true`，回答标注"影响范围基于 1 跳邻域，可能不全"。
- `ChangeImpactAgent.analyze` 失败 → 已有 try-catch（`ChangeTaskService.java:154` 模式），`riskLevel` 为空，回答不含 severity。

---

## 7. 兼容性、风险与回退

### 7.1 兼容性

| 项 | 影响 | 处置 |
|---|---|---|
| 现有 6 意图 | `CHANGE_IMPACT` 新增不冲突 | 意图分类 prompt 增规则，旧问题分类不变 |
| `extractByNode` 旧调用方 | `ChangeTaskService.refreshImpact` 仍用旧方法 | P2 验收后切换；旧方法保留不删 |
| `taskType` 旧值 | `BUGFIX/REFACTOR/UPGRADE` 不受影响 | switch 增 `ADD_COLUMN` 分支，default 抛异常保持不变 |
| SSE 协议 | 新增 `impact` 事件 | 前端旧版忽略未知事件，不报错 |
| 语义缓存 | `CHANGE_IMPACT` 答案入缓存 | schema 变更后须手动清缓存或缩短 TTL（见风险） |

### 7.2 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 多跳 Cypher 性能（大图 8573 节点/22199 边） | 中 | `maxDepth≤4`、`limit=50`、`edgeTypes` 收窄；加 Neo4j 索引 `(projectId, nodeId)`；超时降级 1 跳 |
| LLM 生成的 Flyway 脚本错误 | 高 | `PatchPlanValidator` DDL 专项校验 + `manualReviewNeeded` 强制；`VALIDATION_PASSED` 前不落库 |
| 意图误分类（把查询当变更） | 中 | `ChangeImpactQuestionParser` 二次确认；`UNKNOWN` 降级；评测集回归 |
| 语义缓存返回过时 schema | 中 | `ADD_COLUMN` 任务 `VALIDATION_PASSED` 后主动 `semanticCache.invalidate(projectId, tableName)` |
| 多跳反查命中 956 孤立节点 / 223 INFERRED 边 | 低 | 证据卡片带 `status`；INFERRED 边标 `PENDING_CONFIRM`，不高置信回答（对齐 `03 B3`） |
| Embedding 依赖外部 key（`SILICONFLOW_API_KEY`） | 中 | 变更链路不依赖向量检索，`ImpactSubgraphService` 走图查询，无 key 也能工作 |

### 7.3 回退

- P1 回退：`QueryIntent` 移除 `CHANGE_IMPACT`，`EnhancedQaAgent` 分支自然不触发。
- P2 回退：`extractByNodeMultiHop` 调用改回 `extractByNode`。
- P3 回退：`generatePatch` switch 移除 `ADD_COLUMN` 分支；字典项保留无副作用。
- 配置开关：`legacygraph.qa.change-impact.enabled`（默认 true），可整体关闭变更影响问答链路。

---

## 8. 实施计划

| 阶段 | 工作量 | 交付物 | 依赖 |
|---|---|---|---|
| **P1 意图+打通** | 3d | `QueryIntent` 改、parser、`EnhancedQaAgent` 注入分支、SSE `impact`、前端解析、单测 | 无 |
| **P2 多跳** | 3d | `extractByNodeMultiHop`、`findPathsDirected`、Cypher、`TraversalDirection`、单测、切换 `refreshImpact` | P1 |
| **P3 ADD_COLUMN** | 4d | V39 迁移、`AddColumnPatchAgent` + prompt、`generatePatch` 分支、`PatchPlanValidator` DDL 校验、Controller 入参、单测 | P2 |
| **P4 闭环** | 3d | `ColumnIngestService`、缓存失效、评测集样本、`QaEvaluationService` 维度 | P3 |
| 集成测试 + 文档同步 | 2d | 端到端测试、`02 §13.4`/`03` 文档更新（修正 O1–O4 偏移） | P1–P4 |

### 8.1 测试清单

| 测试文件 | 覆盖点 |
|---|---|
| `EnhancedQaAgentTest`（扩） | CHANGE_IMPACT 触发变更链路；解析失败降级；多跳异常降级 1 跳；缓存命中保留历史 |
| `ChangeImpactQuestionParserTest`（新） | 表名/字段名/类型抽取；UNKNOWN 降级 |
| `ImpactSubgraphServiceTest`（扩） | 多跳反查节点/边/文件；深度钳制 [1,4]；超时降级 |
| `Neo4jGraphDaoDirectedPathTest`（新） | 有向路径；边类型过滤；limit |
| `AddColumnPatchAgentTest`（新） | PatchPlan 结构；taskType=ADD_COLUMN；NOT NULL 无默认值 → manualReview |
| `PatchPlanValidatorTest`（扩） | DDL 专项校验；范围白名单 |
| `ChangeTaskServiceTest`（扩） | ADD_COLUMN 状态机；`ColumnIngestService` 回写 |
| `qa.api.test.ts`（扩） | SSE `impact` 事件解析；`changeImpact` 字段 |
| `GraphQa.test.ts`（扩） | 影响树渲染；"创建变更任务"按钮 |

### 8.2 验证命令

```bash
mvn -Dtest=EnhancedQaAgentTest,ChangeImpactQuestionParserTest,ImpactSubgraphServiceTest,AddColumnPatchAgentTest,PatchPlanValidatorTest,ChangeTaskServiceTest test
mvn test-compile
npm run type-check
npm run test -- --run src/api/__tests__/qa.api.test.ts src/views/graph/__tests__/qaMessageMapper.test.ts tests/unit/views/GraphQa.test.ts
```

---

## 9. 验收标准

1. **意图**：评测集 `CHANGE_IMPACT` 样本意图准确率 ≥ 90%，旧 6 意图准确率不下降。
2. **影响子图**：`extractByNodeMultiHop` 对 `lg_change_task` 反查可命中 `ChangeTaskService/ChangeTaskController/ChangeTaskList.vue` 等 ≥ 5 个跨层节点；深度可达 4，超时降级 1 跳。
3. **回答质量**：回答含 ① 受影响节点清单（带证据）② 执行步骤（DDL→实体→Mapper→Service→Controller→前端→测试）③ 置信度标注 ④ "建议建任务"提示。
4. **ADD_COLUMN**：`POST /change-tasks`（taskType=ADD_COLUMN）能走完整状态机到 `PATCH_DRAFTED`，`PatchPlan` 含 Flyway 脚本 + ≥3 个 patch，过 `PatchPlanValidator`。
5. **闭环**：`VALIDATION_PASSED` 后 Neo4j 出现新 `Column` 节点 + `HAS_COLUMN` 边（`CONFIRMED`），语义缓存对应 key 失效。
6. **置信度底线**：缺口/INFERRED 边相关回答标"⚠ 暂无法高置信回答"，无证据内容进"推断/待确认"章节。
7. **回归**：现有 QA 测试全通过；前端 type-check 通过。

---

## 10. 附录

### 10.1 关键代码位置索引

| 符号 | 位置 |
|---|---|
| `QueryIntent` | `agent/QueryIntent.java` |
| `EnhancedQaAgent.answerStream` | `agent/EnhancedQaAgent.java:55`（14 构造依赖） |
| `ImpactSubgraphService.extractByNode` | `service/change/ImpactSubgraphService.java:39`（1 跳，MAX_NEIGHBORS=200 @:30） |
| `ChangeImpactAgent.analyze` | `agent/ChangeImpactAgent.java:27`（envelope）/ `:45`（legacy） |
| `ChangeImpactAnalysis` | `dto/ChangeImpactAnalysis.java` |
| `ImpactSubgraph` | `dto/graph/ImpactSubgraph.java` |
| `ChangeTaskService.refreshImpact` | `service/change/ChangeTaskService.java:130` |
| `ChangeTaskService.generatePatch` | `service/change/ChangeTaskService.java:181`（switch @:189） |
| `ChangeTaskController` | `controller/ChangeTaskController.java`（`/change-tasks`） |
| `GraphRagPlanExecutor.execute` | `service/graph/GraphRagPlanExecutor.java:140`（findPaths @:123, clampDepth @:201） |
| `ChangeTask` 实体 | `entity/ChangeTask.java`（taskType 注释 @:36） |
| `PatchPlan` | `dto/graph/PatchPlan.java`（taskType 注释 @:26） |
| `change-impact.txt` | `resources/prompts/change-impact.txt` |
| `intent-classifier.txt` | `resources/prompts/intent-classifier.txt` |
| Flyway 最新 | `V38__db_connection_source.sql`（下一可用 V39） |

### 10.2 与既有文档的关系

- 修正 `02 §13.4` 链路 D 的代码偏移（1 跳 → 多跳）。
- 落地 `03 Part C1/C2` 的"变更类问题对接 QA"与回答底线。
- 补齐 `QA问答优化方案.md` 未覆盖的"变更行动类问答"维度（原方案聚焦检索/生成质量，不含变更链路打通）。

### 10.3 术语

| 术语 | 含义 |
|---|---|
| 变更影响链路 | `/change-tasks` → ChangeTask 状态机 → 影响子图 + 补丁 + 验证 + PR |
| 多跳反查 | 沿 `Table←SQL←Mapper←Service←Api←Feature` 反向有界遍历 |
| ADD_COLUMN | 新增的变更任务类型，专门处理加字段 |
| 影响子图 | `ImpactSubgraph`，目标节点的依赖邻域，供 LLM 分析 + 补丁范围校验 |
