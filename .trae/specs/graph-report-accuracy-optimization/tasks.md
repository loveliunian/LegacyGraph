# Tasks

## 阶段一：P0-2 禁止大文档静默丢内容（无依赖，风险最低）

- [x] Task 1: 删除 DocExtractStep.readDocContent() 中的 100KB→50KB 前置截断逻辑，改为返回完整内容由 extractFromChunks() 全量分块处理
  - [x] SubTask 1.1: 移除 readDocContent() 中 byteLen > 100*1024 的截断分支（L492-L505），保留文件读取和空内容检测
  - [x] SubTask 1.2: 调整 DOC_CHUNK_THRESHOLD 或确保大文档走 extractFromChunks() 路径，增加分块数上限告警（>200 块时 warn）
  - [x] SubTask 1.3: 验证 extractFromChunks() 的 isMemoryHealthy() 内存保护逻辑能应对超长文档

- [x] Task 2: 修复 mergeByKey() 置信度取值逻辑（注释声称取最高，实际保留首个）
  - [x] SubTask 2.1: 在 mergeByKey() 的 else 分支增加置信度比较，通过反射获取 confidence 字段，保留较高置信度项
  - [x] SubTask 2.2: 增加同名项的 evidence 列表合并逻辑（多来源证据累加）

- [x] Task 3: splitContent() 返回带元数据的 chunk，写入 Evidence.metadata
  - [x] SubTask 3.1: 新建 DocChunk record（content, chunkIndex, charStart, charEnd, sectionTitle, pageNumber），修改 splitContent() 返回 List<DocChunk>
  - [x] SubTask 3.2: 修改 extractFromChunks() 使用 DocChunk.content 作为输入，将 chunkIndex/charStart/charEnd 传入 extractBusinessFacts() 或 Evidence 创建路径
  - [x] SubTask 3.3: 确保 chunk 元数据最终写入 Evidence 的 metadata JSONB 字段

- [x] Task 4: 增加 .doc 格式检测和降级提示
  - [x] SubTask 4.1: 在 DocumentContentService 中检测 .doc（非 .docx）格式，解析失败时设置明确的 errorMessage 而非静默

- [x] Task 5: 为 P0-2 编写单元测试
  - [x] SubTask 5.1: 测试 150KB 文档末尾内容进入抽取结果（mock LLM 验证 chunk 覆盖末尾）
  - [x] SubTask 5.2: 测试 mergeByKey 同名项保留较高置信度
  - [x] SubTask 5.3: 测试 splitContent 返回的 chunk 含 chunkIndex/charStart/charEnd

## 阶段一：P0-3 停止补造事实（独立，改动集中）

- [x] Task 6: 修改 SystemOverviewIngestService.toClaims() 将推断关系降级为 AI_INFERENCE + confidence 0.6
  - [x] SubTask 6.1: 新增 isTableAccessInferred(RelationRow) 判断方法，识别未经 AST/SQL 证明的 READS/WRITES 推断
  - [x] SubTask 6.2: 对推断的 READS/WRITES 设置 sourceType="AI_INFERENCE"，confidence=0.6
  - [x] SubTask 6.3: 新增 isDomainInferred(RelationRow) 判断方法，识别 deriveDomain() 推断的业务域
  - [x] SubTask 6.4: 对推断的业务域 CONTAINS 关系设置 sourceType="AI_INFERENCE"，confidence=0.6
  - [x] SubTask 6.5: 在推断 Claim 的 lineage 字段记录推导规则 JSON（rule, description, originalConfidence）

- [x] Task 7: 验证 KnowledgeClaimService.computeStatus() 对 AI_INFERENCE 类型始终返回 PENDING_CONFIRM
  - [x] SubTask 7.1: 确认 isAiSource("AI_INFERENCE") 返回 true（EvidenceGraphWriter 已包含）
  - [x] SubTask 7.2: 确认 computeStatus() 中 AI 来源 + confidence < 0.85 返回 PENDING_CONFIRM

- [x] Task 8: 为 P0-3 编写单元测试
  - [x] SubTask 8.1: 测试推断的 WRITES 边状态为 PENDING_CONFIRM 而非 CONFIRMED
  - [x] SubTask 8.2: 测试推断的业务域 confidence < 0.85
  - [x] SubTask 8.3: 测试推断 Claim 的 lineage 字段含推导规则

## 阶段一：P0-1 修复证据链断裂（依赖 P0-2 的分块元数据）

- [x] Task 9: 修改 DocUnderstandingAgent.toClaimDrafts() 传入 evidenceRefs 创建真实 Evidence
  - [x] SubTask 9.1: 修改 draft() 方法签名，新增 List<EvidenceRef> evidenceRefs 和 String contentExcerpt 参数
  - [x] SubTask 9.2: 在 draft() 中为每个 EvidenceRef 创建 Evidence 记录（通过 transient 字段传递到 KnowledgeClaimService），获取真实 UUID
  - [x] SubTask 9.3: 用真实 Evidence UUID 列表填充 KnowledgeClaimDraft.evidenceIds（替代当前的 List.of(sourcePath)）
  - [x] SubTask 9.4: 在 Evidence.metadata 中写入 extractor、model、promptVersion、contentHash、chunkId 等溯源信息
  - [x] SubTask 9.5: 更新 toClaimDrafts() 中所有 draft() 调用点，传入对应类型的 evidence 列表

- [x] Task 10: 修改 EvidenceGraphWriter.upsertNode() 移除 created() 限制，追加多来源证据
  - [x] SubTask 10.1: 将 upsertNode() 中 `if (upsert.created() && ...)` 改为 `if (hasText(claim.getSourcePath()) || isAiSource(claim.getSourceType()))`
  - [x] SubTask 10.2: createEvidenceForNode() 中将 insert 改为 insertOrIgnore 防止重复关联
  - [x] SubTask 10.3: 确认 lg_node_evidence 表有 (node_id, evidence_id) 唯一约束（V1 和 schema-h2.sql 均已存在）

- [x] Task 11: 在 KnowledgeClaimService.upsertDrafts() 中创建真实 Evidence 并填充 evidenceIds
  - [x] SubTask 11.1: 新增 materializeEvidence() 方法读取 transientEvidenceRefs 创建 Evidence 记录
  - [x] SubTask 11.2: evidenceIds 填充真实 UUID（supporting/contradictingClaimIds 暂跳过）

- [x] Task 12: 为 P0-1 编写单元测试
  - [x] SubTask 12.1: 测试 Claim.evidenceIds 为真实 UUID 而非文件路径
  - [x] SubTask 12.2: 测试同一节点被多文档提及时 lg_node_evidence 存在多条关联
  - [x] SubTask 12.3: 测试通过节点 ID 可查到全部关联 Evidence 含 contentExcerpt/sourcePath/chunkId

## 阶段一：P0-5 重做准确率指标（最好在 P0-1 后做，证据覆盖率需要证据链完整）

- [x] Task 13: 新增 Neo4jGraphDao 查询方法
  - [x] SubTask 13.1: countDanglingEdges(projectId, versionId) — 查询 from/to 节点不存在的边数
  - [x] SubTask 13.2: countDuplicateNodes(projectId, versionId) — 按 (projectId, versionId, nodeType, nodeKey) 分组计数 >1 的
  - [x] SubTask 13.3: sampleEdgesWithEvidence(projectId, versionId, sampleSize) — 抽样边并携带关联证据信息

- [x] Task 14: 新增 Repository 统计方法
  - [x] SubTask 14.1: NodeEvidenceRepository.countDistinctNodeIds(projectId, versionId)
  - [x] SubTask 14.2: EdgeEvidenceRepository.countDistinctEdgeIds(projectId, versionId)
  - [x] SubTask 14.3: KnowledgeClaimService.countByStatus(projectId, versionId, sourceType, status)

- [x] Task 15: 重写 GraphQualityAssessor.assessAccuracy() 为 4 个独立指标
  - [x] SubTask 15.1: 新建 metric record 类：StructuralIntegrityMetric、TripleAccuracyMetric、EvidenceCoverageMetric、CalibrationMetric
  - [x] SubTask 15.2: 实现 assessStructuralIntegrity() — 悬空边、重复节点、约束违反
  - [x] SubTask 15.3: 实现 assessTripleAccuracy() — 抽样边检查是否有证据支撑
  - [x] SubTask 15.4: 实现 assessEvidenceCoverage() — 有证据节点/边占比
  - [x] SubTask 15.5: 实现 assessCalibration() — 按来源分桶统计 Precision
  - [x] SubTask 15.6: 更新 buildReport() 输出 4 个独立指标章节，空图谱显示 0/N/A 而非 100%

- [x] Task 16: 为 P0-5 编写单元测试
  - [x] SubTask 16.1: 测试空图谱准确率非 100%
  - [x] SubTask 16.2: 测试悬空边被检测并标红
  - [x] SubTask 16.3: 测试证据覆盖率数值可验证（有证据节点数/总节点数）
  - [x] SubTask 16.4: 测试置信度校准按来源分桶显示 Precision

## 阶段一：P0-4 统一报告真值口径（依赖 P0-1 证据引用和 P0-3 状态正确）

- [x] Task 17: 新建 ReportTruthPolicy 策略类
  - [x] SubTask 17.1: forBody() 返回仅 CONFIRMED 的 Predicate
  - [x] SubTask 17.2: forAppendix() 返回 PENDING_CONFIRM/INFERRED 的 Predicate
  - [x] SubTask 17.3: excluded() 返回 REJECTED/STALE/CONFLICTED 的 Predicate

- [x] Task 18: 修改 SystemOverviewService 报告生成分区
  - [x] SubTask 18.1: 新增 loadConfirmedClaims() 仅加载 CONFIRMED Claim
  - [x] SubTask 18.2: 新增 loadPendingClaims() 加载 PENDING_CONFIRM/INFERRED Claim
  - [x] SubTask 18.3: Markdown 生成中分"正文（已确认结论）"和"附录（待确认/推断）"两个区域
  - [x] SubTask 18.4: 截断时追加"覆盖 X/Y，尚有 N 条未展示"提示

- [x] Task 19: 修改 Neo4jProjectionRepository 截断查询返回 totalCount + truncated 标志
  - [x] SubTask 19.1: Neo4jProjectionRepository 方法无 LIMIT 截断，改在 SystemOverviewService 中通过 countClaimsByStatus 检测截断并显示覆盖率
  - [x] SubTask 19.2: 调用方 appendCoverageHint() 处理截断提示

- [x] Task 20: 修改 SystemOverviewDocumentService Markdown 生成分区
  - [x] SubTask 20.1: SystemOverviewDocumentService.generateMarkdownContent() 返回 null stub，实际 Markdown 生成分区已在 SystemOverviewService 中实现，无需改动

- [x] Task 21: 为 P0-4 编写单元测试
  - [x] SubTask 21.1: 测试报告正文仅含 CONFIRMED Claim（generateMarkdown_partitionsBodyAndAppendixByTruthPolicy）
  - [x] SubTask 21.2: 测试 PENDING_CONFIRM/INFERRED 在附录区有标识（generateMarkdown_partitionsBodyAndAppendixByTruthPolicy）
  - [x] SubTask 21.3: 测试 REJECTED/STALE/CONFLICTED 不出现（ReportTruthPolicyTest.excluded_rejectsRejectedStaleConflicted）
  - [x] SubTask 21.4: 测试截断显示覆盖率提示（generateMarkdown_showsCoverageHintWhenTruncated）

## 阶段一：P0-6 调整发布顺序并接通主流程（依赖 P0-4 和 P0-5）

- [x] Task 22: 调整 ScanArtifactPublisher.publish() 内部执行顺序
  - [x] SubTask 22.1: 将 completeEdges() 和 detectCommunities() 移到 publishGraphQualityReport() 之前
  - [x] SubTask 22.2: 将 publishSystemOverview() 等总结报告移到最后

- [x] Task 23: 在 ProjectScanner 中注入并调用 ScanArtifactPublisher
  - [x] SubTask 23.1: 注入 ScanArtifactPublisher 依赖（@Autowired(required=false) setter）
  - [x] SubTask 23.2: 保留 generateSystemOverviewDocument() + 追加 publishScanArtifacts()（保留前置数据准备）
  - [x] SubTask 23.3: try/catch 包裹，失败仅 warn 不阻塞

- [x] Task 24: 在 AiScanJobWorker 中注入并调用 ScanArtifactPublisher
  - [x] SubTask 24.1: 注入 ScanArtifactPublisher 依赖
  - [x] SubTask 24.2: AI 编排完成后调用 publishScanArtifacts()
  - [x] SubTask 24.3: try/catch 包裹，失败仅 warn 不阻塞

- [x] Task 25: 实现 SystemOverviewDocumentService.generateMarkdownContent() 移除 null 存根
  - [x] SubTask 25.1: SystemOverviewService 已注入
  - [x] SubTask 25.2: 调用 systemOverviewService.generateMarkdown() + ensureQaFoundationSection()
  - [x] SubTask 25.3: 与 generateAfterScan 共用同一份内容逻辑（P0-4 分区口径）

- [x] Task 26: 为 P0-6 编写单元测试
  - [x] SubTask 26.1: 测试产物生成顺序正确（InOrder 验证 completeEdges→detectCommunities→publishGraphQualityReport→publishSystemOverview）
  - [x] SubTask 26.2: 测试边补全失败不阻塞后续发布
  - [x] SubTask 26.3: 测试 system-overview.md 内容非空（generateMarkdownContent 返回非 null 时落盘）
  - [x] SubTask 26.4: 测试发布失败不阻塞扫描主流程（回归验证通过）

## 全局验证

- [x] Task 27: 运行 mvn clean test 全局测试验证
  - [x] SubTask 27.1: 确认 0 failures, 0 errors（1522 tests, 0 failures, 0 errors, 22 skipped）
  - [x] SubTask 27.2: 确认新增测试全部通过

# Task Dependencies
- [Task 9-12] (P0-1) 依赖 [Task 1-5] (P0-2) — 分块元数据需先就位
- [Task 13-16] (P0-5) 最好在 [Task 9-12] (P0-1) 后做 — 证据覆盖率需要证据链完整
- [Task 17-21] (P0-4) 依赖 [Task 9-12] (P0-1) 和 [Task 6-8] (P0-3) — 证据引用和状态需正确
- [Task 22-26] (P0-6) 依赖 [Task 17-21] (P0-4) 和 [Task 13-16] (P0-5) — 发布内容需正确
- [Task 1-5] (P0-2) 和 [Task 6-8] (P0-3) 无依赖，可并行
