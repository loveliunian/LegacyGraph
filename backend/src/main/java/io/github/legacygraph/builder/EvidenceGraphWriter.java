package io.github.legacygraph.builder;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.EvidenceRecord;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.dto.graph.GraphWriteIntent;
import io.github.legacygraph.dto.graph.PrivacyLevel;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.llm.SecretScanService;
import io.github.legacygraph.repository.EdgeEvidenceRepository;
import io.github.legacygraph.repository.EvidenceRepository;
import io.github.legacygraph.repository.NodeEvidenceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import io.github.legacygraph.util.IdUtil;

/**
 * 统一证据图谱写入器。
 * <p>
 * 将三个 Builder（GraphBuilder、FrontendGraphBuilder、BusinessGraphBuilder）
 * 中重复的节点创建、边创建、证据创建、去重、置信度裁决逻辑集中到此模块。
 * </p>
 *
 * <h3>设计原则（见 doc/架构与三类图谱AI优化建议.md 3.1）</h3>
 * <ul>
 *   <li>AI Agent 只产出候选 claim，不能直接写 confirmed 边</li>
 *   <li>Writer 根据 sourceType、confidence、evidenceCount、runtimeVerified、humanReviewed 决定状态</li>
 *   <li>所有 provenance、置信度、证据继承、去重策略集中在一个 Seam</li>
 * </ul>
 */
@Slf4j
@Component
public class EvidenceGraphWriter {

    // ⚠️ B-S1 跨存储事务不一致：本类多处方法标注 @Transactional（见 upsertNode/upsertEdge 等），
    //   但同时写 Neo4j（Neo4jGraphDao）与 PostgreSQL（JPA Repository：Evidence/NodeEvidence/EdgeEvidence）。
    //   Spring @Transactional 仅管理 JDBC/DataSource，不覆盖 Neo4j。Neo4j 写成功后 PG 写失败，
    //   Neo4j 数据不会回滚 → 数据不一致。建议：注册 Neo4jTransactionManager 并用 ChainedTransactionManager
    //   链式管理 JDBC + Neo4j，或对最终一致场景改用补偿事务 + 幂等写入。

    private final Neo4jGraphDao neo4jGraphDao;
    private final EvidenceRepository evidenceRepository;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final SecretScanService secretScanService;
    private final PgEvidenceTxExecutor pgEvidenceTxExecutor;

    /**
     * per-nodeKey 细粒度锁：防止并发 MERGE 同一 (nodeType, nodeKey) 产生重复节点。
     * key = "nodeType::nodeKey"，value = ReentrantLock。
     * 不同 nodeKey 的 MERGE 互不阻塞，仅相同 nodeKey 串行化。
     */
    // M4 修复：使用固定大小 Striped Lock 替代 ConcurrentHashMap 动态锁，消除 unlock→remove 竞态窗口。
    // 锁数量固定为 64 槽位，按 hash 分发，避免动态创建/销毁锁的并发问题。
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] mergeLockStripes = new ReentrantLock[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            mergeLockStripes[i] = new ReentrantLock();
        }
    }

    public EvidenceGraphWriter(Neo4jGraphDao neo4jGraphDao,
                               EvidenceRepository evidenceRepository,
                               NodeEvidenceRepository nodeEvidenceRepository,
                               EdgeEvidenceRepository edgeEvidenceRepository,
                               SecretScanService secretScanService,
                               PgEvidenceTxExecutor pgEvidenceTxExecutor) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.evidenceRepository = evidenceRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.edgeEvidenceRepository = edgeEvidenceRepository;
        this.secretScanService = secretScanService;
        this.pgEvidenceTxExecutor = pgEvidenceTxExecutor;
    }

    // ==================== 节点操作 ====================

    /**
     * 根据声明查找或创建图谱节点。
     * <p>
     * 规则：
     * <ul>
     *   <li>先按 (projectId, versionId, nodeType, nodeKey) 查 Neo4j 去重</li>
     *   <li>不存在则创建新节点，并根据 sourceType 决定状态：
     *     AI_INFERENCE / DOC_AI → PENDING_CONFIRM，其他 → CONFIRMED</li>
     *   <li>创建节点成功后，自动创建证据并建立关联</li>
     *   <li>证据写入失败时（PostgreSQL 不可用），标记 Neo4j 节点 writeStatus=INCOMPLETE，
     *     进入复核队列（见 GraphWriteReconciler）</li>
     * </ul>
     *
     * @param claim 节点声明
     * @return 已存在或新创建的节点
     */
    @Transactional
    public GraphNode upsertNode(GraphNodeClaim claim) {
        // 构造节点声明（id 为新建候选；MERGE 命中已存在节点时以库内 id 为准）
        GraphNode node = new GraphNode();
        node.setId(IdUtil.fastUUID());
        node.setProjectId(claim.getProjectId());
        node.setVersionId(claim.getVersionId());
        node.setNodeType(claim.getNodeType());
        node.setNodeKey(claim.getNodeKey());
        node.setNodeName(claim.getNodeName());
        node.setDisplayName(claim.getDisplayName());
        node.setDescription(claim.getDescription());
        node.setSourceType(claim.getSourceType());
        node.setSourcePath(claim.getSourcePath());
        node.setStartLine(claim.getStartLine());
        node.setEndLine(claim.getEndLine());
        node.setConfidence(claim.getConfidence() != null ? claim.getConfidence() : BigDecimal.ONE);
        node.setStatus(deriveNodeStatus(claim.getSourceType(), claim.getConfidence(), claim.getStatus()));
        node.setProperties(claim.getProperties());
        node.setScanType(claim.getScanType());
        node.setClassName(claim.getClassName());
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());

        // M4 修复：使用 Striped Lock 替代 ConcurrentHashMap 动态锁，消除竞态
        String lockKey = claim.getNodeType() + "::" + claim.getNodeKey();
        ReentrantLock lock = mergeLockStripes[Math.abs(lockKey.hashCode()) % LOCK_STRIPES];
        lock.lock();
        try {
            // 一次 MERGE 完成去重 + 创建（替代原 findNode + createNode 两次远程往返）
            Neo4jGraphDao.NodeUpsert upsert = neo4jGraphDao.mergeNode(node);
            GraphNode merged = upsert.node();

            // 仅新建节点时创建证据（命中已存在节点则跳过，避免重复 evidence）
            if (upsert.created() && (hasText(claim.getSourcePath()) || isAiSource(claim.getSourceType()))) {
                try {
                    pgEvidenceTxExecutor.execute(() -> createEvidenceForNode(merged, claim.getSourceType(), claim.getSourcePath(),
                            claim.getStartLine(), claim.getEndLine()));
                } catch (Exception pgEx) {
                    // 跨存储补偿：Neo4j 已写入但 PG 证据写入失败 → 标记节点 INCOMPLETE
                    log.error("PG evidence write failed for Neo4j node {} (idempotencyKey={}): {} — marking INCOMPLETE",
                            merged.getId(), claim.getIdempotencyKey(), pgEx.getMessage());
                    markNodeIncomplete(merged.getId(), pgEx.getMessage());
                }
            }

            return merged;
        } finally {
            lock.unlock();
            // M4: Striped Lock 无需清理，固定槽位复用
        }
    }

    // ==================== 边操作 ====================

    /**
     * 根据声明查找或创建图谱边。
     * <p>
     * 规则：
     * <ul>
     *   <li>先按 (projectId, versionId, fromNodeId, toNodeId, edgeType, edgeKey) 去重</li>
     *   <li>AI 来源的边默认 PENDING_CONFIRM</li>
     *   <li>创建成功后自动继承源节点的证据</li>
     *   <li>证据继承失败时（PostgreSQL 不可用），标记 Neo4j 边 writeStatus=INCOMPLETE，
     *     进入复核队列</li>
     * </ul>
     *
     * @param claim 边声明
     * @return 已存在或新创建的边
     */
    @Transactional
    public GraphEdge upsertEdge(GraphEdgeClaim claim) {
        // 构造边声明（id 为新建候选；MERGE 命中已存在边时以库内 id 为准）
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(claim.getProjectId());
        edge.setVersionId(claim.getVersionId());
        edge.setFromNodeId(claim.getFromNodeId());
        edge.setToNodeId(claim.getToNodeId());
        edge.setEdgeType(claim.getEdgeType());
        edge.setEdgeKey(claim.getEdgeKey());
        edge.setSourceType(claim.getSourceType());
        edge.setConfidence(claim.getConfidence() != null ? claim.getConfidence() : BigDecimal.ONE);
        edge.setStatus(deriveEdgeStatus(claim.getSourceType(), claim.getConfidence(), claim.getStatus()));
        edge.setProperties(claim.getProperties());
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());

        // 一次 MERGE 完成去重 + 创建（替代原 findEdge + createEdge 两次远程往返）
        Neo4jGraphDao.EdgeUpsert upsert = neo4jGraphDao.mergeEdge(edge);
        if (upsert.edge() == null) {
            // from/to 节点不存在，无法建边（与原 createEdge 静默失败语义一致）
            return edge;
        }
        GraphEdge merged = upsert.edge();

        // 仅新建边时继承源节点证据（命中已存在边则跳过，避免重复继承）
        if (upsert.created()) {
            try {
                pgEvidenceTxExecutor.execute(() -> inheritEvidenceForEdge(merged, claim.getFromNodeId()));
            } catch (Exception pgEx) {
                // 跨存储补偿：Neo4j 已写入但 PG 证据继承失败 → 标记边 INCOMPLETE
                log.error("PG evidence inherit failed for Neo4j edge {} (idempotencyKey={}): {} — marking INCOMPLETE",
                        merged.getId(), claim.getIdempotencyKey(), pgEx.getMessage());
                markEdgeIncomplete(merged.getId(), pgEx.getMessage());
            }
        }

        return merged;
    }

    // ==================== 证据操作 ====================

    /**
     * 将证据关联到图谱元素（节点或边）。
     *
     * @param graphElementId 节点ID 或 边ID
     * @param evidence       证据记录
     * @param role           关联角色（PRIMARY_SOURCE / INHERITED / DERIVED / AI_GENERATED）
     */
    @Transactional
    public void attachEvidence(String graphElementId, EvidenceRecord evidence, EvidenceRole role) {
        Optional<GraphNode> node = neo4jGraphDao.findNodeById(graphElementId);
        // 1. 构造证据实体
        Evidence ev = new Evidence();
        ev.setId(IdUtil.fastUUID());
        ev.setProjectId(hasText(evidence.getProjectId())
                ? evidence.getProjectId()
                : node.map(GraphNode::getProjectId).orElse(null));
        ev.setVersionId(hasText(evidence.getVersionId())
                ? evidence.getVersionId()
                : node.map(GraphNode::getVersionId).orElse(null));
        ev.setEvidenceType(evidence.getEvidenceType());
        ev.setSourcePath(evidence.getSourcePath());
        ev.setSourceName(evidence.getSourceName());
        ev.setStartLine(evidence.getStartLine());
        ev.setEndLine(evidence.getEndLine());
        ev.setContentHash(evidence.getContentHash());
        ev.setSummary(evidence.getSummary());
        ev.setContent(evidence.getContent());
        ev.setMetadata(evidence.getMetadata());
        ev.setAstPath(evidence.getAstPath());
        ev.setSqlHash(evidence.getSqlHash());
        applyPrivacy(ev, evidence);
        ev.setDeleted(0);
        ev.setCreatedAt(LocalDateTime.now());

        // 2. 去重落库：有 contentHash 走 upsert，无则直接 insert
        Evidence persisted;
        if (hasText(ev.getContentHash())) {
            int rows = evidenceRepository.insertOrIgnore(ev);
            if (rows > 0) {
                persisted = ev; // 新插入成功
            } else {
                // 冲突：已有相同 contentHash 的证据，复用已有记录
                persisted = evidenceRepository.findByContentHash(ev.getContentHash());
                if (persisted == null) {
                    // 极端情况回退：直接 insert
                    evidenceRepository.insert(ev);
                    persisted = ev;
                }
            }
        } else {
            evidenceRepository.insert(ev);
            persisted = ev;
        }

        // 3. 判断是节点证据还是边证据，建立关联
        if (node.isPresent()) {
            NodeEvidence ne = new NodeEvidence();
            ne.setId(IdUtil.fastUUID());
            ne.setNodeId(graphElementId);
            ne.setEvidenceId(persisted.getId());
            ne.setRelationType(role.name());
            ne.setCreatedAt(LocalDateTime.now());
            nodeEvidenceRepository.insert(ne);
        } else {
            EdgeEvidence ee = new EdgeEvidence();
            ee.setId(IdUtil.fastUUID());
            ee.setEdgeId(graphElementId);
            ee.setEvidenceId(persisted.getId());
            ee.setRelationType(role.name());
            ee.setCreatedAt(LocalDateTime.now());
            edgeEvidenceRepository.insert(ee);
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 证据隐私分层（见 doc 4.4）：对含内容的证据做 secret scan，
     * 命中密钥则内容以 redacted 形式落库并打 SECRET，避免图谱成为高敏资产聚合点。
     */
    private void applyPrivacy(Evidence ev, EvidenceRecord record) {
        // 显式声明的隐私级别优先
        PrivacyLevel declared = null;
        if (hasText(record.getPrivacyLevel())) {
            try {
                declared = PrivacyLevel.valueOf(record.getPrivacyLevel().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 容错：非标准枚举值时回退到扫描结果
            }
        }
        String content = ev.getContent();
        if (hasText(content)) {
            // L1 修复：单次扫描，复用结果，避免重复调用 secretScanService.scan
            SecretScanService.SecretScanResult scan = secretScanService.scan(content);
            PrivacyLevel level;
            String policy;
            if (declared != null && declared.ordinal() >= scan.getSuggestedLevel().ordinal()) {
                // 显式声明更严格或同等时，以声明为准，但仍按策略脱敏存储
                level = declared;
                policy = hasText(record.getRedactionPolicy()) ? record.getRedactionPolicy() : scan.getSuggestedPolicy();
            } else {
                level = scan.getSuggestedLevel();
                policy = scan.getSuggestedPolicy();
            }
            // 命中密钥的内容必须以 redacted 落库（复用 scan 结果）
            if (scan.isHasSecret() && hasText(scan.getRedacted())) {
                ev.setContent(scan.getRedacted());
            } else if (PrivacyLevel.CONFIDENTIAL == level || PrivacyLevel.SECRET == level) {
                ev.setContent(scan.getRedacted());
            }
            ev.setPrivacyLevel(level.name());
            ev.setRedactionPolicy(policy);
        } else {
            ev.setPrivacyLevel(declared != null ? declared.name() : PrivacyLevel.INTERNAL.name());
            ev.setRedactionPolicy(hasText(record.getRedactionPolicy()) ? record.getRedactionPolicy() : "none");
        }
    }

    /**
     * 为节点创建证据记录并建立关联（含去重保护）。
     */
    private void createEvidenceForNode(GraphNode node, String sourceType, String sourcePath,
                                       Integer startLine, Integer endLine) {
        Evidence evidence = new Evidence();
        evidence.setId(IdUtil.fastUUID());
        evidence.setProjectId(node.getProjectId());
        evidence.setVersionId(node.getVersionId());
        evidence.setEvidenceType(mapSourceTypeToEvidenceType(sourceType));
        evidence.setSourcePath(sourcePath);
        evidence.setSourceName(node.getDisplayName());
        evidence.setStartLine(startLine);
        evidence.setEndLine(endLine);
        // 基于位置签名生成 contentHash，使 DB 层唯一索引也能拦截重复
        evidence.setContentHash(locationHash(node.getProjectId(), node.getVersionId(),
                evidence.getEvidenceType(), sourcePath, node.getDisplayName(),
                startLine, endLine));
        // 构造可读摘要
        evidence.setSummary(sourceType + " 证据: " + (node.getDisplayName() != null ? node.getDisplayName() : node.getNodeName())
                + (sourcePath != null ? " (" + sourcePath + ")" : ""));
        // 无内容证据默认 INTERNAL（节点指针型证据不含源码正文）
        if (evidence.getPrivacyLevel() == null) {
            evidence.setPrivacyLevel(PrivacyLevel.INTERNAL.name());
        }
        if (evidence.getRedactionPolicy() == null) {
            evidence.setRedactionPolicy("none");
        }
        evidence.setDeleted(0);
        evidence.setCreatedAt(LocalDateTime.now());

        // 去重落库：优先 upsert by contentHash
        int rows = evidenceRepository.insertOrIgnore(evidence);
        Evidence persisted;
        if (rows > 0) {
            persisted = evidence;
        } else {
            persisted = evidenceRepository.findByContentHash(evidence.getContentHash());
            if (persisted == null) {
                evidenceRepository.insert(evidence);
                persisted = evidence;
            }
        }

        NodeEvidence nodeEvidence = new NodeEvidence();
        nodeEvidence.setId(IdUtil.fastUUID());
        nodeEvidence.setNodeId(node.getId());
        nodeEvidence.setEvidenceId(persisted.getId());
        nodeEvidence.setRelationType(EvidenceRole.PRIMARY_SOURCE.name());
        nodeEvidence.setCreatedAt(LocalDateTime.now());
        nodeEvidenceRepository.insert(nodeEvidence);
    }

    /**
     * 边证据继承：从源节点继承其关联的所有证据。
     */
    private void inheritEvidenceForEdge(GraphEdge edge, String fromNodeId) {
        var nodeEvidences = nodeEvidenceRepository.lambdaQuery()
                .eq(NodeEvidence::getNodeId, fromNodeId)
                .list();
        if (nodeEvidences.isEmpty()) return;

        List<EdgeEvidence> batch = new ArrayList<>(nodeEvidences.size());
        for (NodeEvidence ne : nodeEvidences) {
            EdgeEvidence ee = new EdgeEvidence();
            ee.setId(IdUtil.fastUUID());
            ee.setEdgeId(edge.getId());
            ee.setEvidenceId(ne.getEvidenceId());
            ee.setRelationType(EvidenceRole.INHERITED.name());
            ee.setCreatedAt(LocalDateTime.now());
            batch.add(ee);
        }
        for (EdgeEvidence ee : batch) {
            edgeEvidenceRepository.insert(ee);
        }
    }

    /**
     * 根据来源类型决定节点状态。
     * <p>AI 产出的节点默认 PENDING_CONFIRM，代码/DB 解析的默认 CONFIRMED。</p>
     */
    private String deriveNodeStatus(String sourceType, BigDecimal confidence, String requestedStatus) {
        if (isAiSource(sourceType)) {
            // AI 推断默认待确认
            return "PENDING_CONFIRM";
        }
        if (hasText(requestedStatus)) {
            return requestedStatus;
        }
        if (sourceType == null) return "CONFIRMED";
        // 低置信度的也待确认
        if (confidence != null && confidence.compareTo(BigDecimal.valueOf(0.5)) < 0) {
            return "PENDING_CONFIRM";
        }
        return "CONFIRMED";
    }

    /**
     * 根据来源类型决定边状态。
     */
    private String deriveEdgeStatus(String sourceType, BigDecimal confidence, String requestedStatus) {
        if (isAiSource(sourceType)) {
            return "PENDING_CONFIRM";
        }
        if (hasText(requestedStatus)) {
            return requestedStatus;
        }
        if (sourceType == null) return "CONFIRMED";
        if (confidence != null && confidence.compareTo(BigDecimal.valueOf(0.5)) < 0) {
            return "PENDING_CONFIRM";
        }
        return "CONFIRMED";
    }

    private boolean isAiSource(String sourceType) {
        return "AI_INFERENCE".equals(sourceType)
                || "AI_FEATURE_MAPPING".equals(sourceType)
                || "DOC_AI".equals(sourceType);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 将源码类型映射为证据类型。
     */
    private String mapSourceTypeToEvidenceType(String sourceType) {
        if (sourceType == null) return "unknown";
        return switch (sourceType) {
            case "CODE_AST" -> "code";
            case "MYBATIS_XML", "SQL_PARSE" -> "sql";
            case "FRONTEND_AST" -> "ui";
            case "DB_METADATA" -> "db";
            case "DOCUMENT" -> "doc";
            case "AI_INFERENCE", "DOC_AI" -> "ai";
            case "RUNTIME_TRACE" -> "runtime";
            default -> sourceType.toLowerCase();
        };
    }

    /**
     * 简单判断 ID 是否为节点ID（非边ID）。
     * <p>实际项目中可通过 ID 前缀或额外查询来精确判断。</p>
     */
    private boolean isNodeId(String graphElementId) {
        // 目前通过 Neo4j 查询节点是否存在来区分
        return neo4jGraphDao.findNodeById(graphElementId).isPresent();
    }

    // ==================== 证据角色枚举 ====================

    /**
     * 写入意图（outbox 模式入口）：批量处理 node/edge claims + evidence records。
     * <p>每条 claim 独立 upsert，失败不中断其他 claim 处理。</p>
     *
     * @param intent 写入意图，包含幂等键、claims 和 evidence records
     * @return 写入结果摘要
     */
    public GraphWriteResult writeIntent(GraphWriteIntent intent) {
        int nodeCount = 0, edgeCount = 0;
        java.util.concurrent.atomic.AtomicInteger evidenceCount = new java.util.concurrent.atomic.AtomicInteger(0);
        if (intent.getNodeClaims() != null) {
            for (GraphNodeClaim nc : intent.getNodeClaims()) {
                try {
                    upsertNode(nc);
                    nodeCount++;
                } catch (Exception e) {
                    log.error("writeIntent: node claim failed (idempotencyKey={}, nodeKey={}): {}",
                            intent.getIdempotencyKey(), nc.getNodeKey(), e.getMessage());
                }
            }
        }
        if (intent.getEdgeClaims() != null) {
            for (GraphEdgeClaim ec : intent.getEdgeClaims()) {
                try {
                    upsertEdge(ec);
                    edgeCount++;
                } catch (Exception e) {
                    log.error("writeIntent: edge claim failed (idempotencyKey={}, edgeKey={}): {}",
                            intent.getIdempotencyKey(), ec.getEdgeKey(), e.getMessage());
                }
            }
        }
        if (intent.getEvidenceRecords() != null) {
            for (EvidenceRecord er : intent.getEvidenceRecords()) {
                try {
                    pgEvidenceTxExecutor.execute(() -> {
                        Evidence ev = new Evidence();
                        ev.setId(IdUtil.fastUUID());
                        ev.setProjectId(intent.getProjectId());
                        ev.setVersionId(intent.getVersionId());
                        ev.setEvidenceType(er.getEvidenceType());
                        ev.setSourcePath(er.getSourcePath());
                        ev.setSourceName(er.getSourceName());
                        ev.setContentHash(er.getContentHash());
                        ev.setSummary(er.getSummary());
                        ev.setContent(er.getContent());
                        ev.setMetadata(er.getMetadata());
                        applyPrivacy(ev, er);
                        ev.setDeleted(0);
                        ev.setCreatedAt(LocalDateTime.now());
                        if (evidenceRepository.insertOrIgnore(ev) > 0) {
                            evidenceCount.incrementAndGet();
                        }
                    });
                } catch (Exception e) {
                    log.error("writeIntent: evidence record failed (idempotencyKey={}): {}",
                            intent.getIdempotencyKey(), e.getMessage());
                }
            }
        }
        int ec = evidenceCount.get();
        log.info("writeIntent processed (idempotencyKey={}): {} nodes, {} edges, {} evidence",
                intent.getIdempotencyKey(), nodeCount, edgeCount, ec);
        return new GraphWriteResult(nodeCount, edgeCount, ec);
    }

    /** 标记 Neo4j 节点为 INCOMPLETE（PG 证据写入失败补偿） */
    private void markNodeIncomplete(String nodeId, String error) {
        try {
            neo4jGraphDao.setNodeProperty(nodeId, "writeStatus", "INCOMPLETE");
            neo4jGraphDao.setNodeProperty(nodeId, "writeError", error != null
                    ? (error.length() > 500 ? error.substring(0, 500) : error) : "unknown");
        } catch (Exception neoEx) {
            log.error("Failed to mark Neo4j node {} as INCOMPLETE: {}", nodeId, neoEx.getMessage());
        }
    }

    /** 标记 Neo4j 边为 INCOMPLETE（PG 证据写入失败补偿） */
    private void markEdgeIncomplete(String edgeId, String error) {
        try {
            neo4jGraphDao.setEdgeProperty(edgeId, "writeStatus", "INCOMPLETE");
            neo4jGraphDao.setEdgeProperty(edgeId, "writeError", error != null
                    ? (error.length() > 500 ? error.substring(0, 500) : error) : "unknown");
        } catch (Exception neoEx) {
            log.error("Failed to mark Neo4j edge {} as INCOMPLETE: {}", edgeId, neoEx.getMessage());
        }
    }

    /** 写入结果摘要 */
    public record GraphWriteResult(int nodeCount, int edgeCount, int evidenceCount) {}

    /**
     * 基于位置签名生成 SHA-256 哈希，用于节点指针型证据的去重。
     */
    private static String locationHash(String projectId, String versionId, String evidenceType,
                                       String sourcePath, String sourceName,
                                       Integer startLine, Integer endLine) {
        String input = String.format("%s|%s|%s|%s|%s|%d|%d",
                projectId, versionId, evidenceType, sourcePath, sourceName,
                startLine != null ? startLine : 0, endLine != null ? endLine : 0);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 证据关联角色。
     */
    public enum EvidenceRole {
        /** 直接来源 */
        PRIMARY_SOURCE,
        /** 从其他元素继承 */
        INHERITED,
        /** 从已有证据推导 */
        DERIVED,
        /** AI 生成 */
        AI_GENERATED,
        /** 人工确认 */
        HUMAN_CONFIRMED
    }
}
