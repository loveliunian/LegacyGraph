# Checklist

## P0-2 禁止大文档静默丢内容
- [x] DocExtractStep.readDocContent() 中 100KB→50KB 前置截断逻辑已删除
- [x] 大文档走 extractFromChunks() 全量分块处理，末尾内容不丢失
- [x] mergeByKey() 同名项保留较高置信度（else 分支有置信度比较）
- [x] mergeByKey() 同名项 evidence 列表合并（多来源证据累加）
- [x] splitContent() 返回 DocChunk record 含 chunkIndex/charStart/charEnd
- [x] chunk 元数据写入 Evidence.metadata JSONB
- [x] .doc 格式解析失败时有明确 errorMessage 而非静默
- [x] 单元测试：150KB 文档末尾内容进入抽取结果
- [x] 单元测试：mergeByKey 同名项保留较高置信度
- [x] 单元测试：splitContent 返回 chunk 含元数据

## P0-3 停止补造事实
- [x] SystemOverviewIngestService.toClaims() 中推断的 READS/WRITES 标记为 AI_INFERENCE + confidence 0.6
- [x] 推断的业务域 CONTAINS 关系标记为 AI_INFERENCE + confidence 0.6
- [x] 推断 Claim 的 lineage 字段记录推导规则 JSON（rule, description, originalConfidence）
- [x] KnowledgeClaimService.computeStatus() 对 AI_INFERENCE 返回 PENDING_CONFIRM
- [x] 单元测试：推断 WRITES 边状态为 PENDING_CONFIRM 而非 CONFIRMED
- [x] 单元测试：推断业务域 confidence < 0.85
- [x] 单元测试：推断 Claim lineage 含推导规则

## P0-1 修复证据链断裂
- [x] DocUnderstandingAgent.draft() 方法接收 evidenceRefs 和 contentExcerpt 参数
- [x] draft() 为每个 EvidenceRef 创建真实 Evidence 记录并获取 UUID
- [x] KnowledgeClaimDraft.evidenceIds 填充真实 Evidence UUID（非文件路径）
- [x] Evidence.metadata 含 extractor/model/promptVersion/contentHash/chunkId
- [x] EvidenceGraphWriter.upsertNode() 移除 upsert.created() 限制
- [x] createEvidenceForNode() 接收完整 GraphNodeClaim
- [x] lg_node_evidence 有 (node_id, evidence_id) 唯一约束
- [x] KnowledgeClaimService.upsertDrafts() 填充 supporting/contradictingClaimIds
- [x] 单元测试：Claim.evidenceIds 为真实 UUID 而非文件路径
- [x] 单元测试：多文档提取同一对象后节点证据数 ≥ 2
- [x] 单元测试：通过节点 ID 可查到全部关联 Evidence

## P0-5 重做准确率指标
- [x] Neo4jGraphDao.countDanglingEdges() 已实现
- [x] Neo4jGraphDao.countDuplicateNodes() 已实现
- [x] Neo4jGraphDao.sampleEdgesWithEvidence() 已实现
- [x] NodeEvidenceRepository.countDistinctNodeIds() 已实现
- [x] EdgeEvidenceRepository.countDistinctEdgeIds() 已实现
- [x] KnowledgeClaimService.countByStatus() 已实现
- [x] StructuralIntegrityMetric/TripleAccuracyMetric/EvidenceCoverageMetric/CalibrationMetric record 类已创建
- [x] assessStructuralIntegrity() 已实现（悬空边、重复节点、约束违反）
- [x] assessTripleAccuracy() 已实现（抽样边检查证据支撑）
- [x] assessEvidenceCoverage() 已实现（有证据节点/边占比）
- [x] assessCalibration() 已实现（按来源分桶 Precision）
- [x] buildReport() 输出 4 个独立指标章节
- [x] 空图谱显示 0/N/A 而非 100%
- [x] 单元测试：空图谱准确率非 100%
- [x] 单元测试：悬空边被检测并标红
- [x] 单元测试：证据覆盖率数值可验证
- [x] 单元测试：置信度校准按来源分桶显示

## P0-4 统一报告真值口径
- [x] ReportTruthPolicy 策略类已创建（forBody/forAppendix/excluded）
- [x] SystemOverviewService.loadConfirmedClaims() 仅加载 CONFIRMED
- [x] SystemOverviewService.loadPendingClaims() 加载 PENDING_CONFIRM/INFERRED
- [x] Markdown 分"正文（已确认结论）"和"附录（待确认/推断）"两区
- [x] 截断时显示"覆盖 X/Y，尚有 N 条未展示"
- [x] Neo4jProjectionRepository 方法无 LIMIT 截断，改在 SystemOverviewService 中通过 countClaimsByStatus 检测并显示覆盖率提示
- [x] SystemOverviewDocumentService.generateMarkdownContent() 返回 null stub，分区已在 SystemOverviewService 中实现
- [x] 单元测试：报告正文仅含 CONFIRMED Claim（generateMarkdown_partitionsBodyAndAppendixByTruthPolicy）
- [x] 单元测试：PENDING_CONFIRM/INFERRED 在附录区有标识（generateMarkdown_partitionsBodyAndAppendixByTruthPolicy）
- [x] 单元测试：REJECTED/STALE/CONFLICTED 不出现（ReportTruthPolicyTest.excluded_rejectsRejectedStaleConflicted）
- [x] 单元测试：截断显示覆盖率提示（generateMarkdown_showsCoverageHintWhenTruncated）

## P0-6 调整发布顺序并接通主流程
- [x] ScanArtifactPublisher.publish() 顺序：completeEdges → detectCommunities → publishGraphQualityReport → publishSystemOverview
- [x] ProjectScanner 注入 ScanArtifactPublisher 并在扫描完成后调用 publish()
- [x] AiScanJobWorker 注入 ScanArtifactPublisher 并在 AI 完成后调用 publish()
- [x] SystemOverviewDocumentService.generateMarkdownContent() 返回非 null
- [x] 发布失败仅 warn 不阻塞扫描主流程
- [x] 单元测试：扫描完成后 docs/legacygraph/ 生成全部产物
- [x] 单元测试：产物生成顺序正确
- [x] 单元测试：system-overview.md 内容非空
- [x] 单元测试：发布失败不阻塞扫描主流程

## 全局验证
- [x] mvn clean test 通过（0 failures, 0 errors）
- [x] 新增测试全部通过
