# LegacyGraph 剩余项优化计划

## 摘要

经代码审查，用户列出的 P0-P2 问题中**大部分已在之前会话中修复**（字段名一致、constraints 已持久化、ACL/Release 参数已透传、测试签名已匹配、graph-release.enabled 已开启）。本计划聚焦于经核实后**确实仍然存在的问题**，按优先级修复。

## 当前状态分析

### 已修复项（无需再动）

| 原编号 | 问题 | 当前状态 |
|--------|------|---------|
| P0-1 | 前端 requirementText vs 后端 text 字段不一致 | **已修复** — 前端 API 层映射为 `text` 字段，后端 DTO 字段名也是 `text` |
| P0-2 | constraints/openQuestions 未持久化 | **已修复** — `rebuildAnalysis` line 263 从 `constraintsJson` 读取；openQuestions line 255 从 DB 读取 |
| P0-3 | ACL/GraphRelease 未接入 QA 召回链路 | **部分已修复** — EnhancedQaAgent line 273-278 透传参数；HybridRetrievalService 转发；EvidenceVerifier line 161-171 有 Release 校验。**但 VectorRetrievalService 静默丢弃过滤参数**（见下方 P0-A） |
| P1-1 | FileChangeDetectorTest 编译失败 | **已修复** — 测试 line 67 使用双参数构造器 `new FileChangeDetector(repository, jdbcTemplate)` |
| P1-2 | graph-release.enabled 默认关闭 | **已修复** — application.yml line 251-252 已设 `enabled: true`；EnhancedQaAgent 拒绝 FAILED/DRAFT/VALIDATING |
| P1-4 | 方案评审入口空页面 | **非问题** — ProjectDetail.vue line 201 进入列表模式（设计如此）；SolutionReview.vue 兼容有无 solutionId 两种模式 |

### 确认仍存在的问题

| 编号 | 优先级 | 问题 | 证据 |
|------|--------|------|------|
| A | P0 | 向量检索的 GraphRelease/ACL 过滤被 feature flag 静默关闭 | `VectorRetrievalService.java:49` `@Value("${legacygraph.qa.graph-release-filter.enabled:false}")`，application.yml 未设置此属性 |
| B | P1 | V64 测试用例 SQL 写死 LegacyGraph 示例实体 | `V64__qa_test_case.sql` line 29-41 硬编码 `lg_account`、`OrderMapper` 等 |
| C | P1 | `resolveLatestVersionId` 返回 null 时未防御 | SolutionController line 288-294 和 RequirementController line 306-312，项目无扫描版本时返回 null |
| D | P2 | tasks.md / checklist.md 状态漂移 | 父任务标记 `[x]` 但子任务全 `[ ]`；checklist 全标 `[x]` 但 tasks.md 不一致 |
| E | P2 | FullPipelineIntegrationTest 覆盖不全 | 仅覆盖 scan→extract→build，不含 GraphRelease→需求→方案→QA |

## 修复方案

### 修复 A（P0）：启用向量检索的 GraphRelease/ACL 过滤

**问题根因**：`VectorRetrievalService` line 49 的 `@Value("${legacygraph.qa.graph-release-filter.enabled:false}")` 默认关闭。line 169-171 在 feature flag 关闭时将 `graphReleaseId` 和 `aclPrincipal` 置为 `null`，导致向量检索路径不应用任何过滤。虽然 HybridRetrievalService 的关键词搜索路径已硬编码过滤，但向量检索路径被静默旁路。

**修改文件**：
- `backend/src/main/resources/application.yml`

**修改内容**：
在 `legacygraph` 配置块下（与 `graph-release.enabled: true` 同级）添加 QA 过滤开关：
```yaml
legacygraph:
  # ... 现有配置 ...
  graph-release:
    enabled: true
  qa:
    graph-release-filter:
      # Task 8.3：向量检索 GraphRelease + ACL 过滤开关
      # 开启后向量检索按 graphReleaseId / aclPrincipal 过滤，与关键词检索一致
      enabled: true
```

**影响分析**：开启后向量检索会按 `graphReleaseId` 和 `aclPrincipal` 过滤，与关键词检索行为一致。缓存键计算会包含这两个维度（line 175-176），旧缓存自然失效。无破坏性变更。

### 修复 B（P1）：V64 测试用例改为项目无关的通用模板

**问题根因**：`V64__qa_test_case.sql` 的 SMOKE/GOLDEN 用例的 `expected_entities` / `expected_keywords` 字段硬编码了 LegacyGraph demo 项目的实体名（`lg_account`、`OrderMapper`、`AccountController` 等），对新接入项目造成误拦截或评测失真。

**修改文件**：
- `backend/src/main/resources/db/migration/V64__qa_test_case.sql`

**修改内容**：
将 `expected_entities` 和 `expected_keywords` 字段改为通用占位值（空数组或通用描述），并添加注释说明这些用例是**结构模板**而非具体项目数据。具体方案：
- `expected_entities` 改为空 JSON 数组 `[]`
- `expected_keywords` 改为空 JSON 数组 `[]`
- `expected_answer` 改为通用描述（如"请基于项目实际图谱回答"）
- 保留用例的 `question` / `category` / `difficulty` / `status` 等结构化字段不变

用例变为"结构正确性校验"而非"实体匹配校验"，`entityRecall` / `keywordCoverage` 指标在空期望集时自动跳过。

### 修复 C（P1）：`resolveLatestVersionId` 返回 null 时添加防御

**问题根因**：`SolutionController.resolveLatestVersionId`（line 288-294）和 `RequirementController.resolveLatestVersionId`（line 306-312）在项目无扫描版本时返回 `null`，该 null 被传递给 `verifier.verify`、`linkingService.link`、`impactService.extract` 等下游服务，可能导致 NPE 或空指针查询。

**修改文件**：
- `backend/src/main/java/io/github/legacygraph/controller/SolutionController.java`
- `backend/src/main/java/io/github/legacygraph/controller/RequirementController.java`

**修改内容**：
在两个 `resolveLatestVersionId` 方法中添加 null 检查，当返回 null 时抛出明确的业务异常：
```java
private String resolveLatestVersionId(String projectId) {
    LambdaQueryWrapper<ScanVersion> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(ScanVersion::getProjectId, projectId)
            .orderByDesc(ScanVersion::getStartedAt)
            .last("LIMIT 1");
    ScanVersion version = scanVersionRepository.selectOne(wrapper);
    if (version == null) {
        throw new IllegalStateException(
                "项目 " + projectId + " 无可用扫描版本，请先完成至少一次扫描");
    }
    return version.getId();
}
```

**影响分析**：将静默的 null 传递转变为显式异常，前端可捕获并提示用户先执行扫描。不影响已有扫描版本的项目。

### 修复 D（P2）：同步 tasks.md 和 checklist.md 状态

**问题根因**：`tasks.md` 中 Task 11/12/13 的父任务标记为 `[x]` 但所有子任务为 `[ ]`；`checklist.md` 中对应条目全标 `[x]`。两个文件状态不同步。

**修改文件**：
- `.trae/specs/scan-to-qa-pipeline/tasks.md`
- `.trae/specs/scan-to-qa-pipeline/checklist.md`

**修改内容**：
1. **tasks.md**：将 Task 12 和 Task 13 的父任务标记从 `[x]` 改为 `[ ]`（与子任务状态一致）
2. **checklist.md**：将标注为 `[x]` 但实际未完成的条目改为 `[ ]`：
   - "全链路集成测试通过" → `[ ]`（FullPipelineIntegrationTest 不含全链路）
   - "需求→方案→验证链路集成测试通过" → 确认是否有 `RequirementToSolutionIntegrationTest`，如有则保留 `[x]`
3. 在 checklist.md 末尾添加备注，说明当前实际完成范围

### 修复 E（P2）：FullPipelineIntegrationTest 补充全链路覆盖说明

**问题根因**：`FullPipelineIntegrationTest` 仅覆盖 scan→extract→build graph，不包含 GraphRelease 发布、需求分析、方案生成、QA 问答。但 checklist 中标注为"全链路集成测试通过"。

**修改文件**：
- `backend/src/test/java/io/github/legacygraph/extractors/adapter/FullPipelineIntegrationTest.java`

**修改内容**：
在类 Javadoc 中明确标注实际覆盖范围和未覆盖部分：
```java
/**
 * 全链路集成测试（前半段：扫描→抽取→图谱构建）。
 * <p>
 * <b>覆盖范围</b>：ScanContext 构建 → 文件遍历 → 适配器选择 → 事实抽取 → Fact 落库 → 图谱构建。
 * </p>
 * <p>
 * <b>未覆盖范围</b>（需单独测试）：
 * <ul>
 *   <li>GraphRelease 发布与质量门禁</li>
 *   <li>需求分析（RequirementController）</li>
 *   <li>方案生成与校验（SolutionController）</li>
 *   <li>QA 问答全链路（EnhancedQaAgent）</li>
 * </ul>
 * </p>
 */
```

**不新增测试代码**：全链路集成测试需要 Neo4j + PostgreSQL + LLM 等完整环境，作为单元测试不合适。仅修正文档标注，避免误导。

## 验证步骤

### 1. 编译验证
```bash
cd /Users/huymac/工作/数智/LegacyGraph
mvn -f backend/pom.xml clean test-compile -q
```
预期：BUILD SUCCESS，无编译错误。

### 2. 全量测试验证
```bash
mvn -f backend/pom.xml clean test 2>&1 | tail -40
```
预期：`Tests run: ~1976, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

### 3. 配置验证
确认 application.yml 中两个开关均开启：
- `legacygraph.graph-release.enabled: true`
- `legacygraph.qa.graph-release-filter.enabled: true`

### 4. 文档一致性检查
确认 tasks.md 父子任务状态一致，checklist.md 与 tasks.md 状态一致。

## 假设与决策

1. **不修改已修复项**：P0-1（字段名）、P0-2（constraints 持久化）、P1-1（测试签名）、P1-2（graph-release.enabled）已在之前会话修复，不重复修改。
2. **P1-4（方案评审空页面）判定为非问题**：列表模式是设计意图，非 bug。
3. **V64 改为空期望集而非删除**：保留测试用例结构框架，仅清空项目专属数据。这比删除迁移文件更安全（已执行的迁移不可回滚）。
4. **FullPipelineIntegrationTest 不新增全链路测试**：真正的全链路测试需要完整中间件环境，适合作为集成测试或手动验证，不适合放在单元测试中。
5. **resolveLatestVersionId 抛 IllegalStateException 而非自定义异常**：与项目现有异常风格一致（Controller 层有全局异常处理）。
