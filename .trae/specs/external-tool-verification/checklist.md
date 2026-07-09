# Checklist

## Phase 1: 核心框架搭建

- [x] ExternalVerificationAdapter 接口定义了 supports/verify/checkHealth/adapterName/priority 五个方法
- [x] VerificationResult 包含 confirmedEdges/missingEdges/suspiciousEdges/nodeProperties 四类验证产物
- [x] VerifiedEdge DTO 包含 fromNodeKey/toNodeKey/edgeType/confidence/sourceTool 字段
- [x] VerifiedNodeProperty DTO 包含 nodeKey/propertyName/propertyValue/sourceTool 字段
- [x] 所有新类在 `io.github.legacygraph.verification` 包下
- [x] ResultFusionEngine 对 confirmedEdges 更新边 confidence=1.0/status=CONFIRMED
- [x] ResultFusionEngine 对 missingEdges 通过 EvidenceGraphWriter.upsertEdge 写入（sourceType=EXTERNAL_VERIFY, confidence=0.85）
- [x] ResultFusionEngine 对 nodeProperties 通过 Neo4jGraphDao.setNodeProperty 写入（带来源前缀）
- [x] ResultFusionEngine 对 suspiciousEdges 通过 Neo4jGraphDao.setEdgeProperty 降级为 PENDING_CONFIRM
- [x] ExternalVerificationService 构造器注入 List<ExternalVerificationAdapter> 和 ResultFusionEngine
- [x] ExternalVerificationService 使用虚拟线程并行执行 verify()
- [x] ExternalVerificationService 实现超时控制（默认 60 秒）
- [x] ExternalVerificationService 单个适配器异常不影响其他适配器
- [x] ExternalVerificationService 无可用适配器时返回空结果不报错

## Phase 2: 扫描主流程集成

- [x] ProjectScanner 注入了 ExternalVerificationService
- [x] ProjectScanner 注入了配置项 legacygraph.external-verification.enabled
- [x] EXTERNAL_VERIFY 阶段位于 MEMBER_CALL_RESOLVE 之后、DATABASE_SCAN 之前
- [x] EXTERNAL_VERIFY 阶段创建 taskType="EXTERNAL_VERIFY" 的 ScanTask
- [x] 验证失败时标记 ScanTask 失败但不中断后续步骤
- [x] enabled=false 时跳过整个阶段（向后兼容）

## Phase 3: MCP 验证适配器

- [x] McpVerificationAdapter 实现 ExternalVerificationAdapter 接口
- [x] McpVerificationAdapter 注入 McpClientFacade 和 Neo4jGraphDao
- [x] checkHealth() 调用 McpClientFacade.indexStatus()，异常时返回 false
- [x] CALLS 边验证用 MCP queryGraph
- [x] 缺失边补全用 MCP queryGraph 查询外部 CALLS 关系，对比本地缺失的调用（已修复 toNodeKey 为 null 的问题）
- [x] 继承边补全用 MCP searchGraph 查找未解析的 EXTENDS/IMPLEMENTS
- [x] MCP 不可达时 checkHealth() 返回 false，不抛异常

## Phase 4: 审计指标扩展

- [x] GraphCompletenessAuditService.audit() 返回 8 项指标（原 7 项 + 外部验证覆盖率）
- [x] 外部验证覆盖率 = sourceType=EXTERNAL_VERIFY 且 status=CONFIRMED 的边数 / 总边数
- [x] 无 EXTERNAL_VERIFY 边时指标值为 0，detail 标注 "未启用外部验证"
- [x] metrics 列表容量从 7 扩展为 8

## 编译与集成

- [x] `mvn compile` 编译通过
- [x] 默认配置（enabled=false）下扫描流程行为不变
- [x] 无 MCP 服务时应用正常启动（@Autowired(required=false) + McpClientFacadeConfiguration 默认降级）
