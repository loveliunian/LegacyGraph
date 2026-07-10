# QA 高级问答能力扩展 Spec（K~O 五类）

## Why

在 qa-enhancement-plan（A-J 十大类）基础上，对标市面产品（Sourcegraph Cody、Deepwiki、CodeGraph、Google security-hardening skill、Diffblue Cover），发现 LegacyGraph QA 问答模块在技术债嗅探、安全审计、事务并发、动态可视化、精准测试生成五个方向存在能力缺失。本 Spec 补齐这五个方向，使 QA 问答从十大类扩展到十五大类（A~O），覆盖代码维护、安全、并发、可视化、测试全生命周期。

## What Changes

### K 类：技术债嗅探（P7-35 ~ P7-41）
- 新增 `TECH_DEBT` 意图枚举
- 新增 `Neo4jGraphDao.detectCircularDependencies` 图算法检测环
- 新增扇入扇出统计与过大类识别（lineCount/methodCount/complexity 写入 Class 节点 properties）
- 新增 `ArchitectureViolationScanner` 架构违规扫描与死代码识别
- 新增 `tech-debt-report` prompt 模板
- 新增 QueryRewriter 技术债多维拆解策略
- EnhancedQaAgent 新增 TECH_DEBT 处理链路

### L 类：安全审计问答（P8-42 ~ P8-48）
- 新增 `SECURITY_AUDIT` 意图枚举
- 新增 `SecurityExtractor` 安全扫描器（SQL 注入/硬编码密钥/反序列化/XSS/路径遍历）
- 新增 `NodeType.SecurityRisk` 节点类型 + `EdgeType.HAS_RISK` 边类型
- 新增敏感数据标记与脱敏追踪（Column.sensitive + `EdgeType.MASKED_AT` 边）
- 新增权限校验缺失检测（`findUnprotectedApiEndpoints`）
- 新增 `security-audit-report` prompt 模板
- EnhancedQaAgent 新增 SECURITY_AUDIT 处理链路

### M 类：事务与并发分析（P9-49 ~ P9-53）
- 新增 `CONCURRENCY` 意图枚举
- 新增 `ConcurrencyExtractor` 事务/并发扫描器（传播行为/隔离级别/@Async/synchronized/锁）
- 新增 `NodeType.TransactionScope` 节点 + `EdgeType.BOUND_BY` 边
- 检测 self-invocation 导致 @Transactional 失效风险
- 新增 `concurrency-analysis` prompt 模板
- EnhancedQaAgent 新增 CONCURRENCY 处理链路

### N 类：动态可视化（P10-54 ~ P10-58）
- 新增 `VISUALIZATION` 意图枚举
- 新增 `DiagramGenerator` 服务：调用链→时序图、依赖图、调用链图、数据流图、业务链路图转换
- 新增 `visualization` prompt 模板（输出 mermaid 代码块）
- 新增 QueryRewriter 可视化拆图策略
- EnhancedQaAgent 新增 VISUALIZATION 处理链路

### O 类：精准测试生成（P11-59 ~ P11-63）
- 新增 `TEST_GENERATION` 意图枚举
- 新增覆盖率缺口分析（`findUncoveredMethods`）
- 新增 `TestGenerationAgent` 测试代码生成（JUnit + Mockito 模板）
- 新增 `test-generation` prompt 模板
- EnhancedQaAgent 新增 TEST_GENERATION 处理链路

## Impact
- Affected specs: qa-enhancement-plan（A-J 十大类基础能力，本 Spec 在其基础上扩展）
- Affected code:
  - `agent/QueryIntent.java` — 新增 5 个意图枚举
  - `agent/EnhancedQaAgent.java` — 新增 5 个意图处理链路
  - `common/NodeType.java` — 新增 SecurityRisk、TransactionScope
  - `common/EdgeType.java` — 新增 HAS_RISK、MASKED_AT、BOUND_BY
  - `dao/Neo4jGraphDao.java` — 新增 detectCircularDependencies、computeFanInOut、findIsolatedNodes、findUnprotectedApiEndpoints、findUncoveredMethods
  - `extractors/` — 新建 SecurityExtractor、ConcurrencyExtractor 及对应 Adapter
  - `builder/GraphBuilder.java` — 新增 buildSecurityGraph、buildConcurrencyGraph
  - `service/scan/ArchitectureViolationScanner.java`（新建）
  - `service/viz/DiagramGenerator.java`（新建）
  - `agent/TestGenerationAgent.java`（新建）
  - `prompts/` — 新建 5 个 prompt 模板
  - `db/migration/` — 新增种子数据（5 个 prompt 模板）

## ADDED Requirements

### Requirement: TECH_DEBT 意图与技术债反查
系统 SHALL 提供 TECH_DEBT 意图，识别技术债类问题（循环依赖/过大类/架构违规/死代码/高耦合），并通过图算法和图谱反查输出结构化技术债报告。

#### Scenario: 查询循环依赖
- **WHEN** 用户问"系统有哪些循环依赖"
- **THEN** 识别为 TECH_DEBT 意图，调用 detectCircularDependencies 检测 DEPENDS_ON/CALLS 边的环，输出环路径列表

#### Scenario: 查询过大类
- **WHEN** 用户问"哪些类过大该拆"
- **THEN** 列出 lineCount/methodCount 超阈值的 Class 节点，附规模指标

#### Scenario: 查询架构违规
- **WHEN** 用户问"Controller 有没有直接调 DAO"
- **THEN** ArchitectureViolationScanner 列出 Controller→Mapper 跳层 CALLS 边

#### Scenario: 查询死代码
- **WHEN** 用户问"哪些方法是死代码"
- **THEN** 列出 fanIn=0 且非入口节点（非 ApiEndpoint/Page）的 Method 节点

### Requirement: SECURITY_AUDIT 意图与安全扫描
系统 SHALL 提供 SECURITY_AUDIT 意图，识别安全审计类问题，通过 SecurityExtractor 扫描 SQL 注入/硬编码密钥/反序列化等风险，并追踪敏感数据脱敏链路。

#### Scenario: 查询 SQL 注入风险
- **WHEN** 用户问"XX 接口有没有 SQL 注入风险"
- **THEN** 识别为 SECURITY_AUDIT，列出 riskType=SQL_INJECTION 的 SecurityRisk 节点

#### Scenario: 查询硬编码密钥
- **WHEN** 用户问"哪些地方硬编码了密钥"
- **THEN** 列出 riskType=HARDCODED_SECRET 的 SecurityRisk 节点

#### Scenario: 查询敏感数据脱敏
- **WHEN** 用户问"身份证号在哪里脱敏"
- **THEN** 反查 Column --MASKED_AT--> Method 边

#### Scenario: 查询权限校验缺失
- **WHEN** 用户问"哪些接口缺少权限校验"
- **THEN** 列出无 REQUIRES_PERMISSION 边的 ApiEndpoint 节点

### Requirement: CONCURRENCY 意图与事务并发分析
系统 SHALL 提供 CONCURRENCY 意图，识别事务/并发类问题，通过 ConcurrencyExtractor 分析事务传播行为/隔离级别/@Async/锁，并检测 self-invocation 失效风险。

#### Scenario: 查询事务边界
- **WHEN** 用户问"XX 方法的事务边界是什么"
- **THEN** 输出 propagation/isolation 属性和同事务内的方法列表

#### Scenario: 查询事务跨数
- **WHEN** 用户问"这个操作跨几个事务"
- **THEN** 通过 TransactionScope 节点聚合统计

#### Scenario: 检测 self-invocation 风险
- **WHEN** 用户问"有没有 self-invocation 导致事务失效"
- **THEN** 列出 txFailureRisk=true 的 Method 节点

### Requirement: VISUALIZATION 意图与动态图表生成
系统 SHALL 提供 VISUALIZATION 意图，识别可视化类问题，通过 DiagramGenerator 从图谱生成 mermaid 时序图/依赖图/调用链图/数据流图/业务链路图。

#### Scenario: 生成时序图
- **WHEN** 用户问"画一下下单流程的时序图"
- **THEN** 从 CALLS 边生成 mermaid sequenceDiagram 代码块

#### Scenario: 生成依赖图
- **WHEN** 用户问"画一下订单模块的依赖图"
- **THEN** 从 DEPENDS_ON 边生成 mermaid graph LR 代码块

#### Scenario: 生成调用链图
- **WHEN** 用户问"画一下 orderService.create 的调用链"
- **THEN** 从 CALLS 边生成 mermaid graph TD 代码块

### Requirement: TEST_GENERATION 意图与测试代码生成
系统 SHALL 提供 TEST_GENERATION 意图，识别测试生成类问题，通过 TestGenerationAgent 结合图谱上下文（Method 签名/CALLS 依赖/ApiEndpoint 契约）生成 JUnit 测试代码。

#### Scenario: 生成单元测试
- **WHEN** 用户问"帮 orderService.create 方法生成单元测试"
- **THEN** 输出完整 JUnit 测试类代码，含多个测试场景

#### Scenario: 查询覆盖率缺口
- **WHEN** 用户问"哪些方法没有测试覆盖"
- **THEN** 列出无 VERIFIED_BY 边的 Method 节点，按 fanIn 排序

#### Scenario: 生成回归测试
- **WHEN** 用户问"改了 XX 方法，帮我生成回归测试"
- **THEN** 结合变更影响分析生成测试用例

## MODIFIED Requirements

### Requirement: QueryIntent 枚举
QueryIntent 枚举从 12 种扩展到 17 种，新增 TECH_DEBT、SECURITY_AUDIT、CONCURRENCY、VISUALIZATION、TEST_GENERATION。每个新意图需实现 requiresXxx() 方法，且与现有 requiresPlanner()/requiresChangeImpact()/requiresImplementationPlan() 互斥。

### Requirement: EnhancedQaAgent answerStream
answerStream 新增 5 个意图处理分支，每个分支包含：意图判定 → 图谱反查 → 上下文拼装 → LLM 流式生成。新增分支通过 isDedicatedIntent 互斥保护，避免与已有分支冲突。

### Requirement: detectListingNodeType
detectListingNodeType 补齐对新节点类型词的识别：SecurityRisk、TransactionScope，以及已补齐的 Exception/LogPoint/Package/ConfigItem/User/RequiredDocument（已在 G/H 类补齐中完成）。
