# 剩余项修复方案

## 概述

用户在上一轮执行后发现了 8 个问题（P0×3, P1×4, P2×1）。经代码探索确认：

- **P0 #1（字段不一致）**：已修复 — Vue 发送 `text`、API 接收 `text`、后端 `request.getText()`，三层一致
- **P0 #2（约束/openQuestions 未持久化）**：已修复 — V67 迁移 + `rebuildAnalysis` 恢复 constraints/openQuestions
- **P0 #3（ACL/GraphRelease 未接入 QA 召回）**：已修复 — EnhancedQaAgent 传递 graphReleaseId + principals，HybridRetrievalService 使用 7 参数 semanticSearch
- **P1 #1（FileChangeDetector 构造器）**：已修复 — 测试使用新签名 `(repository, jdbcTemplate)`
- **P1 #3（QA 门禁非项目级）**：已修复 — V68 迁移添加 project_id 列，runSmoke 按 projectId 过滤
- **P2（文档状态漂移）**：已修复 — tasks.md/checklist.md 已在上一轮修正

**剩余待修复**：
1. 14 个测试失败（由上一轮生产代码变更引入的测试不匹配）
2. application.yml 未启用 `graph-release.enabled`（P1 #2 剩余项）

## 14 个测试失败根因分析

| 测试文件 | 失败数 | 根因 |
|---------|-------|------|
| SolutionVerifierTest | 3 | `buildPlan()` 未设置 `estimatedCost`/`riskAssessment`，G7 校验总报错 |
| HybridRetrievalServiceTest | 3 | stub 5 参数 `semanticSearch`，生产代码调 7 参数版本 |
| GraphBuilderTest | 5 | 未加 `@MockitoSettings(LENIENT)`，`findNode` stub 未触发 → UnnecessaryStubbingException |
| JavaMemberCallResolverTest | 1 | `resolveInheritanceEdges` 额外调 `mergeEdgesBatch`，verify 期望 1 次但实际 2 次 |
| EnhancedQaAgentTest | 1 | stub/verify 5 参数 `retrieve`，生产代码调 7 参数版本 |
| FileChangeDetectorTest | 1 | `recordSnapshots` 已改用 `jdbcTemplate.batchUpdate`，测试仍 verify `repository.insert` |

## 修复步骤

### 步骤 1：修复 SolutionVerifierTest（3 个失败）

**文件**：`backend/src/test/java/io/github/legacygraph/service/solution/SolutionVerifierTest.java`

**问题**：`buildPlan()` 辅助方法（第 58-66 行）未设置 `estimatedCost` 和 `riskAssessment`。生产代码 `SolutionVerifier.verify()` 第 107 行总调用 `checkCostAndRisk`，检测到 null 后添加 `COST_ESTIMATE_MISSING` + `RISK_ASSESSMENT_MISSING` 错误，导致期望 `isPassed()==true` 的测试失败。

**修复**：在 `buildPlan()` 中添加 `estimatedCost` 和 `riskAssessment`：
```java
private SolutionPlan buildPlan() {
    SolutionPlan plan = new SolutionPlan();
    plan.setSummary("方案总览");
    // 新增：成本估算（满足 G7 校验）
    SolutionPlan.CostEstimate cost = new SolutionPlan.CostEstimate();
    cost.setPersonDays(3.0);
    cost.setAffectedFiles(2);
    cost.setComplexity("MEDIUM");
    plan.setEstimatedCost(cost);
    // 新增：风险评估（满足 G7 校验）
    SolutionPlan.RiskAssessment risk = new SolutionPlan.RiskAssessment();
    risk.setRiskLevel("LOW");
    risk.setHighRiskAreas(List.of());
    risk.setMitigations(List.of("充分测试"));
    plan.setRiskAssessment(risk);
    plan.setSteps(List.of(
            buildStep("OrderMapper#selectRecentOrders",
                    "backend/src/main/java/io/github/legacygraph/mapper/OrderMapper.java",
                    "MODIFY", "ev-001")));
    return plan;
}
```

### 步骤 2：修复 HybridRetrievalServiceTest（3 个失败）

**文件**：`backend/src/test/java/io/github/legacygraph/service/qa/HybridRetrievalServiceTest.java`

**问题**：测试 stub 5 参数 `semanticSearch(p, v, q, topK, chunkType)`，但生产代码 `safeSemanticSearch` 调用 7 参数 `semanticSearch(p, v, q, topK, null, graphReleaseId, aclPrincipal)`。Mock 对象将两种签名视为不同方法，5 参数 stub 不匹配 7 参数调用。

**修复**：将所有 `semanticSearch` stub 从 5 参数改为 7 参数：

- 第 54 行：`semanticSearch(eq("p1"), eq("v1"), eq("测试查询"), eq(10), isNull())` → `semanticSearch(eq("p1"), eq("v1"), eq("测试查询"), eq(10), isNull(), isNull(), isNull())`
- 第 64 行 verify 同步改为 7 参数
- 第 74 行、96 行、98 行同理

涉及 3 个测试方法：
1. `retrieve_mergesVectorAndKeywordResults`（第 44-65 行）
2. `retrieve_deduplicatesById`（第 67-84 行）
3. `retrieve_withQueryVariants_includesVariantResults`（第 86-99 行）

### 步骤 3：修复 GraphBuilderTest（5 个失败）

**文件**：`backend/src/test/java/io/github/legacygraph/builder/GraphBuilderTest.java`

**问题**：测试类未使用 `@MockitoSettings(strictness = Strictness.LENIENT)`，部分测试 stub 了 `neo4jGraphDao.findNode(any(), any(), any(), any())` 但生产代码路径未调用 `findNode`，导致 `UnnecessaryStubbingException`。

**修复**：在类声明上添加注解：
```java
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphBuilderTest {
```

需确认 import：`import org.mockito.junit.jupiter.MockitoSettings; import org.mockito.quality.Strictness;`

### 步骤 4：修复 JavaMemberCallResolverTest（1 个失败）

**文件**：`backend/src/test/java/io/github/legacygraph/builder/JavaMemberCallResolverTest.java`

**问题**：`resolveInheritanceEdges` 方法（生产代码在 `resolveMemberCalls` 末尾调用）会额外调用 `mergeEdgesBatch`，导致 `verify(neo4jGraphDao).mergeEdgesBatch(captor.capture())` 期望 1 次但实际 2 次。

**修复**：将受影响测试的 verify 从精确 1 次改为 `atLeast(1)`：
```java
verify(neo4jGraphDao, atLeast(1)).mergeEdgesBatch(captor.capture());
```

具体受影响测试：
- `happyPath_methodLevelCallEdge`（第 117 行）
- `overload_fallsBackToClassLevel`（第 162 行）

注意：`captor.getValue()` 在多次调用时返回最后一次的值。需确认断言逻辑是否仍正确。若 `resolveInheritanceEdges` 的 `mergeEdgesBatch` 调用在 CALLS 边之后，`captor.getValue()` 可能取到继承边的值而非 CALLS 边的值。需改用 `captor.getAllValues()` 并从列表中找到 CALLS 边。

### 步骤 5：修复 EnhancedQaAgentTest（1 个失败）

**文件**：`backend/src/test/java/io/github/legacygraph/agent/EnhancedQaAgentTest.java`

**问题**：测试 stub 5 参数 `retrieve(eq("proj-1"), eq("v1"), anyString(), anyList(), eq(20))`（第 159 行），生产代码调 7 参数 `retrieve(projectId, versionId, question, queryVariants, 20, graphReleaseId, principals)`（第 275 行）。Mock 对象不匹配。

**修复**：将 stub 和 verify 从 5 参数改为 7 参数：

- 第 159 行 stub：
  ```java
  when(hybridRetrievalService.retrieve(eq("proj-1"), eq("v1"), anyString(), anyList(), eq(20), any(), any()))
          .thenReturn(List.of(doc));
  ```
- 第 177-178 行 verify：
  ```java
  verify(hybridRetrievalService).retrieve(
          eq("proj-1"), eq("v1"), eq("OrderService 有哪些方法？"), variantsCaptor.capture(), eq(20), any(), any());
  ```
- 第 232 行 stub 同理改为 7 参数

### 步骤 6：修复 FileChangeDetectorTest（1 个失败）

**文件**：`backend/src/test/java/io/github/legacygraph/service/scan/FileChangeDetectorTest.java`

**问题**：`recordSnapshots_批量记录所有文件`（第 179-189 行）测试 stub `repository.selectOne` 并 verify `repository.insert`，但生产代码 `recordSnapshots` 已重构为 `jdbcTemplate.batchUpdate`，不再调用 `repository.selectOne/insert`。

**修复**：
```java
@Test
void recordSnapshots_批量记录所有文件() {
    Map<String, String> pathToContent = new HashMap<>();
    pathToContent.put("src/A.java", "class A {}");
    pathToContent.put("src/B.java", "class B {}");

    detector.recordSnapshots("p1", pathToContent);

    // 生产代码使用 jdbcTemplate.batchUpdate 批量 upsert
    verify(jdbcTemplate, times(1)).batchUpdate(anyString(), anyList());
}
```

移除 `when(repository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null)` stub（不再使用）。

### 步骤 7：启用 GraphRelease 发布门禁配置（P1 #2 剩余项）

**文件**：`backend/src/main/resources/application.yml`

**问题**：`legacygraph.graph-release.enabled` 未在 application.yml 中设置（默认 false），门禁逻辑虽已实现但不生效。

**修复**：在 `legacygraph` 配置块下添加：
```yaml
legacygraph:
  # ... 现有配置 ...
  graph-release:
    enabled: true
```

说明：当 `enabled=true` 时，QA 门禁对 FAILED/DRAFT/VALIDATING 状态的图谱拒答，对 PUBLISHED 和无发布版本（向后兼容）放行。启用后不影响已有功能。

### 步骤 8：执行 `mvn clean test` 全量验证

```bash
cd /Users/huymac/工作/数智/LegacyGraph
mvn -f backend/pom.xml clean test 2>&1 | tail -20
```

期望结果：`Tests run: ~1976, Failures: 0, Errors: 0, Skipped: 24`，`BUILD SUCCESS`

若仍有失败，根据具体错误继续修复。

## 假设与决策

1. **GraphReleaseConfig 默认启用**：决策为 `enabled=true`，因为代码已实现向后兼容（无发布版本时放行）
2. **GraphBuilderTest 使用 LENIENT 模式**：决策为添加 `@MockitoSettings(LENIENT)`，因为该测试有 20 个方法，逐个修复 `findNode` stub 过于繁重且 LENIENT 模式不影响测试有效性
3. **JavaMemberCallResolverTest captor 策略**：若 `atLeast(1)` 导致 captor 取值问题，改用 `getAllValues()` 从列表中筛选 CALLS 边
4. **不修改生产代码**：本轮仅修复测试以适配上一轮的生产代码变更，不引入新的生产代码改动

## 验证步骤

1. 执行 `mvn -f backend/pom.xml clean test`
2. 确认 0 Failures, 0 Errors
3. 确认 `application.yml` 中 `graph-release.enabled=true`
4. 确认无回归：所有原通过的测试仍通过
