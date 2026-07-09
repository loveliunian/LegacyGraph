# Tasks

## Phase 1: 核心框架搭建

- [x] Task 1: 创建 ExternalVerificationAdapter 接口和验证数据模型
  - [ ] SubTask 1.1: 创建 `ExternalVerificationAdapter` 接口（方法：supports/verify/checkHealth/adapterName/priority）
  - [ ] SubTask 1.2: 创建 `VerificationResult` 数据模型（confirmedEdges/missingEdges/suspiciousEdges/nodeProperties/adapterName/totalChecked/totalConfirmed）
  - [ ] SubTask 1.3: 创建 `VerifiedEdge` DTO（fromNodeKey/toNodeKey/edgeType/confidence/sourceTool）
  - [ ] SubTask 1.4: 创建 `VerifiedNodeProperty` DTO（nodeKey/propertyName/propertyValue/sourceTool）
  - **验证**: 编译通过，所有类在 `io.github.legacygraph.verification` 包下

- [x] Task 2: 创建 ResultFusionEngine 融合引擎
  - [ ] SubTask 2.1: 创建 `ResultFusionEngine` 服务类，注入 Neo4jGraphDao 和 EvidenceGraphWriter
  - [ ] SubTask 2.2: 实现 `fuse()` 方法——遍历 VerificationResult，对 confirmedEdges 更新边 confidence=1.0/status=CONFIRMED
  - [ ] SubTask 2.3: 实现 missingEdges 写入——通过 EvidenceGraphWriter.upsertEdge 写入新边（sourceType=EXTERNAL_VERIFY, confidence=0.85, status=PENDING_CONFIRM）
  - [ ] SubTask 2.4: 实现 nodeProperties 写入——通过 Neo4jGraphDao.setNodeProperty 写入属性（前缀来源工具名）
  - [ ] SubTask 2.5: 实现 suspiciousEdges 标记——通过 Neo4jGraphDao.setEdgeProperty 将 status 降级为 PENDING_CONFIRM
  - **验证**: 编译通过，单元测试覆盖四种融合场景

- [x] Task 3: 创建 ExternalVerificationService 编排服务
  - [ ] SubTask 3.1: 创建 `ExternalVerificationService` 服务类，构造器注入 `List<ExternalVerificationAdapter>` 和 `ResultFusionEngine`
  - [ ] SubTask 3.2: 实现健康检查——遍历所有适配器调用 checkHealth()，过滤不健康的
  - [ ] SubTask 3.3: 实现并行验证——使用虚拟线程 `Executors.newVirtualThreadPerTaskExecutor()` 并行执行 verify()
  - [ ] SubTask 3.4: 实现超时控制——每个适配器 verify() 限时 60 秒（可配置），超时中断
  - [ ] SubTask 3.5: 实现结果收集与融合——收集所有 VerificationResult，调用 ResultFusionEngine.fuse()
  - [ ] SubTask 3.6: 实现异常隔离——单个适配器异常不影响其他适配器
  - **验证**: 编译通过，无可用适配器时返回空结果不报错

## Phase 2: 扫描主流程集成

- [x] Task 4: 在 ProjectScanner 中新增 EXTERNAL_VERIFY 阶段
  - [ ] SubTask 4.1: 在 `ProjectScanner` 中注入 `ExternalVerificationService` 和配置项 `legacygraph.external-verification.enabled`
  - [ ] SubTask 4.2: 在 `runScanBody()` 中 MEMBER_CALL_RESOLVE 之后、DATABASE_SCAN 之前插入 EXTERNAL_VERIFY 阶段
  - [ ] SubTask 4.3: 创建 ScanTask（taskType="EXTERNAL_VERIFY"），调用 ExternalVerificationService
  - [ ] SubTask 4.4: 异常处理——验证失败时标记 ScanTask 失败但不中断后续步骤
  - [ ] SubTask 4.5: 配置门控——`enabled=false` 时跳过整个阶段
  - **验证**: 编译通过，默认配置下扫描流程行为不变

## Phase 3: MCP 验证适配器

- [x] Task 5: 创建 McpVerificationAdapter
  - [ ] SubTask 5.1: 创建 `McpVerificationAdapter` 类，实现 `ExternalVerificationAdapter`，注入 `McpClientFacade` 和 `Neo4jGraphDao`
  - [ ] SubTask 5.2: 实现 `checkHealth()` — 调用 McpClientFacade.indexStatus()，捕获异常返回 false
  - [ ] SubTask 5.3: 实现 CALLS 边验证 — 查询本地 CALLS 边，对每条边用 MCP queryGraph 验证
  - [ ] SubTask 5.4: 实现缺失边补全 — 查询 targetClass 为空的 SERVICE_CALL，用 MCP searchGraph 查找目标，生成 VerifiedEdge
  - [ ] SubTask 5.5: 实现继承边补全 — 查询未解析的 EXTENDS/IMPLEMENTS，用 MCP searchGraph 查找父类，生成 VerifiedEdge
  - **验证**: 编译通过，MCP 不可达时 checkHealth() 返回 false

## Phase 4: 审计指标扩展

- [x] Task 6: 扩展 GraphCompletenessAuditService 新增第 8 项指标
  - [ ] SubTask 6.1: 在 `audit()` 方法中新增 `externalVerificationCoverage` 指标方法
  - [ ] SubTask 6.2: 实现 — 查询 sourceType=EXTERNAL_VERIFY 且 status=CONFIRMED 的边数 / 总边数
  - [ ] SubTask 6.3: 无 EXTERNAL_VERIFY 边时返回 0 并标注 "未启用外部验证"
  - [ ] SubTask 6.4: 将 metrics 列表容量从 7 扩展为 8
  - **验证**: 编译通过，审计结果包含 8 项指标

# Task Dependencies
- [Task 2] depends on [Task 1] — 融合引擎依赖验证数据模型
- [Task 3] depends on [Task 1] and [Task 2] — 编排服务依赖适配器接口和融合引擎
- [Task 4] depends on [Task 3] — 扫描阶段依赖编排服务
- [Task 5] depends on [Task 1] — MCP 适配器依赖验证接口
- [Task 6] depends on [Task 3] — 审计指标依赖验证边写入（但可独立编译）
- [Task 5] 可与 [Task 4] 并行
- [Task 6] 可与 [Task 4] 和 [Task 5] 并行
