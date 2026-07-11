# P1-P3 差距优化方案

## Summary

针对从"资料扫描→图谱构建→QA问答→方案生成"全流程的 7 个 P1-P3 差距进行优化，进一步逼近两个战略目标：
1. 建立图谱，指导从需求到编码的过程
2. 取代人工，自动分析需求，给出可落地方案

**P1 差距（方案深度 + 自动执行 + 社区摘要）**：
- G7 方案成本/风险评估：`SolutionPlan` 仅含 summary + steps，无成本估算、风险评估、备选方案，开发者无法评估"值不值得做"
- G8 方案自动执行桥接：`Solution`（需求方案）与 `ChangeTask`（变更执行）两套流水线完全割裂，APPROVED 的方案无法自动转执行
- G3 社区分层摘要：`CommunityDetectionService` 有标签传播算法但无摘要生成，社区检测结果无法用于 QA 检索增强

**P2 差距（知识闭环 + 意图扩展 + 需求引导）**：
- G9 知识闭环回写：PR 合并后不回写图谱，新代码不会进入下次扫描的基线
- G10 评估/诊断意图：`QueryIntent` 仅 7 种意图，缺少方案评估和问题诊断能力
- G11 引导式需求补充：`openQuestions` 仅展示不交互，需求模糊时无法追问

**P3 差距（API 提取 + 跨语言增强）**：
- G4 Swagger/OpenAPI 提取器：无 API 规范文件提取器，Controller 注解提取的 API 信息不完整
- G5 跨语言实体消解增强：`mergeCrossLanguageFeatures` 已实现但仅基于名称匹配，缺 embedding 语义消解

## Current State Analysis

### G7 当前状态
- `SolutionPlan.java`：仅 `summary` + `steps` 两个字段
- `Solution.java` 实体：仅 `summary` + `analysisJson` + `impactResultJson` + `verificationErrors`，无成本/风险字段
- `solution-planning.txt`：只要求输出 summary + steps，不要求成本估算和风险评估
- `SolutionVerifier.java`：7 类校验中无成本/风险校验

### G8 当前状态
- `Solution` 状态机：DRAFT → READY_FOR_REVIEW / NEEDS_INPUT → APPROVED / REJECTED
- `ChangeTask` 状态机：OPEN → IMPACT_READY → PATCH_DRAFTED → VALIDATING → VALIDATION_PASSED → PR_READY → MERGED
- `ChangeTaskService` 有完整的状态机编排（createTask/refreshImpact/generatePatch/runValidation/createPr）
- **缺口**：Solution APPROVED 后无任何路径转到 ChangeTask，两套流水线无桥接点

### G3 当前状态
- `CommunityDetectionService`：实现了标签传播算法，输出 `Map<String, String>`（nodeKey → communityLabel）
- `writeCommunityToNodes`：将社区标签写入 Package 节点 properties.community
- **缺口**：无社区摘要生成，无法回答"这个社区/子系统是做什么的"

### G9 当前状态
- `ValidationGateRunner`：执行门禁并回写结果到 `lg_validation_gate`
- `ChangeTaskService.runValidation`：ADD_COLUMN 类型验证通过后通过 `columnIngestService.onValidationPassed` 回写 Column 节点
- `PrOrchestrator`：只创建 PR 草案（prStatus=DRAFT），无 PR 合并回调
- **缺口**：非 ADD_COLUMN 类型不回写图谱；PR 合并后无回调

### G10 当前状态
- `QueryIntent`：7 种意图（FACT_LOOKUP / STRUCTURAL / RELATIONAL / COMPARATIVE / TEMPORAL / EXPLANATION / CHANGE_IMPACT）
- `requiresPlanner()`：FACT_LOOKUP / STRUCTURAL / RELATIONAL / COMPARATIVE 走 GraphRAG
- `requiresChangeImpact()`：仅 CHANGE_IMPACT 走变更影响链路
- **缺口**：无方案评估意图（"这个方案靠谱吗"）和问题诊断意图（"为什么接口慢"）

### G11 当前状态
- `RequirementController`：3 个端点（analyze / save / impact）
- `RequirementAnalysis`：含 `openQuestions` 字段，由 LLM 抽取
- `SolutionVerifier.checkBlockingQuestions`：openQuestions 非空时校验失败
- **缺口**：openQuestions 仅展示和阻断，无交互式追问端点

### G4 当前状态
- 无 Swagger/OpenAPI 提取器（Glob 搜索 `*Swagger*` 无结果）
- Controller 注解提取通过 `JavaStructureExtractor` 完成
- **缺口**：无法从 OpenAPI 3.0 / Swagger 2.0 JSON/YAML 文件提取 API 端点

### G5 当前状态
- `BusinessGraphBuilder.mergeCrossLanguageFeatures`：已实现跨语言 Feature 合并
- 基于名称匹配（Vue 前端 Feature 名 vs Java 后端 Controller 路径）
- **缺口**：无 embedding 语义消解，名称不完全匹配时无法关联

## Proposed Changes

### Change 1: G7 — 方案增加成本/风险评估

**文件 1**：`backend/src/main/java/io/github/legacygraph/dto/solution/SolutionPlan.java`

**What**：新增 `estimatedCost`、`riskAssessment`、`alternatives` 字段

**Why**：当前方案只有"做什么"，没有"要花多少成本"和"有什么风险"。增加成本/风险评估让开发者能评估方案可行性，备选方案提供对比维度。

**How**：
```java
/** 成本估算（人天/复杂度/影响文件数） */
private CostEstimate estimatedCost;

/** 风险评估（高风险区域 + 缓解措施） */
private RiskAssessment riskAssessment;

/** 备选方案列表（最多 2 个） */
private List<Alternative> alternatives = new ArrayList<>();
```

新增内部类：
```java
@Data
public static class CostEstimate {
    /** 预估人天 */
    private double personDays;
    /** 影响文件数 */
    private int affectedFiles;
    /** 复杂度等级：LOW / MEDIUM / HIGH */
    private String complexity;
}

@Data
public static class RiskAssessment {
    /** 风险等级：LOW / MEDIUM / HIGH */
    private String riskLevel;
    /** 高风险区域描述列表 */
    private List<String> highRiskAreas;
    /** 缓解措施列表 */
    private List<String> mitigations;
}

@Data
public static class Alternative {
    /** 备选方案名称 */
    private String name;
    /** 方案简述 */
    private String description;
    /** 优劣对比 */
    private String tradeoffs;
}
```

**文件 2**：`backend/src/main/java/io/github/legacygraph/entity/Solution.java`

**What**：新增 `estimatedCostJson`、`riskAssessmentJson` 字段持久化

**How**：
```java
/** 成本估算 JSON（SolutionPlan.estimatedCost 的序列化） */
private String estimatedCostJson;

/** 风险评估 JSON（SolutionPlan.riskAssessment 的序列化） */
private String riskAssessmentJson;
```

**文件 3**：`backend/src/main/resources/prompts/solution-planning.txt`

**What**：在输出格式中增加 estimatedCost、riskAssessment、alternatives 字段

**How**：
- 在输出 JSON 结构中增加这三个字段
- 在重要约束中增加：成本估算基于影响文件数和复杂度；风险评估基于影响子图中高风险节点
- 约束 alternatives 最多 2 个，每个含 name + description + tradeoffs

**文件 4**：`backend/src/main/java/io/github/legacygraph/service/solution/SolutionVerifier.java`

**What**：新增第 8 类校验 `checkCostAndRisk`

**How**：
- 校验 estimatedCost 非空且 personDays > 0
- 校验 riskAssessment 非空且 riskLevel 有效
- 错误码：`COST_ESTIMATE_MISSING`、`RISK_ASSESSMENT_MISSING`

### Change 2: G8 — 方案到变更任务桥接

**文件 1**：`backend/src/main/java/io/github/legacygraph/service/solution/SolutionToChangeTaskBridge.java`（新建）

**What**：新建桥接服务，将 APPROVED 的 Solution 转换为 ChangeTask

**Why**：当前 Solution（需求方案）和 ChangeTask（变更执行）是两套完全割裂的流水线。Solution APPROVED 后无任何路径转到执行。桥接服务打通"方案→执行"的最后一公里。

**How**：
```java
@Service
public class SolutionToChangeTaskBridge {

    /**
     * 将已批准的方案转换为变更任务。
     * 从 SolutionPlan.steps 提取 filePath 列表，映射为 ChangeTask 的 targetNodeId。
     * 根据 steps 的 actionType 分布推断 taskType：
     * - 全 CREATE → NEW_FEATURE
     * - 含 MODIFY → REFACTOR（默认）
     * - 含 SQL/DDL → ADD_COLUMN / MIGRATION
     */
    public ChangeTask bridge(String solutionId, String projectId, String versionId) {
        // 1. 加载 Solution + SolutionPlan
        // 2. 校验 status=APPROVED
        // 3. 推断 taskType
        // 4. 调用 ChangeTaskService.createTask
        // 5. 关联 Solution → ChangeTask（Solution 新增 changeTaskId 字段）
        // 6. 返回 ChangeTask
    }

    /**
     * 从 SolutionPlan 推断变更任务类型。
     */
    String inferTaskType(SolutionPlan plan) {
        // 统计 actionType 分布
        // CREATE 为主 → NEW_FEATURE
        // MODIFY 为主 → REFACTOR
        // 含 .sql / migration → MIGRATION
    }
}
```

**文件 2**：`backend/src/main/java/io/github/legacygraph/entity/Solution.java`

**What**：新增 `changeTaskId` 字段关联变更任务

**How**：
```java
/** 关联的变更任务 ID（方案转执行后填充） */
private String changeTaskId;
```

**文件 3**：`backend/src/main/java/io/github/legacygraph/controller/SolutionController.java`

**What**：新增 `POST /solutions/{solutionId}/bridge` 端点

**How**：
```java
@PostMapping("/solutions/{solutionId}/bridge")
@Operation(summary = "将已批准方案转为变更任务")
public Result<ChangeTask> bridge(@PathVariable String solutionId) {
    // 调用 SolutionToChangeTaskBridge.bridge
}
```

**文件 4**：数据库迁移脚本 `V66__solution_bridge.sql`

**What**：Solution 表新增 `change_task_id`、`estimated_cost_json`、`risk_assessment_json` 字段

### Change 3: G3 — 社区分层摘要

**文件 1**：`backend/src/main/java/io/github/legacygraph/service/scan/CommunityDetectionService.java`

**What**：新增 `generateCommunitySummaries` 方法，为每个社区生成摘要

**Why**：当前社区检测只输出标签，不知道每个社区"做什么"。摘要生成后可用于 QA 检索增强，回答"订单模块包含哪些功能"。

**How**：
```java
/**
 * 为每个社区生成摘要。
 * 遍历社区检测结果，对每个社区：
 * 1. 收集社区内所有节点的 nodeName + nodeType
 * 2. 按 nodeType 分组统计
 * 3. 调用 LLM 生成一句话摘要
 * 4. 将摘要写入社区内节点的 properties.communitySummary
 */
public Map<String, String> generateCommunitySummaries(String projectId) {
    // 1. detectCommunities 获取社区映射
    // 2. 反转映射：communityLabel → List<GraphNode>
    // 3. 对每个社区调用 LLM 生成摘要
    // 4. writeCommunitySummaryToNodes
}
```

**文件 2**：`backend/src/main/java/io/github/legacygraph/service/scan/ScanFinalizationService.java`

**What**：在扫描完成后的 10 步编排中新增"社区摘要生成"步骤

**How**：
- 在社区检测步骤后增加 `communityDetectionService.generateCommunitySummaries(projectId)`
- 使用 Feature Flag 控制（`legacygraph.community.summary.enabled`，默认 false）

### Change 4: G9 — 知识闭环回写

**文件 1**：`backend/src/main/java/io/github/legacygraph/service/change/ChangeTaskService.java`

**What**：新增 `onPrMerged` 方法，PR 合并后触发图谱回写

**Why**：当前 PR 合并后新代码不会进入图谱，导致下次扫描基线不更新。回写触发重新扫描或标记节点为"需重新扫描"。

**How**：
```java
/**
 * PR 合并后的回调：标记相关节点需重新扫描。
 */
@Transactional
public void onPrMerged(String taskId) {
    ChangeTask task = requireTask(taskId);
    task.setStatus("MERGED");
    task.setUpdatedAt(LocalDateTime.now());
    changeTaskRepository.updateById(task);

    // 标记 PatchFile 涉及的图谱节点为 stale
    List<PatchFile> patches = patchFileRepository.lambdaQuery()
            .eq(PatchFile::getChangeTaskId, taskId).list();
    for (PatchFile pf : patches) {
        // 标记对应图谱节点的 properties.stale=true
        // 下次扫描时优先处理 stale 节点
    }
    log.info("ChangeTask {} PR merged, marked {} patches as stale", taskId, patches.size());
}
```

**文件 2**：`backend/src/main/java/io/github/legacygraph/controller/ChangeTaskController.java`

**What**：新增 `POST /change-tasks/{taskId}/merge` 端点

**How**：调用 `ChangeTaskService.onPrMerged`

### Change 5: G10 — 评估/诊断意图

**文件 1**：`backend/src/main/java/io/github/legacygraph/agent/QueryIntent.java`

**What**：新增 `SOLUTION_EVALUATION` 和 `DIAGNOSIS` 两个意图枚举

**Why**：
- SOLUTION_EVALUATION：用户问"这个方案靠谱吗"、"这个设计有什么风险"时，需要走方案评估链路
- DIAGNOSIS：用户问"为什么接口慢"、"为什么报错"时，需要走诊断链路（结合日志+图谱）

**How**：
```java
SOLUTION_EVALUATION,  // 方案评估："这个方案有什么风险？"
DIAGNOSIS;            // 问题诊断："为什么这个接口慢？"

// getRecommendedGraphDepth
case SOLUTION_EVALUATION -> 2;
case DIAGNOSIS -> 3;

// requiresPlanner
|| this == SOLUTION_EVALUATION;

// 新增方法
public boolean requiresDiagnosis() {
    return this == DIAGNOSIS;
}
```

**文件 2**：`backend/src/main/java/io/github/legacygraph/agent/EnhancedQaAgent.java`

**What**：在意图路由中新增 SOLUTION_EVALUATION 和 DIAGNOSIS 分支

**How**：
- SOLUTION_EVALUATION：加载最新 Solution + ImpactResult，调用 LLM 评估方案完整性/风险/成本
- DIAGNOSIS：结合图谱路径分析 + 用户提供的问题描述，调用 LLM 诊断根因

### Change 6: G11 — 引导式需求补充

**文件 1**：`backend/src/main/java/io/github/legacygraph/controller/RequirementController.java`

**What**：新增 `POST /requirements/{requirementId}/clarify` 端点

**Why**：当前 openQuestions 仅展示阻断，无交互式追问。开发者需要回答 openQuestions 并触发方案重新生成。

**How**：
```java
@PostMapping("/requirements/{requirementId}/clarify")
@Operation(summary = "回答需求的开放问题并重新分析")
public Result<RequirementResponse> clarify(
        @PathVariable String projectId,
        @PathVariable String requirementId,
        @RequestBody ClarifyRequest request) {
    // 1. 加载原需求
    // 2. 将用户回答的 answers 合并到需求文本
    // 3. 重新调用 extractionService.extract
    // 4. 更新需求条目
    // 5. 返回更新后的分析结果
}
```

**文件 2**：`frontend/src/views/requirement/RequirementAnalysis.vue`

**What**：新增 openQuestions 交互式问答 UI

**How**：
- 展示 openQuestions 列表，每个问题下方有文本输入框
- 用户填写回答后点击"重新分析"按钮
- 调用 `/clarify` 端点，更新分析结果

### Change 7: G4 — Swagger/OpenAPI 提取器

**文件 1**：`backend/src/main/java/io/github/legacygraph/extractors/adapter/OpenApiSpecAdapter.java`（新建）

**What**：新建 OpenAPI 规范文件提取适配器

**Why**：当前无法从 OpenAPI 3.0 / Swagger 2.0 JSON/YAML 文件提取 API 端点，导致 API 覆盖率不完整。

**How**：
```java
@Component
public class OpenApiSpecAdapter implements ExtractionAdapter {

    @Override
    public ExtractionResult extract(ScanContext context, SourceAsset asset) {
        // 1. 解析 JSON/YAML 文件
        // 2. 遍历 paths → 每个端点创建 ApiEndpoint 节点
        // 3. 遍历 components/schemas → 每个模型创建 Entity 节点
        // 4. 创建 EXPOSED_BY 边（ApiEndpoint → Controller）
        // 5. 返回 ExtractionResult
    }

    @Override
    public AdapterCapability capability() {
        return AdapterCapability.builder()
                .name("OpenApiSpec")
                .fileTypes(Set.of("json", "yaml", "yml"))
                .frameworks(Set.of("openapi", "swagger"))
                .priority(10)
                .build();
    }
}
```

### Change 8: G5 — 跨语言实体消解增强

**文件**：`backend/src/main/java/io/github/legacygraph/builder/BusinessGraphBuilder.java`

**What**：增强 `mergeCrossLanguageFeatures` 方法，增加 embedding 语义匹配

**Why**：当前仅基于名称匹配，Vue 前端功能名"订单管理"与 Java Controller "OrderController" 无法关联。embedding 相似度 > 0.78 时创建 `POSSIBLE_SAME_AS` 边。

**How**：
```java
// 在 mergeCrossLanguageFeatures 中新增 embedding 语义消解
// 1. 对前端 Feature 节点和后端 Controller 节点分别生成 embedding
// 2. 计算余弦相似度
// 3. 相似度 > 0.78 时创建 POSSIBLE_SAME_AS 边
// 4. 使用 Feature Flag 控制（legacygraph.cross-language.embedding.enabled，默认 false）
```

### Change 9: 补充测试

**文件**：
- `SolutionPlanTest.java` — 验证 estimatedCost/riskAssessment/alternatives 字段
- `SolutionVerifierTest.java` — 新增成本/风险校验测试
- `SolutionToChangeTaskBridgeTest.java` — 桥接服务测试
- `CommunityDetectionServiceTest.java` — 社区摘要生成测试
- `ChangeTaskServiceTest.java` — onPrMerged 测试
- `QueryIntentTest.java` — 新意图枚举测试
- `OpenApiSpecAdapterTest.java` — OpenAPI 提取测试
- `RequirementControllerTest.java` — clarify 端点测试

## Assumptions & Decisions

1. **G7 成本估算粒度**：基于影响文件数和复杂度估算人天，不做精确的工时评估。理由：LLM 无法准确估算工时，但可以基于影响范围给出粗略的复杂度等级。
2. **G7 备选方案数量**：最多 2 个，避免 LLM 输出过长导致 token 超限。
3. **G8 桥接为手动触发**：不自动转换，由开发者在方案审核通过后手动点击"转为变更任务"。理由：自动执行需要更多护栏（如自动验证、自动 PR），当前阶段先打通路径。
4. **G8 taskType 推断**：基于 steps 的 actionType 分布推断，默认 REFACTOR。用户可在创建后手动修改。
5. **G3 社区摘要用 LLM 生成**：每个社区调用一次 LLM 生成一句话摘要。使用 Feature Flag 默认关闭，避免扫描耗时增加。
6. **G9 不自动重新扫描**：PR 合并后只标记节点 stale，不触发自动重新扫描。下次手动扫描时优先处理 stale 节点。
7. **G10 DIAGNOSIS 不接日志系统**：当前仅基于图谱路径分析诊断，不接入 APM/日志系统。日志接入是后续工作。
8. **G11 clarify 端点复用 extractionService**：重新调用 LLM 抽取，不新建抽取逻辑。
9. **G4 OpenApiSpecAdapter 优先级低于 Java 适配器**：priority=10（数值越大越靠后），避免与 Java 注解提取冲突。
10. **G5 embedding 消解默认关闭**：使用 Feature Flag 控制，因为 embedding 服务可用性不稳定。

## Verification

1. `mvn clean test` 全量通过，0 Failures，0 Errors
2. SolutionPlan 新增 estimatedCost/riskAssessment/alternatives 字段
3. solution-planning.txt prompt 要求输出成本/风险/备选方案
4. SolutionVerifier 新增第 8 类成本/风险校验
5. SolutionToChangeTaskBridge 可将 APPROVED 方案转为 ChangeTask
6. CommunityDetectionService 可生成社区摘要
7. ChangeTaskService.onPrMerged 可标记节点 stale
8. QueryIntent 新增 SOLUTION_EVALUATION 和 DIAGNOSIS
9. RequirementController 新增 clarify 端点
10. OpenApiSpecAdapter 可从 OpenAPI JSON/YAML 提取 ApiEndpoint 节点
11. BusinessGraphBuilder mergeCrossLanguageFeatures 增加 embedding 语义匹配
12. 数据库迁移脚本 V66 执行成功

## Tasks

### P1（高优先级）
1. G7-1: SolutionPlan 新增 estimatedCost/riskAssessment/alternatives 字段 + 内部类
2. G7-2: Solution 实体新增 estimatedCostJson/riskAssessmentJson/changeTaskId 字段 + V66 迁移脚本
3. G7-3: solution-planning.txt prompt 增加成本/风险/备选方案输出要求
4. G7-4: SolutionVerifier 新增第 8 类成本/风险校验 + 测试
5. G8-1: 新建 SolutionToChangeTaskBridge 桥接服务 + inferTaskType 逻辑
6. G8-2: SolutionController 新增 bridge 端点
7. G8-3: SolutionToChangeTaskBridge 测试
8. G3-1: CommunityDetectionService 新增 generateCommunitySummaries 方法
9. G3-2: ScanFinalizationService 新增社区摘要生成步骤（Feature Flag 控制）
10. G3-3: 社区摘要生成测试

### P2（中优先级）
11. G9-1: ChangeTaskService 新增 onPrMerged 方法 + stale 标记
12. G9-2: ChangeTaskController 新增 merge 端点
13. G9-3: onPrMerged 测试
14. G10-1: QueryIntent 新增 SOLUTION_EVALUATION 和 DIAGNOSIS 枚举
15. G10-2: EnhancedQaAgent 新增评估/诊断意图分支
16. G10-3: 意图路由测试
17. G11-1: RequirementController 新增 clarify 端点
18. G11-2: 前端 RequirementAnalysis.vue 新增 openQuestions 交互 UI
19. G11-3: clarify 端点测试

### P3（低优先级）
20. G4-1: 新建 OpenApiSpecAdapter 提取器
21. G4-2: 注册到 ExtractionAdapterRegistry
22. G4-3: OpenApiSpecAdapter 测试
23. G5-1: BusinessGraphBuilder mergeCrossLanguageFeatures 增加 embedding 语义匹配
24. G5-2: 跨语言消解测试

### 收尾
25. 集成验证: mvn clean test 全量通过
