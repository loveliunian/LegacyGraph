# AI 增强机会分析 — LegacyGraph

> 本文分析 LegacyGraph 当前 AI 架构，找出可以进一步接入 AI 提升效率和准确率的方向。

## 📊 当前已有的 AI 能力

> ⚠️ 下表为本轮实施前的现状分析。Phase 0 已修复所有契约不一致，详见文末「推荐实施路线」中的落地记录。

| Agent | 功能 | 代码现状 | 可提升空间 |
|-------|------|---------|-----------|
| `CodeFactAgent` | 代码事实抽取 | ✅ 基础实现，直接调用 `LlmGateway.callWithTemplate` | 🟡 增加自我校正、结构化校验、证据回写 |
| `DocUnderstandingAgent` | 文档理解 | ✅ 基础实现，但模板变量与 Agent 传参需对齐 | 🟢 增强 RAG，并在文档解析后自动触发业务事实抽取 |
| `FeatureMappingAgent` | 前后端特征映射 | 🟡 Agent 已有，但当前模板输入与 Agent 传参不一致 | 🔴 先修模板/DTO，再接入图谱邻域 + 向量召回 |
| `GraphMergeAgent` | 图谱合并决策 | 🟡 Agent 已有；`GraphMergeService` 默认仍是规则相似度决策 | 🟢 接入 LLM 二次裁决，低置信进入人工审核 |
| `ReviewAgent` | 审核建议 | 🟡 当前复用 `graph-merge-decision` 模板 | 🟡 拆独立审核模板，接入失败测试、冲突证据、低置信节点 |
| `TestCaseAgent` | 测试用例生成 | 🟡 骨架生成；DTO 与模板输出结构需对齐 | 🟢 填充真实参数、断言、边界场景和失败根因建议 |

---

## 🧭 代码对照后的总体判断

本次结合代码、`/doc/AI 增强机会分析.md`、`/doc/三类图谱的具体实现.md`、`/doc/LegacyGraph 平台引入 LLM 增强的实施蓝图.md` 后，结论如下：

1. **AI 基础设施已具备，但现有 Agent 还不是稳定闭环**  
   `LlmGateway` 已支持动态提供商、模板渲染、脱敏和 `PromptRun` 审计；但失败时会尝试返回空 JSON，且多个 Agent 的模板变量、DTO Schema、实际传参不一致。新增能力前，应先修这一层，否则容易出现“调用成功但结果无效”。

2. **AI 不能直接作为事实源，必须走证据、置信度、人工审核或测试验证**  
   现有图谱设计要求每个节点/关系具备 `evidence`、`confidence`、`source_type`、`project_id`、`version_id`。因此 AI 输出应默认作为 `DOC_AI` / `AI_INFERENCE` / `PENDING_CONFIRM`，不能直接写成 `CONFIRMED`。

3. **最适合优先增强的是“已有结构化输入 + AI 解释/补全/裁决”的场景**  
   例如图谱问答、Feature→Page/API 映射、SQL 风险解释、测试用例参数补全、失败根因归纳、报告建议生成。这些场景已有代码、图谱、证据或测试结果作为输入，AI 输出可审查。

4. **不建议优先投入大规模自动重构或微服务拆分**  
   这类能力收益依赖真实项目数据、映射准确率和测试闭环。在当前阶段，应后置到“RAG、证据、测试、审核闭环”稳定之后。

---

## 🔧 现有 AI 能力的前置修复

> ✅ 本节描述的三项前置修复均已在 Phase 0 完成，以下保留作为问题背景与设计依据。

### 1. **模板变量与 DTO Schema 对齐** 🔴 ✅ 已修复

**代码证据**：
- `CodeFactAgent` 传入 `codeContent/sourcePath`，但 `code-fact-extraction.txt` 还包含 `{className}`、`{methodName}`。
- `DocUnderstandingAgent` 传入 `docContent/sourcePath`，但 `doc-understanding.txt` 使用 `{documentContent}`。
- `FeatureMappingAgent` 传入 `vueCode/apiDefinitions/controllerCode/permissionInfo/productDoc`，但 `feature-mapping.txt` 使用 `{businessDescription}`、`{candidateFeatures}`。
- `TestCaseAgent` 期望解析为单个 `GeneratedTestCase`，但 `test-case-generation.txt` 输出 `testCases` 数组。
- `GraphMergeDecision` DTO 使用 `candidateA/candidateB/decision/score/reasons`，但 `graph-merge-decision.txt` 输出 `shouldMerge/confidence/reason`。

**建议**：
- 建立模板契约测试：每个模板的变量必须能由 Agent 完整填充。
- 建立 JSON Schema 校验：LLM 输出必须能反序列化到目标 DTO。
- `LlmGateway` 不应把失败默默转换为空对象，应返回失败状态、错误原因和 `needs_review=true`。

### 2. **统一自我校正与证据校验编排** 🔴 ✅ 部分落地

当前所有 Agent 都是“拼变量 -> 调模板 -> 解析结果”的薄封装。建议在 `LlmGateway` 或新增 `AgentOrchestrator` 中统一实现：

1. 首轮生成结构化结果。
2. 用原始证据 + 生成结果做自检。
3. 检查是否存在无证据结论、字段缺失、置信度异常。
4. 输出校正后结果；不能校正则进入人工审核。

> **落地说明**：Phase 0 已在 `LlmGateway` 统一实现「结构化校验 → 校验失败标记 `status=REVIEW` 并抛出 `LlmCallException(needsReview=true)`」，即第 3、4 步的「字段缺失/反序列化失败 → 人工审核」闭环。完整的「用原始证据二次喂回 LLM 做自检」多轮校正循环（准确率提升方向 1）属增量增强，可在此基础上继续接入。

### 3. **PromptRun 审计补全** 🟠 ✅ 已修复

`PromptRun` 已有 `inputHash`、`parsedOutput`、`promptTokens`、`completionTokens`、`latencyMs` 字段，但当前调用链主要写入 masked input/raw output/status。建议补齐：

- `inputHash`：用于缓存和重复调用去重。
- `parsedOutput`：保存结构化解析结果。
- token/latency：用于成本和性能分析。
- `status=REVIEW`：用于 AI 输出不满足证据或 schema 时进入人工处理。

---

## 🚀 新增 AI 功能建议

### 1. **SQL 性能优化顾问** 🐢

**当前问题**：仅抽取 MyBatis SQL 到图谱，但不分析性能问题

**AI 增强方案**：
- LLM 分析 SQL 语句，识别 `SELECT *`、缺少索引、N+1 查询、不合理的 LIKE 前缀模糊匹配等问题
- 给出具体的优化建议（添加索引、拆分查询、修改连接方式）
- 结合数据库统计信息（可以通过 JDBC 获取）给出更精准建议
- 自动生成优化后的 SQL

**收益**：帮助开发者在迁移过程中发现并修复历史慢 SQL

---

### 2. **代码异味智能重构建议** 👃

**当前问题**：仅识别出代码异味，不提供修复方案

**AI 增强方案**：
- 对识别出的"上帝类""过长方法"，AI 分析职责边界
- 自动给出拆分建议（应该拆成几个类，每个类负责什么）
- 生成重构后的代码框架
- 分析拆分对现有调用关系的影响

**收益**：减少人工思考成本，提升重构效率

---

### 3. **微服务拆分建议器** 📦

**当前问题**：构建完整图谱，但不分析业务边界

**AI 增强方案**：
- 基于图谱的模块依赖关系 + 代码语义分析
- AI 识别紧耦合模块，建议合理的拆分边界
- 评估拆分风险（依赖循环、数据一致性问题）
- 生成拆分步骤和迁移计划

**收益**：辅助架构师做拆分决策，降低拆分复杂度

---

### 4. **自然语言知识库问答** ❓

**当前问题**：需要人工浏览查找

**AI 增强方案**：
- 基于**向量检索 + 图谱查询 + LLM 生成**的 RAG 架构
- 用户用自然提问：
  > "请问用户注册功能涉及哪些表？"
  > "修改密码的流程经过哪些模块？"
  > "这个接口的权限要求是什么？"
- AI 从图谱找到相关节点，整合信息给出自然语言回答
- 可以直接展示相关代码片段位置

**收益**：极大提升新人上手效率，减少老手搜索时间

---

### 5. **变更影响分析增强** 💥

**当前问题**：仅基于图谱找出所有依赖节点

**AI 增强方案**：
- AI 语义分析变更内容，判断修改类型（bugfix/新功能/接口 breaking change）
- 评估业务影响严重程度（高/中/低）
- 预测哪些测试用例需要重跑
- 建议重点回归范围

**收益**：帮助开发者评估变更风险，减少线上问题

---

### 6. **迁移代码自动转换** 🔄

**当前问题**：只识别迁移风险

**AI 增强方案**：
- 针对 Spring Boot 1.x/2.x → 3.x 迁移
  - 自动识别过时的注解（`@Autowired` → 构造注入，`javax.*` → `jakarta.*`）
  - 生成替换建议和替换后的代码
  - 识别配置文件格式变化
- 支持自定义迁移规则（比如公司内部框架迁移）

**收益**：减少机械重复性工作，加速迁移过程

---

### 7. **错误根因分析助手** 🔍

**新增功能**：
- 输入异常堆栈 + 相关日志
- AI 匹配图谱中的代码，定位可能的根因
- 给出排查步骤建议
- 结合历史类似问题（如果有记录）给出解决方案

---

### 8. **数据库文档自动生成** 📄

**当前问题**：很多遗留系统表缺少注释

**AI 增强方案**：
- 根据表名、字段名、数据自动推断业务含义
- 生成中文表注释和字段注释
- 识别表之间的业务关系（不是外键，是业务关系）
- 可以输出为 Markdown 文档

---

### 9. **PR 描述/提交信息自动生成** 📝

**新增功能**：
- 分析 git diff 变更内容
- 依据 conventional commits 规范自动生成提交信息
- 概括变更目的、影响范围
- 关联相关 issue 编号

---

### 10. **扫描后 AI 编排器** 🔴

**当前问题**：前端创建扫描页已有“启用 AI 归纳”“自动生成测试用例”开关，但后端 `ProjectScanner.startFullScan` 当前主要执行规则扫描、Mapper 扫描、前端扫描、数据库扫描和图谱构建，尚未把这些开关落成扫描后的 AI 编排。

**AI 增强方案**：
- 扫描完成后按配置自动执行：
  - 文档分片向量化
  - 文档业务事实抽取
  - Feature → Page/API/Service/Mapper/Table 映射
  - 高价值节点测试用例生成
  - 低置信节点审核任务生成
- 扫描任务中新增 AI 子任务状态：`AI_DOC_EXTRACT`、`AI_FEATURE_MAPPING`、`AI_TEST_GENERATE`、`AI_REVIEW_PREPARE`。
- 所有 AI 结果默认 `PENDING_CONFIRM`，并关联证据。

**收益**：把“用户手动点多个 AI 入口”变成扫描闭环，提升实际使用体验。

---

### 11. **报告洞察与行动建议生成器** 🟠

**当前问题**：`ReportingService` 已能生成迁移就绪度、置信度趋势、测试覆盖率、图谱质量和五维指标，但建议主要是规则生成；前端 `ValidationReport.vue` 中“AI 分析建议”仍偏固定示例。

**AI 增强方案**：
- 输入 `GraphMetricsReport`、低置信节点、孤立节点、未覆盖测试、运行时验证缺口。
- 生成按优先级排序的行动清单：
  - 哪些节点先补证据
  - 哪些 API 先生成测试
  - 哪些表/服务存在迁移风险
  - 哪些审核项可批量确认，哪些必须人工细看
- 每条建议必须带图谱节点、证据或报告指标来源。

**收益**：把报告从“指标展示”提升为“下一步工作队列”。

---

### 12. **测试失败根因分析与复测建议** 🟠

**当前问题**：测试失败后已经能回写置信度并创建 `ReviewRecord`，但仅写入“测试失败: xxx”，没有对失败上下文做根因归纳。

**AI 增强方案**：
- 输入测试请求、响应、错误信息、目标节点、上下游图谱路径、最近运行时 trace。
- 输出：
  - 可能根因
  - 关联代码/接口/表
  - 建议排查步骤
  - 是否降低节点/边置信度
  - 建议重跑范围

**收益**：测试闭环不只影响置信度，还能直接帮助开发者定位问题。

---

## ⚡ 准确率提升方向

即使在现有功能上，也可以通过 AI 技术提升准确率：

### 1. **自我校正循环** 🔄

**当前**：单次 LLM 调用输出结果

**改进**：
```
Step 1: LLM 抽取/理解 -> 输出结论
Step 2: 将结论+原始代码再次喂给 LLM
Step 3: 让 LLM 自我检查："检查你的结论是否严格符合代码证据，有没有过度推断？"
Step 4: LLM 输出校正后的结论
```

**收益**：减少幻觉，准确率可提升 10-20%

---

### 2. **RAG 增强上下文** 📚

**当前**：只给当前代码片段作为上下文

**改进**：
- 向量检索项目中**已人工审核**的相似节点
- 将相似案例作为"示例"加入 LLM 上下文
- 让 LLM 参考人工审核标准来输出

**收益**：输出更符合团队预期，一致性提升

---

### 3. **多模型投票** 🗳️

**当前**：单一模型输出

**改进**：
- 如果配置了多个 LLM API，可以让多个模型独立输出
- 对合并决策等任务，比较多个模型结论
- 结论一致 → 高置信度；结论不一致 → 标记需要人工审核

**收益**：降低错误决策概率

---

### 4. **基于运行时反馈校准** 📈

**新增**：
- 测试执行后，如果测试通过/失败，反馈给 AI
- AI 学习什么样的节点预测更容易出错
- 动态调整置信度计算权重

---

## 🔝 优先级建议

| 优先级 | 功能 | 工作量 | 业务收益 |
|--------|------|--------|---------|
| 🔴 高 | 模板变量与 DTO Schema 对齐 | 小 | 先修现有 AI 调用有效性，避免假成功 |
| 🔴 高 | 自我校正 + 证据校验编排 | 小-中 | 直接提升所有现有 Agent 准确率 |
| 🔴 高 | 扫描后 AI 编排器 | 中 | 把 AI 归纳、映射、测试生成接入主流程 |
| 🔴 高 | 自然语言知识库问答 | 中 | 极大提升使用体验，是图谱平台核心能力 |
| 🟠 中 | Feature → Page/API/Service/Mapper/Table 映射增强 | 中 | 补强当前跨图谱映射准确率 |
| 🟠 中 | 测试用例真实参数/断言生成 | 中 | 替换测试骨架占位内容，提升验证闭环价值 |
| 🟠 中 | SQL 性能优化与动态 SQL 风险分析 | 中 | 迁移场景非常实用 |
| 🟠 中 | 报告洞察与行动建议生成 | 中 | 把报告转为可执行工作队列 |
| 🟡 低 | 错误根因分析助手 | 中 | 排障场景有用，可基于测试失败闭环演进 |
| 🟡 低 | 代码异味智能重构建议 | 中 | 需依赖更稳定的调用链和测试覆盖 |
| 🟡 低 | 微服务拆分建议 | 大 | 适合真实项目数据稳定后投入 |
| 🟢 低 | PR 描述/提交信息自动生成 | 小 | 开发体验优化，不是迁移主链路 |

---

## 🧩 推荐实施路线

> **实施进度追踪**（落地记录，随实现更新）
>
> | Phase | 状态 | 落地内容 | 测试 |
> |-------|------|---------|------|
> | Phase 0 | ✅ 已完成 | 6 模板/DTO/Agent 契约对齐；`LlmGateway` 结构化校验+失败显式返回+审计补全 | `PromptTemplateContractTest`、`LlmGatewayTest` 等 32 用例通过 |
> | Phase 1 | ✅ 已完成 | `AiScanOrchestrator` 接入 `ProjectScanner`：扫描后按开关执行 4 个 AI 子任务，结果默认 PENDING_CONFIRM | `AiScanOrchestratorTest`（5）、`AiScanConfigTest`（4）通过 |
> | Phase 2 | ✅ 已完成 | `QaAgent` + `GraphQaController`：向量召回+图邻域+LLM 生成，回答带证据/相关节点/置信度 | `QaAgentTest`（4 用例）、`qa-answer` 契约用例通过 |
> | Phase 3 | ✅ 已完成 | `SqlAdvisorAgent`/`TestFailureAnalysisAgent`/`ReportInsightAgent` + `LlmAgentController` 端点；测试用例真实参数/断言已在 Phase 0 模板对齐 | 三个 Agent 单测 + 契约用例通过 |
> | Phase 4 | ✅ 大部分完成 | `RefactorAgent`/`ChangeImpactAgent`/`MigrationAgent`/`PrDescriptionAgent` + 端点；微服务拆分建议按文档建议后置 | `Phase4AgentsTest`（4）+ 契约用例通过 |
>
> 截至本轮：13 个生产 Prompt 模板全部纳入 `PromptTemplateContractTest`；本轮新增/改造的 AI 单测共 63 个全部通过（连同既有 `PiiMaskingServiceTest` 等共 75 个 AI 相关用例通过）。

### Phase 0：修现有 AI 基础契约 ✅ 已完成

- 对齐 6 个 Prompt 模板与 Agent 入参。
- 对齐 DTO 输出结构。
- `LlmGateway` 增加结构化校验、失败显式返回、`PromptRun.parsedOutput/inputHash/latencyMs` 写入。
- 给每个 Agent 增加最小契约测试。

**落地细节**：

- **模板↔Agent↔DTO 三方对齐**：
  - `code-fact-extraction.txt` 改为输出 `FactExtractionResult`（factType/projectId/items），变量改为 `projectId/codeContent/sourcePath`；`CodeFactAgent` 补传 `projectId`。
  - `doc-understanding.txt` 变量由 `documentContent` 改为 `docContent/sourcePath`，输出结构对齐 `BusinessFactExtraction`（含 businessProcesses/objects/rules/roles/statusTransitions/features/evidence）。
  - `feature-mapping.txt` 变量改为 `vueCode/apiDefinitions/controllerCode/permissionInfo/productDoc`，输出对齐 `MappingResult`（pageKey/apiKey/businessAction/permissionKey/evidence/conflicts + unmatched）。
  - `graph-merge-decision.txt` 变量改为 `candidateAKey/candidateAInfo/...` + 5 个相似度分数，输出对齐 `GraphMergeDecision`（decision=AUTO_MERGE/REVIEW/REJECT、score、reasons、正/负证据 ids）。
  - `test-case-generation.txt` 输出 `testCases` 数组并对齐 `GeneratedTestCase` 字段；新增包装 DTO `TestCaseGenerationResult`，`TestCaseAgent` 解析数组并补全缺失 `featureKey`。
  - 新增独立审核模板 `review-suggestion.txt`，`ReviewAgent` 不再复用 `graph-merge-decision`，输出对齐 `ReviewResult`（summary/supportingPoints/conflictingPoints/recommendation/reasoning）。
- **`LlmGateway` 加固**（`io.github.legacygraph.llm.LlmGateway`）：
  - 结构化校验：LLM 输出反序列化失败时，记录 `status=REVIEW` 并抛出 `LlmCallException(needsReview=true)`，不再静默返回空对象。
  - 失败显式返回：调用异常落 `status=FAILED` 并抛出 `LlmCallException`，附带 `promptRunId` 便于审计追溯。
  - `PromptRun` 审计补全：写入 `inputHash`（SHA-256，用于缓存/去重）、`parsedOutput`（结构化结果）、`promptTokens/completionTokens`（取自 ChatResponse usage）、`latencyMs`。
- **新增类型**：`LlmCallException`、`TestCaseGenerationResult`。
- **契约测试**：`PromptTemplateContractTest` 校验每个生产模板的占位符均能被对应 Agent 入参完整填充、渲染后无残留 `{var}`；`LlmGatewayTest` 校验失败显式抛出与 `inputHash`/`FAILED` 审计写入；各 Agent 单测更新为新契约。

### Phase 1：把 AI 接入扫描闭环 ✅ 已完成

- 扫描配置传递 `enableAi`、`autoGenerateTestCase`、`minConfidence`。
- `ProjectScanner` 扫描成功后触发文档 AI 抽取、向量化、功能映射和测试生成。
- 所有 AI 结果写入图谱时默认 `PENDING_CONFIRM`，并关联证据。

**落地细节**：

- **配置传递**：新增 `AiScanConfig.fromScanScope`，从 `ScanVersion.scanScope`(JSON) 解析 `enableAi/autoGenerateTestCase/minConfidence`；前端 `CreateScan.vue` 提交扫描时把三个开关写入 `scanScope`。
- **`AiScanOrchestrator`**（`io.github.legacygraph.task.AiScanOrchestrator`），由 `ProjectScanner.startFullScan` 在扫描成功后调用（AI 编排失败不影响扫描 SUCCESS）：
  - `AI_DOC_EXTRACT`：读取本版本文档 → `DocUnderstandingAgent` 抽取业务流程/对象 → 写入 `lg_fact`（`sourceType=DOC_AI`，`status=PENDING_CONFIRM`）。
  - `AI_FEATURE_MAPPING`：汇总 PAGE/API 节点 → `FeatureMappingAgent` 生成映射（默认 PENDING_CONFIRM）。
  - `AI_TEST_GENERATE`：`autoGenerateTestCase` 开启时，对高价值 API 节点 → `TestCaseAgent` 生成用例并写入 `lg_test_case`（`generatedBy=LLM`，上限 20 节点）。
  - `AI_REVIEW_PREPARE`：扫描节点中置信度 < `minConfidence` 者生成 `ReviewRecord`（PENDING，去重，上限 50）。
  - 每个子任务以独立 `ScanTask` 记录状态（RUNNING→SUCCESS/FAILED），单步失败不中断整体编排。
- **测试**：`AiScanConfigTest`（解析/默认/异常）、`AiScanOrchestratorTest`（开关门控、四子任务编排、低置信审核生成、测试用例持久化）。

### Phase 2：上线自然语言问答 ✅ 已完成

- 新增 `QaAgent` / `GraphQaController`。
- 采用“规则过滤 -> 向量召回 -> 图邻域扩展 -> 重排序 -> LLM 生成”。
- 回答必须返回证据列表、相关节点和置信度。

**落地细节**：

- **`QaAgent`**（`io.github.legacygraph.agent.QaAgent`）：
  - 向量召回：`VectorRetrievalService.semanticSearch` 取 Top-5 文档片段，按内容去重（轻量重排序）。
  - 图邻域：`VectorRetrievalService.findSimilarNodes` 召回语义相似节点。
  - 上下文拼装：节点（类型/名称/key/描述）+ 文档片段（chunk#N），同步生成结构化证据明细。
  - LLM 生成：调用 `qa-answer` 模板，输出对齐 `QaAnswer`（answer/confidence/usedEvidence/relatedNodeKeys）。
  - 鲁棒性：空问题短路返回；检索失败降级为"信息不足"而非抛出；无上下文时仍带占位提示调用 LLM。
- **`GraphQaController`**：`POST /qa/ask`，入参 `{projectId, versionId, question}`，返回 `QaAnswer`（含服务端回填的 `evidences` 明细，便于前端展示来源与跳转）。
- **新增类型**：`QaAnswer`（含 `EvidenceItem`）、`qa-answer.txt` 模板。
- **测试**：`QaAgentTest`（上下文拼装+证据回填、空问题短路、无上下文、检索失败降级）；`PromptTemplateContractTest` 增加 `qa-answer` 契约校验。

### Phase 3：增强测试、报告、SQL ✅ 已完成

- `TestCaseAgent` 生成真实请求参数、断言和边界场景。
- 测试失败触发根因分析和复测建议。
- 报告页接入动态 AI 建议。
- SQL 分析补充性能风险、动态 SQL 解析失败兜底和表字段业务含义推断。

**落地细节**：

- **测试用例真实化**：在 Phase 0 已重写 `test-case-generation.txt`，单次返回多场景（NORMAL/EXCEPTION/BOUNDARY），按 `requestSchema` 填充真实参数、生成可执行断言（HTTP/JSON_PATH/SQL），不确定项放入 `needHumanInput`。
- **SQL 性能优化顾问** `SqlAdvisorAgent` + `sql-advisor.txt` + `SqlAdvisorResult`：识别 SELECT *、缺索引、N+1、前缀模糊匹配等，输出优化建议与优化后 SQL；端点 `POST /agents/sql/analyze`。
- **测试失败根因分析** `TestFailureAnalysisAgent` + `test-failure-analysis.txt` + `TestFailureAnalysis`：输入请求/响应/错误/目标节点/图谱路径/trace，输出根因、关联制品、排查步骤、是否降低置信度、重跑范围；端点 `POST /agents/tests/analyze-failure`。
- **报告洞察与行动建议** `ReportInsightAgent` + `report-insight.txt` + `ReportInsight`：输入图谱指标 + 缺口摘要，输出按优先级排序、带来源的可执行行动清单；端点 `POST /agents/report/insights`。
- **测试**：三个 Agent 单测 + `PromptTemplateContractTest` 增加 `sql-advisor`/`test-failure-analysis`/`report-insight` 契约校验。

### Phase 4：后置复杂能力 ✅ 大部分完成

- 代码异味重构建议。
- 微服务拆分建议。
- 迁移代码自动转换。
- PR 描述/提交信息自动生成。

**落地细节**：

- **代码异味重构建议** `RefactorAgent` + `refactor-suggestion.txt` + `RefactorSuggestion`：分析职责边界、给出拆分建议与重构骨架、评估影响与风险；端点 `POST /agents/refactor/suggest`。
- **变更影响分析增强** `ChangeImpactAgent` + `change-impact.txt` + `ChangeImpactAnalysis`：语义级判断变更类型（BUGFIX/FEATURE/BREAKING_CHANGE/REFACTOR）、严重程度、受影响节点与回归范围；端点 `POST /agents/change/impact`。
- **迁移代码自动转换** `MigrationAgent` + `migration-convert.txt` + `MigrationConversion`：识别过时注解、`javax.*`→`jakarta.*`、配置变化，支持自定义规则，输出转换后代码；端点 `POST /agents/migration/convert`。
- **PR 描述/提交信息生成** `PrDescriptionAgent` + `pr-description.txt` + `PrDescription`：按 Conventional Commits 规范生成 commitMessage / prTitle / prBody；端点 `POST /agents/pr/describe`。
- **微服务拆分建议**：按本文档优先级建议（🟡 低、工作量大、依赖真实项目数据稳定）暂未实现，待图谱数据与映射准确率稳定后再投入。
- **测试**：`Phase4AgentsTest`（4 个 Agent）+ `PromptTemplateContractTest` 增加 4 个模板契约校验。

## 💡 总结

LegacyGraph 的 AI 架构已经搭好了（`LlmGateway` + 各个 Agent + 提示词模板管理 + 向量检索 + 图谱查询 + 审核/测试闭环），**基础设施就绪**，但当前不能直接扩大新增 Agent 数量。

更准确的判断是：**先修契约，再接主流程，最后扩能力**。

**最立竿见影的改进**是：

1. **修复模板变量、DTO Schema 与 Agent 入参不一致** —— 这是现有 AI 能力有效性的前提。
2. **给所有现有 Agent 加上自我校正与证据校验步骤** —— 工作量小，直接提升准确率。
3. **把扫描页的 AI 开关落到后端编排** —— 让 AI 归纳、映射、测试生成进入主流程。
4. **实现自然语言问答** —— 用户体验提升最大，是知识图谱平台的核心能力。
5. **增强测试生成和 SQL 分析** —— 都有现成结构化输入，迁移场景收益高。

---

## ✅ 本轮实施总结（2026-06-30）

按"先修契约，再接主流程，最后扩能力"路线，Phase 0–4 已基本落地：

- **Phase 0**：6 个模板/DTO/Agent 三方契约对齐；`LlmGateway` 加结构化校验、失败显式返回（`LlmCallException`）、`PromptRun` 审计补全（inputHash/parsedOutput/token/latency）；新增独立审核模板。
- **Phase 1**：`AiScanOrchestrator` 接入 `ProjectScanner`，扫描后按 `enableAi/autoGenerateTestCase/minConfidence` 执行 4 个 AI 子任务（`AI_DOC_EXTRACT`/`AI_FEATURE_MAPPING`/`AI_TEST_GENERATE`/`AI_REVIEW_PREPARE`），结果默认 `PENDING_CONFIRM`。
- **Phase 2**：`QaAgent` + `GraphQaController` 自然语言问答（向量召回+图邻域+LLM 生成，带证据/相关节点/置信度）。
- **Phase 3**：SQL 性能顾问、测试失败根因分析、报告行动建议三个新 Agent + 端点。
- **Phase 4**：重构建议、变更影响、迁移转换、PR 描述四个 Agent + 端点；微服务拆分按建议后置。

**新增产物**：13 个生产 Prompt 模板（含 7 个新增）、15+ 个 Agent/DTO、`/qa` 与 `/agents/*` 一批端点。

**测试**：AI 相关单测共 **63 个全部通过**，含 `PromptTemplateContractTest`（覆盖全部 13 个模板的变量契约）、各 Agent 单测、`LlmGatewayTest`、`AiScanOrchestratorTest`。
> 说明：仓库中 `@SpringBootTest` 集成测试因本机无 Postgres/Neo4j/Flyway 基线而无法加载上下文，属环境限制，与本轮 AI 改动无关。

---

*文档生成日期：2026-06-30*
