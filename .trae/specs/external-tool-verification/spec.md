# 外部工具对照校验与图谱增强 Spec

## Why

当前 LegacyGraph 的扫描主流程完全依赖本地抽取器（ExtractionAdapter）生成图谱节点和边，没有"用外部工具验证本地结果"或"用外部结果补充本地盲区"的闭环。导致已发现的缺口（如 CALLS=0、跨文件引用解析率低）只能靠人工代码修复。

已有 `McpCodeGraphAdapter` 完整实现了 MCP 协议调用（searchGraph/traceCall/readSnippet），但调用路径被限制在 `AI_CODE_UNDERSTANDING` 增强步骤（order=8），且默认关闭（`enhancementEnabled=false` + `enableAi=false` 双重门控），不参与图谱构建本身。

需要将外部工具从"增强侧枝"改造为"扫描验证节点"，在扫描主流程中增加对照校验点。

## What Changes

- 新增 `ExternalVerificationAdapter` 接口，定义"验图"契约（区别于 ExtractionAdapter 的"建图"契约）
- 新增 `VerificationResult` 数据模型，包含确认边、缺失边、可疑边、节点属性四类验证产物
- 新增 `ResultFusionEngine` 融合引擎，对本地结果与外部验证结果做比对、去重、置信度加权
- 新增 `ExternalVerificationService` 编排服务，在扫描主流程中协调多个验证适配器
- 在 `ProjectScanner.runScanBody()` 中新增 `EXTERNAL_VERIFY` 阶段（位于 `MEMBER_CALL_RESOLVE` 之后、`DATABASE_SCAN` 之前）
- 新增 `McpVerificationAdapter`，复用已有 `McpClientFacade`，实现 `ExternalVerificationAdapter`
- 扩展 `GraphCompletenessAuditService`，新增第 8 项指标：外部验证覆盖率
- 新增配置项 `legacygraph.external-verification.enabled`（默认 false，向后兼容）

## Impact

- Affected specs: 图谱完整性审计、扫描主流程编排
- Affected code:
  - `ProjectScanner.java` — 新增 EXTERNAL_VERIFY 阶段
  - `GraphCompletenessAuditService.java` — 新增第 8 项指标
  - `McpClientFacade.java` — 复用，不修改
  - `EvidenceGraphWriter.java` — 复用 upsertEdge/upsertNode，不修改
  - `ToolRegistry.java` — 复用，不修改

## ADDED Requirements

### Requirement: ExternalVerificationAdapter 接口

系统 SHALL 提供一个 `ExternalVerificationAdapter` 接口，用于在本地抽取完成后用外部工具对照校验图谱结果。

接口定义：
- `boolean supports(ScanContext context)` — 是否支持验证此扫描上下文
- `VerificationResult verify(String projectId, String versionId, ScanContext context)` — 执行验证
- `boolean checkHealth()` — 外部工具健康检查
- `String adapterName()` — 适配器名称
- `int priority()` — 优先级（数值越小越优先）

#### Scenario: 外部工具不可达时降级
- **WHEN** checkHealth() 返回 false
- **THEN** 跳过该适配器的验证，不阻塞扫描流程
- **AND** 在扫描日志中记录 WARN 级别信息

#### Scenario: 验证超时
- **WHEN** verify() 执行超过配置的超时时间（默认 60 秒）
- **THEN** 中断验证，记录超时日志，继续扫描后续步骤

### Requirement: VerificationResult 数据模型

系统 SHALL 提供一个 `VerificationResult` 数据模型，包含四类验证产物：

- `List<VerifiedEdge> confirmedEdges` — 外部工具确认存在的边（验证本地结果正确）
- `List<VerifiedEdge> missingEdges` — 外部工具发现但本地缺失的边（补漏）
- `List<VerifiedEdge> suspiciousEdges` — 本地存在但外部工具未发现的边（可能误报）
- `List<VerifiedNodeProperty> nodeProperties` — 外部工具独有的节点属性（如复杂度）
- `String adapterName` — 来源适配器名称
- `int totalChecked` — 总检查数
- `int totalConfirmed` — 确认数

`VerifiedEdge` 字段：
- `String fromNodeKey` — 源节点 key
- `String toNodeKey` — 目标节点 key
- `String edgeType` — 边类型（CALLS/EXTENDS/IMPLEMENTS 等）
- `Double confidence` — 置信度（0.0~1.0）
- `String sourceTool` — 来源工具名

`VerifiedNodeProperty` 字段：
- `String nodeKey` — 节点 key
- `String propertyName` — 属性名（如 "complexity"）
- `Object propertyValue` — 属性值
- `String sourceTool` — 来源工具名

### Requirement: ResultFusionEngine 融合引擎

系统 SHALL 提供一个 `ResultFusionEngine` 服务，融合本地图谱与外部验证结果。

融合策略：
1. 双方都确认的边 → confidence=1.0, status=CONFIRMED
2. 本地有但外部无 → confidence 保持原值, status=PENDING_CONFIRM
3. 外部有但本地无 → 通过 EvidenceGraphWriter 写入新边, confidence=0.85, status=PENDING_CONFIRM, sourceType=EXTERNAL_VERIFY
4. 双方冲突 → 按工具优先级仲裁

#### Scenario: 外部工具补充缺失的 CALLS 边
- **WHEN** 外部工具发现一条本地未生成的 CALLS 边
- **THEN** 融合引擎通过 EvidenceGraphWriter.upsertEdge 写入该边
- **AND** 边的 sourceType 设为 "EXTERNAL_VERIFY"
- **AND** 边的 confidence 设为 0.85
- **AND** 边的 status 设为 PENDING_CONFIRM

#### Scenario: 外部工具确认本地边
- **WHEN** 外部工具确认本地已存在的边
- **THEN** 融合引擎通过 Neo4jGraphDao.setEdgeProperty 更新该边 confidence 为 1.0
- **AND** 边的 status 更新为 CONFIRMED

#### Scenario: 节点属性增强
- **WHEN** 外部工具提供了节点属性（如复杂度）
- **THEN** 融合引擎通过 Neo4jGraphDao.setNodeProperty 写入属性
- **AND** 属性名前缀为来源工具名（如 "mcp.confidence"）

### Requirement: ExternalVerificationService 编排服务

系统 SHALL 提供一个 `ExternalVerificationService` 服务，编排多个验证适配器的执行。

职责：
1. 收集所有 `ExternalVerificationAdapter` Spring Bean
2. 按 priority 排序
3. 并行执行所有健康的适配器
4. 收集所有 VerificationResult
5. 调用 ResultFusionEngine 融合结果

#### Scenario: 多适配器并行验证
- **WHEN** 有 2 个以上健康的验证适配器
- **THEN** 使用虚拟线程并行执行验证
- **AND** 每个适配器独立超时控制
- **AND** 任一适配器失败不影响其他适配器

#### Scenario: 无可用适配器
- **WHEN** 所有适配器 checkHealth() 都返回 false
- **THEN** 跳过 EXTERNAL_VERIFY 阶段
- **AND** 在扫描日志中记录 INFO 级别信息
- **AND** 不影响后续扫描步骤

### Requirement: 扫描主流程新增 EXTERNAL_VERIFY 阶段

系统 SHALL 在 `ProjectScanner.runScanBody()` 中新增 `EXTERNAL_VERIFY` 阶段，位于 `MEMBER_CALL_RESOLVE` 之后、`DATABASE_SCAN` 之前。

#### Scenario: 默认关闭
- **WHEN** `legacygraph.external-verification.enabled=false`（默认值）
- **THEN** 跳过 EXTERNAL_VERIFY 阶段
- **AND** 扫描流程与当前行为完全一致（向后兼容）

#### Scenario: 开启验证
- **WHEN** `legacygraph.external-verification.enabled=true`
- **AND** 至少有一个健康的验证适配器
- **THEN** 创建 ScanTask（taskType="EXTERNAL_VERIFY"）
- **AND** 调用 ExternalVerificationService 执行验证
- **AND** 验证结果通过 ResultFusionEngine 融合写入图谱
- **AND** 更新 ScanTask 状态为完成

#### Scenario: 验证失败不阻塞扫描
- **WHEN** EXTERNAL_VERIFY 阶段抛出异常
- **THEN** 记录错误日志
- **AND** 标记 ScanTask 为失败
- **AND** 继续执行 DATABASE_SCAN 等后续步骤

### Requirement: McpVerificationAdapter

系统 SHALL 提供一个 `McpVerificationAdapter`，实现 `ExternalVerificationAdapter` 接口，复用已有 `McpClientFacade`。

验证逻辑：
1. **CALLS 边验证**：对本地已生成的 CALLS 边，用 MCP `queryGraph` 验证
2. **缺失边补全**：对本地解析失败的 SERVICE_CALL（targetClass 为空），用 MCP `searchGraph` 查找目标
3. **继承边补全**：对本地 EXTENDS/IMPLEMENTS 未解析的父类，用 MCP `searchGraph` 查找

#### Scenario: MCP 服务不可达
- **WHEN** McpClientFacade 抛出 IllegalStateException（未配置）
- **THEN** checkHealth() 返回 false
- **AND** 跳过 MCP 验证

#### Scenario: 补全缺失的调用目标
- **WHEN** 本地有一条 SERVICE_CALL fact 的 targetClass 为空
- **AND** MCP searchGraph 返回了匹配的符号
- **THEN** 生成 VerifiedEdge（edgeType=CALLS，confidence=0.85）
- **AND** 加入 missingEdges 列表

### Requirement: GraphCompletenessAuditService 新增外部验证覆盖率指标

系统 SHALL 在 `GraphCompletenessAuditService.audit()` 中新增第 8 项指标：外部验证覆盖率。

指标定义：
- 名称：外部验证覆盖率
- 计算方式：`confirmedEdges / totalEdges`（被外部工具确认的边数 / 总边数）
- 目标：≥30%
- 前提：仅当存在 status=CONFIRMED 且 sourceType=EXTERNAL_VERIFY 的边时才计算，否则指标值为 0 并标注"未启用外部验证"

#### Scenario: 外部验证未启用
- **WHEN** 图谱中无 sourceType=EXTERNAL_VERIFY 的边
- **THEN** 指标值为 0
- **AND** detail 字段标注 "未启用外部验证"

#### Scenario: 外部验证已启用
- **WHEN** 图谱中有 sourceType=EXTERNAL_VERIFY 的边
- **THEN** 计算被确认的边占总边的比例
- **AND** 与目标 ≥30% 比较，返回 isPassed() 结果
