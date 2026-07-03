package io.github.legacygraph.service.graph;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.dto.graph.FeatureSlice.SliceNodeRef;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import io.github.legacygraph.entity.ToolEvidenceEntity;
import io.github.legacygraph.repository.ToolEvidenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 动态功能切片合成器 — 替代固定层级 FeatureSliceBuilder。
 * <p>
 * 核心思路：不依赖"Feature → Page → ApiEndpoint → Method → SqlStatement → Table"
 * 的固定层级假设，而是从 KnowledgeClaim（知识断言）中读取 Feature 的入口、实现、
 * 数据、规则、验证关系，再结合 Neo4j 图谱补充完整的节点详情。
 * </p>
 *
 * <h3>支持的多入口类型</h3>
 * <ul>
 *   <li>Page — 前端页面入口</li>
 *   <li>ApiEndpoint — API 接口入口</li>
 *   <li>ScheduledJob — 定时任务入口</li>
 *   <li>MQConsumer — 消息消费者入口（MessageConsumer）</li>
 *   <li>BatchTask — 批处理任务入口</li>
 *   <li>ExternalSystem — 外部回调入口（ExternalCallback）</li>
 * </ul>
 *
 * <h3>Claim 关系映射</h3>
 * <pre>
 * EXPOSED_BY     → entrances（入口）
 * IMPLEMENTS / HANDLED_BY → implementation（实现）
 * READS / WRITES → data（数据）
 * ENFORCES_RULE / HAS_RULE → rules（规则）
 * VERIFIED_BY    → verification（验证）
 * </pre>
 *
 * <h3>图谱边遍历补充</h3>
 * <p>在 Claim 覆盖不全时，从 Feature 节点出发沿 Neo4j 边遍历补齐各层节点。</p>
 *
 * @see FeatureSlice
 * @see KnowledgeClaimService
 * @see Neo4jGraphDao
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureSliceSynthesizer {

    private final KnowledgeClaimService knowledgeClaimService;
    private final Neo4jGraphDao neo4jGraphDao;
    /** 外部工具证据 Repository — 读取工具运行产生的结构化证据，补充切片覆盖率 */
    private final ToolEvidenceRepository toolEvidenceRepository;

    /** 入口类型集合：动态识别多入口形态 */
    private static final Set<String> ENTRANCE_TYPES = Set.of(
            "Page", "ApiEndpoint", "ScheduledJob", "MQConsumer", "ExternalSystem"
    );

    /** 实现层节点类型 */
    private static final Set<String> IMPLEMENTATION_TYPES = Set.of(
            "Controller", "Service", "Method", "Mapper"
    );

    /** 数据层节点类型 */
    private static final Set<String> DATA_TYPES = Set.of(
            "SqlStatement", "Table", "Column"
    );

    /** 规则层节点类型 */
    private static final Set<String> RULE_TYPES = Set.of(
            "BusinessRule", "Permission"
    );

    /** 验证层节点类型 */
    private static final Set<String> VERIFICATION_TYPES = Set.of(
            "TestCase", "Assertion"
    );

    /** 入口关系的 Claim 谓词集合 */
    private static final Set<String> ENTRANCE_PREDICATES = Set.of(
            "EXPOSED_BY", "CALLS"
    );

    /** 实现关系的 Claim 谓词集合 */
    private static final Set<String> IMPLEMENTATION_PREDICATES = Set.of(
            "IMPLEMENTS", "HANDLED_BY", "IMPLEMENTED_BY"
    );

    /** 数据关系的 Claim 谓词集合 */
    private static final Set<String> DATA_PREDICATES = Set.of(
            "READS", "WRITES"
    );

    /** 规则关系的 Claim 谓词集合 */
    private static final Set<String> RULE_PREDICATES = Set.of(
            "ENFORCES_RULE", "HAS_RULE"
    );

    /** 验证关系的 Claim 谓词集合 */
    private static final Set<String> VERIFICATION_PREDICATES = Set.of(
            "VERIFIED_BY", "ASSERTS"
    );

    // ==================== 主入口 ====================

    /**
     * 为指定 Feature 动态合成功能切片。
     * <p>
     * 综合 KnowledgeClaim（知识断言图谱）和 Neo4j 图谱两条线索，
     * 构建包含入口、实现、数据、规则、验证、缺口的完整切片。
     * </p>
     *
     * @param projectId  项目ID
     * @param versionId  版本ID
     * @param featureKey Feature 标识（节点名/显示名/key）
     * @return 动态合成的功能切片，若 Feature 不存在则返回覆盖状态为 UNCOVERED 的空切片
     */
    public FeatureSlice synthesizeFeatureSlice(String projectId, String versionId, String featureKey) {
        // 步骤1：在 Neo4j 图谱中查找 Feature 节点
        Optional<GraphNode> featureNodeOpt = findFeatureNode(projectId, versionId, featureKey);

        // 步骤2：查询该 Feature 相关的所有 KnowledgeClaim
        List<KnowledgeClaim> featureClaims = queryFeatureClaims(projectId, versionId, featureKey);

        String featureNodeId = featureNodeOpt.map(GraphNode::getId).orElse(null);
        String featureDisplayName = featureNodeOpt
                .map(n -> n.getDisplayName() != null && !n.getDisplayName().isBlank()
                        ? n.getDisplayName() : n.getNodeName())
                .orElse(featureKey);

        // 步骤3：分别收集各层节点（Claim 图谱 + Neo4j 图谱双源）
        List<SliceNodeRef> entrances = collectEntrances(projectId, versionId, featureNodeId, featureClaims);
        List<SliceNodeRef> implementation = collectImplementation(projectId, versionId,
                featureNodeId, entrances, featureClaims);
        List<SliceNodeRef> data = collectData(projectId, versionId, featureNodeId,
                implementation, featureClaims);
        List<SliceNodeRef> rules = collectRules(projectId, versionId, featureNodeId,
                entrances, featureClaims);
        List<SliceNodeRef> verification = collectVerification(projectId, versionId,
                featureNodeId, featureClaims);

        // 步骤3.5：从外部工具证据（ToolEvidence）补充各层节点覆盖率
        // 工具证据的 graphNodeKey 可匹配到图谱节点，提高入口、实现、数据、规则章节覆盖率
        supplementCoverageFromToolEvidence(projectId, versionId, featureNodeId,
                entrances, implementation, data, rules, verification);

        // 步骤4：识别缺口
        List<SliceNodeRef> gaps = identifyGaps(featureKey, entrances, implementation,
                data, rules, verification);

        // 步骤5：计算覆盖状态与置信度
        CoverageResult coverage = computeCoverage(entrances, implementation, data, rules, verification);

        // 步骤6：收集证据来源
        List<String> evidenceSources = collectEvidenceSources(entrances, implementation, data, rules, verification);

        // 步骤7：构建 FeatureSlice
        FeatureSlice slice = FeatureSlice.builder()
                .sliceId(featureNodeId != null ? featureNodeId : featureKey)
                .projectId(projectId)
                .versionId(versionId)
                .name(featureDisplayName)
                .featureName(featureDisplayName)
                .entryPage(entrances.isEmpty() ? null : entrances.get(0).getDisplayName())
                // 传统固定层级字段（向后兼容）
                .pageIds(extractIdsByType(entrances, "Page"))
                .apiIds(extractIdsByType(entrances, "ApiEndpoint"))
                .methodIds(extractIdsByType(implementation, "Method"))
                .sqlIds(extractIdsByType(data, "SqlStatement"))
                .tableIds(extractIdsByType(data, "Table"))
                .permissionIds(extractIdsByType(rules, "Permission"))
                .ruleIds(extractIdsByType(rules, "BusinessRule"))
                .testCaseIds(extractIdsByType(verification, "TestCase"))
                // M5 动态字段
                .entrances(entrances)
                .implementation(implementation)
                .data(data)
                .rules(rules)
                .verification(verification)
                .gaps(gaps)
                // 状态字段
                .confidence(coverage.confidence)
                .status(featureNodeOpt.isPresent() ? "ACTIVE" : "NO_FEATURE_NODE")
                .riskLevel(coverage.riskLevel)
                .coverageStatus(coverage.coverageStatus)
                .evidenceSources(evidenceSources)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        log.info("合成功能切片 featureKey={}, entrances={}, impl={}, data={}, rules={}, verify={}, gaps={}, coverage={}",
                featureKey, entrances.size(), implementation.size(), data.size(),
                rules.size(), verification.size(), gaps.size(), coverage.coverageStatus);

        return slice;
    }

    // ==================== 节点查找 ====================

    /**
     * 在 Neo4j 图谱中按 featureKey 查找 Feature 节点。
     * 先精确匹配 nodeKey/displayName/nodeName，再模糊匹配。
     */
    private Optional<GraphNode> findFeatureNode(String projectId, String versionId, String featureKey) {
        // 精确查找：使用 queryNodes(nodeKey) 参数
        List<GraphNode> exact = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(),
                featureKey, null, null, null, 1);
        if (!exact.isEmpty()) {
            return Optional.of(exact.get(0));
        }

        // 模糊查找：遍历所有 Feature 节点比对名称
        List<GraphNode> all = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(),
                null, null, null, null, 500);
        for (GraphNode node : all) {
            String name = node.getNodeName() != null ? node.getNodeName().toLowerCase() : "";
            String display = node.getDisplayName() != null ? node.getDisplayName().toLowerCase() : "";
            String query = featureKey.toLowerCase();
            if (name.contains(query) || display.contains(query)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    // ==================== Claim 查询 ====================

    /**
     * 查询与指定 Feature 相关的所有 KnowledgeClaim。
     * 从 KnowledgeClaimService 拉取 project+version 下所有 Feature 类型的 Claim，
     * 再按 subjectKey 过滤。
     */
    private List<KnowledgeClaim> queryFeatureClaims(String projectId, String versionId, String featureKey) {
        // 查询所有 Feature 相关 Claim
        List<KnowledgeClaim> allClaims = knowledgeClaimService.listClaims(
                projectId, versionId, "Feature", null, null, null, 500);

        String keyLower = featureKey.toLowerCase();
        return allClaims.stream()
                .filter(c -> {
                    String sk = c.getSubjectKey();
                    return sk != null && sk.toLowerCase().contains(keyLower);
                })
                .collect(Collectors.toList());
    }

    // ==================== 各层节点收集 ====================

    /**
     * 收集入口节点。
     * <p>
     * 双源策略：
     * <ol>
     *   <li>从 Claim 中提取 predicate ∈ {EXPOSED_BY, CALLS} 的 objectKey</li>
     *   <li>从 Neo4j 图谱中沿 EXPOSED_BY / CALLS 边查找 Feature 的连接节点</li>
     * </ol>
     * 最终按 ENTRANCE_TYPES 过滤，去重合并。
     * </p>
     */
    private List<SliceNodeRef> collectEntrances(String projectId, String versionId,
                                                 String featureNodeId,
                                                 List<KnowledgeClaim> featureClaims) {
        Set<String> seenIds = new HashSet<>();
        List<SliceNodeRef> result = new ArrayList<>();

        // 源1：从 Claim 中提取入口
        for (KnowledgeClaim claim : featureClaims) {
            if (ENTRANCE_PREDICATES.contains(claim.getPredicate())
                    && claim.getObjectKey() != null) {
                resolveAndAddNodeRef(projectId, versionId, claim.getObjectKey(),
                        claim.getObjectType(), claim.getPredicate(), seenIds, result, ENTRANCE_TYPES);
            }
        }

        // 源2：从 Neo4j 图谱 Feature 节点出发查找 EXPOSED_BY / CALLS 出边目标
        if (featureNodeId != null) {
            for (String edgeType : List.of(EdgeType.EXPOSED_BY.name(), EdgeType.CALLS.name())) {
                List<GraphNode> connected = findConnectedNodes(projectId, versionId,
                        featureNodeId, edgeType, true);
                for (GraphNode node : connected) {
                    addGraphNodeAsRef(node, edgeType, seenIds, result, ENTRANCE_TYPES);
                }
            }
        }

        log.debug("收集入口节点: {} 个", result.size());
        return result;
    }

    /**
     * 收集实现节点。
     * <p>
     * 双源策略：
     * <ol>
     *   <li>从 Claim 中提取 predicate ∈ {IMPLEMENTS, HANDLED_BY, IMPLEMENTED_BY}</li>
     *   <li>从入口节点出发，沿 HANDLED_BY / CALLS / IMPLEMENTED_BY / EXECUTES 边向下遍历</li>
     * </ol>
     * </p>
     */
    private List<SliceNodeRef> collectImplementation(String projectId, String versionId,
                                                      String featureNodeId,
                                                      List<SliceNodeRef> entrances,
                                                      List<KnowledgeClaim> featureClaims) {
        Set<String> seenIds = new HashSet<>();
        List<SliceNodeRef> result = new ArrayList<>();

        // 源1：从 Claim 中提取实现
        for (KnowledgeClaim claim : featureClaims) {
            if (IMPLEMENTATION_PREDICATES.contains(claim.getPredicate())
                    && claim.getObjectKey() != null) {
                resolveAndAddNodeRef(projectId, versionId, claim.getObjectKey(),
                        claim.getObjectType(), claim.getPredicate(), seenIds, result, IMPLEMENTATION_TYPES);
            }
        }

        // 源2：从入口节点出发遍历实现层
        for (SliceNodeRef entrance : entrances) {
            if (entrance.getNodeId() == null) continue;
            for (String edgeType : List.of(
                    EdgeType.HANDLED_BY.name(), EdgeType.CALLS.name(),
                    EdgeType.IMPLEMENTED_BY.name(), EdgeType.EXECUTES.name())) {
                List<GraphNode> connected = findConnectedNodes(projectId, versionId,
                        entrance.getNodeId(), edgeType, true);
                for (GraphNode node : connected) {
                    addGraphNodeAsRef(node, edgeType, seenIds, result, IMPLEMENTATION_TYPES);
                }
            }
        }

        // 源3：如果入口为空，从 Feature 节点直接查找 IMPLEMENTED_BY
        if (result.isEmpty() && featureNodeId != null) {
            List<GraphNode> connected = findConnectedNodes(projectId, versionId,
                    featureNodeId, EdgeType.IMPLEMENTED_BY.name(), true);
            for (GraphNode node : connected) {
                addGraphNodeAsRef(node, EdgeType.IMPLEMENTED_BY.name(), seenIds, result, IMPLEMENTATION_TYPES);
            }
        }

        log.debug("收集实现节点: {} 个", result.size());
        return result;
    }

    /**
     * 收集数据节点。
     * <p>
     * 从实现节点出发沿 READS/WRITES/EXECUTES/JOINS/HAS_COLUMN 边查找数据层节点。
     * </p>
     */
    private List<SliceNodeRef> collectData(String projectId, String versionId,
                                            String featureNodeId,
                                            List<SliceNodeRef> implementation,
                                            List<KnowledgeClaim> featureClaims) {
        Set<String> seenIds = new HashSet<>();
        List<SliceNodeRef> result = new ArrayList<>();

        // 源1：从 Claim 中提取数据关系
        for (KnowledgeClaim claim : featureClaims) {
            if (DATA_PREDICATES.contains(claim.getPredicate())
                    && claim.getObjectKey() != null) {
                resolveAndAddNodeRef(projectId, versionId, claim.getObjectKey(),
                        claim.getObjectType(), claim.getPredicate(), seenIds, result, DATA_TYPES);
            }
        }

        // 源2：从实现节点出发遍历数据层
        for (SliceNodeRef impl : implementation) {
            if (impl.getNodeId() == null) continue;
            for (String edgeType : List.of(
                    EdgeType.READS.name(), EdgeType.WRITES.name(),
                    EdgeType.EXECUTES.name(), EdgeType.JOINS.name(),
                    EdgeType.HAS_COLUMN.name())) {
                List<GraphNode> connected = findConnectedNodes(projectId, versionId,
                        impl.getNodeId(), edgeType, true);
                for (GraphNode node : connected) {
                    addGraphNodeAsRef(node, edgeType, seenIds, result, DATA_TYPES);
                }
            }
        }

        log.debug("收集数据节点: {} 个", result.size());
        return result;
    }

    /**
     * 收集规则节点。
     * <p>
     * 从 Feature / 入口节点出发沿 HAS_RULE / ENFORCES_RULE / REQUIRES_PERMISSION 边查找。
     * </p>
     */
    private List<SliceNodeRef> collectRules(String projectId, String versionId,
                                             String featureNodeId,
                                             List<SliceNodeRef> entrances,
                                             List<KnowledgeClaim> featureClaims) {
        Set<String> seenIds = new HashSet<>();
        List<SliceNodeRef> result = new ArrayList<>();

        // 源1：从 Claim 中提取规则
        for (KnowledgeClaim claim : featureClaims) {
            if (RULE_PREDICATES.contains(claim.getPredicate())
                    && claim.getObjectKey() != null) {
                resolveAndAddNodeRef(projectId, versionId, claim.getObjectKey(),
                        claim.getObjectType(), claim.getPredicate(), seenIds, result, RULE_TYPES);
            }
        }

        // 源2：从 Feature 节点出发
        if (featureNodeId != null) {
            for (String edgeType : List.of(
                    EdgeType.HAS_RULE.name(), EdgeType.REQUIRES_PERMISSION.name())) {
                List<GraphNode> connected = findConnectedNodes(projectId, versionId,
                        featureNodeId, edgeType, true);
                for (GraphNode node : connected) {
                    addGraphNodeAsRef(node, edgeType, seenIds, result, RULE_TYPES);
                }
            }
        }

        // 源3：从入口节点（特别是 ApiEndpoint）出发查找 REQUIRES_PERMISSION
        for (SliceNodeRef entrance : entrances) {
            if (entrance.getNodeId() == null) continue;
            List<GraphNode> perms = findConnectedNodes(projectId, versionId,
                    entrance.getNodeId(), EdgeType.REQUIRES_PERMISSION.name(), true);
            for (GraphNode node : perms) {
                addGraphNodeAsRef(node, EdgeType.REQUIRES_PERMISSION.name(),
                        seenIds, result, RULE_TYPES);
            }
        }

        log.debug("收集规则节点: {} 个", result.size());
        return result;
    }

    /**
     * 收集验证节点。
     * <p>
     * 从 Feature 或入口节点出发沿 VERIFIED_BY / ASSERTS 边查找 TestCase / Assertion。
     * </p>
     */
    private List<SliceNodeRef> collectVerification(String projectId, String versionId,
                                                    String featureNodeId,
                                                    List<KnowledgeClaim> featureClaims) {
        Set<String> seenIds = new HashSet<>();
        List<SliceNodeRef> result = new ArrayList<>();

        // 源1：从 Claim 中提取验证关系
        for (KnowledgeClaim claim : featureClaims) {
            if (VERIFICATION_PREDICATES.contains(claim.getPredicate())
                    && claim.getObjectKey() != null) {
                resolveAndAddNodeRef(projectId, versionId, claim.getObjectKey(),
                        claim.getObjectType(), claim.getPredicate(), seenIds, result, VERIFICATION_TYPES);
            }
        }

        // 源2：从 Feature 节点出发查找 VERIFIED_BY
        if (featureNodeId != null) {
            for (String edgeType : List.of(EdgeType.VERIFIED_BY.name(), EdgeType.ASSERTS.name())) {
                List<GraphNode> connected = findConnectedNodes(projectId, versionId,
                        featureNodeId, edgeType, true);
                for (GraphNode node : connected) {
                    addGraphNodeAsRef(node, edgeType, seenIds, result, VERIFICATION_TYPES);
                }
            }
        }

        log.debug("收集验证节点: {} 个", result.size());
        return result;
    }

    // ==================== 外部工具证据补充 ====================

    /**
     * 从外部工具证据（ToolEvidence 表）补充各层节点覆盖率。
     * <p>
     * 策略：
     * <ol>
     *   <li>查询该项目+版本下所有 ToolEvidence 记录</li>
     *   <li>按 graphNodeKey 在 Neo4j 图谱中解析对应节点</li>
     *   <li>根据节点类型分配到 entrances / implementation / data / rules / verification 各层</li>
     *   <li>已存在的节点不重复添加（按 nodeId 去重）</li>
     * </ol>
     * 外部工具证据可提高入口、实现、数据、规则各章节的覆盖率。
     * </p>
     */
    private void supplementCoverageFromToolEvidence(String projectId, String versionId,
                                                     String featureNodeId,
                                                     List<SliceNodeRef> entrances,
                                                     List<SliceNodeRef> implementation,
                                                     List<SliceNodeRef> data,
                                                     List<SliceNodeRef> rules,
                                                     List<SliceNodeRef> verification) {
        try {
            // 查询该项目+版本下所有工具证据记录
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ToolEvidenceEntity>();
            // 注意：ToolEvidence 没有直接的 projectId/versionId，通过 toolRunId 关联
            // 简化实现：查询所有有 graphNodeKey 的证据记录，逐个在 Neo4j 中验证归属
            List<ToolEvidenceEntity> allEvidence = toolEvidenceRepository.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ToolEvidenceEntity>()
                            .isNotNull(ToolEvidenceEntity::getGraphNodeKey));

            if (allEvidence.isEmpty()) {
                log.debug("无外部工具证据记录");
                return;
            }

            // 收集所有各层已有的 nodeId，用于去重
            Set<String> existingNodeIds = new HashSet<>();
            for (List<SliceNodeRef> layer : List.of(entrances, implementation, data, rules, verification)) {
                for (SliceNodeRef ref : layer) {
                    if (ref.getNodeId() != null) {
                        existingNodeIds.add(ref.getNodeId());
                    }
                }
            }

            int supplemented = 0;
            for (ToolEvidenceEntity ev : allEvidence) {
                if (ev.getGraphNodeKey() == null || ev.getGraphNodeKey().isBlank()) {
                    continue;
                }
                // 按 graphNodeKey 在 Neo4j 中查找节点
                List<GraphNode> matched = neo4jGraphDao.queryNodes(
                        projectId, versionId, null, ev.getGraphNodeKey(), null, null, null, 5);
                for (GraphNode node : matched) {
                    if (node == null || node.getId() == null) continue;
                    if (!existingNodeIds.add(node.getId())) continue; // 去重

                    // 根据节点类型分配到对应层
                    String nodeType = node.getNodeType();
                    String sourceLabel = "TOOL_EVIDENCE:" + (ev.getEvidenceType() != null ? ev.getEvidenceType() : "UNKNOWN");
                    SliceNodeRef ref = SliceNodeRef.builder()
                            .nodeId(node.getId())
                            .nodeType(nodeType)
                            .nodeName(node.getNodeName())
                            .displayName(node.getDisplayName() != null ? node.getDisplayName() : node.getNodeName())
                            .description(node.getDescription())
                            .sourceType(sourceLabel)
                            .confidence(ev.getConfidence() != null
                                    ? BigDecimal.valueOf(ev.getConfidence())
                                    : node.getConfidence())
                            .edgeType("EVIDENCE_SUPPORTS")
                            .build();

                    if (ENTRANCE_TYPES.contains(nodeType)) {
                        entrances.add(ref);
                    } else if (IMPLEMENTATION_TYPES.contains(nodeType)) {
                        implementation.add(ref);
                    } else if (DATA_TYPES.contains(nodeType)) {
                        data.add(ref);
                    } else if (RULE_TYPES.contains(nodeType)) {
                        rules.add(ref);
                    } else if (VERIFICATION_TYPES.contains(nodeType)) {
                        verification.add(ref);
                    }
                    supplemented++;
                }
            }

            if (supplemented > 0) {
                log.info("外部工具证据补充了 {} 个切片节点: entrances={}, impl={}, data={}, rules={}, verify={}",
                        supplemented, entrances.size(), implementation.size(),
                        data.size(), rules.size(), verification.size());
            }
        } catch (Exception e) {
            // 工具证据补充失败不能影响基础切片合成
            log.warn("外部工具证据补充失败: featureKey 相关 projectId={}, err={}", projectId, e.getMessage());
        }
    }

    // ==================== 缺口识别 ====================

    /**
     * 识别功能切片的缺口。
     * <p>
     * 缺口类型包括：
     * <ul>
     *   <li>NO_ENTRANCE — 未找到任何入口</li>
     *   <li>NO_IMPLEMENTATION — 有入口但无实现</li>
     *   <li>NO_DATA — 有实现但无数据访问</li>
     *   <li>NO_RULES — 无关联业务规则或权限</li>
     *   <li>NO_VERIFICATION — 无测试用例或断言覆盖</li>
     * </ul>
     * </p>
     */
    private List<SliceNodeRef> identifyGaps(String featureKey,
                                             List<SliceNodeRef> entrances,
                                             List<SliceNodeRef> implementation,
                                             List<SliceNodeRef> data,
                                             List<SliceNodeRef> rules,
                                             List<SliceNodeRef> verification) {
        List<SliceNodeRef> gaps = new ArrayList<>();

        if (entrances.isEmpty()) {
            gaps.add(SliceNodeRef.builder()
                    .nodeId("GAP:NO_ENTRANCE:" + featureKey)
                    .nodeType("Gap")
                    .nodeName("NO_ENTRANCE")
                    .displayName("未找到入口（Page/ApiEndpoint/ScheduledJob/MessageConsumer/BatchTask/ExternalCallback）")
                    .description("Feature「" + featureKey + "」缺少入口节点，可能未被任何页面/API/定时任务暴露")
                    .edgeType("GAP")
                    .build());
        }

        if (!entrances.isEmpty() && implementation.isEmpty()) {
            gaps.add(SliceNodeRef.builder()
                    .nodeId("GAP:NO_IMPLEMENTATION:" + featureKey)
                    .nodeType("Gap")
                    .nodeName("NO_IMPLEMENTATION")
                    .displayName("入口缺少实现")
                    .description("Feature「" + featureKey + "」有入口但未找到 Controller/Service/Method 等实现节点")
                    .edgeType("GAP")
                    .build());
        }

        if (!implementation.isEmpty() && data.isEmpty()) {
            gaps.add(SliceNodeRef.builder()
                    .nodeId("GAP:NO_DATA:" + featureKey)
                    .nodeType("Gap")
                    .nodeName("NO_DATA")
                    .displayName("实现缺少数据访问")
                    .description("Feature「" + featureKey + "」有实现但未找到 SqlStatement/Table/Column 等数据节点")
                    .edgeType("GAP")
                    .build());
        }

        if (rules.isEmpty()) {
            gaps.add(SliceNodeRef.builder()
                    .nodeId("GAP:NO_RULES:" + featureKey)
                    .nodeType("Gap")
                    .nodeName("NO_RULES")
                    .displayName("缺少业务规则/权限")
                    .description("Feature「" + featureKey + "」未关联 BusinessRule/Permission 规则节点")
                    .edgeType("GAP")
                    .build());
        }

        if (verification.isEmpty()) {
            gaps.add(SliceNodeRef.builder()
                    .nodeId("GAP:NO_VERIFICATION:" + featureKey)
                    .nodeType("Gap")
                    .nodeName("NO_VERIFICATION")
                    .displayName("缺少测试验证")
                    .description("Feature「" + featureKey + "」未关联 TestCase/Assertion 验证节点")
                    .edgeType("GAP")
                    .build());
        }

        return gaps;
    }

    // ==================== 覆盖计算 ====================

    /**
     * 覆盖计算结果。
     */
    private static class CoverageResult {
        final String coverageStatus;
        final String riskLevel;
        final BigDecimal confidence;

        CoverageResult(String coverageStatus, String riskLevel, BigDecimal confidence) {
            this.coverageStatus = coverageStatus;
            this.riskLevel = riskLevel;
            this.confidence = confidence;
        }
    }

    /**
     * 根据各层节点覆盖情况计算覆盖状态、风险等级和置信度。
     */
    private CoverageResult computeCoverage(List<SliceNodeRef> entrances,
                                            List<SliceNodeRef> implementation,
                                            List<SliceNodeRef> data,
                                            List<SliceNodeRef> rules,
                                            List<SliceNodeRef> verification) {
        int totalLayers = 5; // entrance, implementation, data, rules, verification
        int coveredLayers = 0;
        if (!entrances.isEmpty()) coveredLayers++;
        if (!implementation.isEmpty()) coveredLayers++;
        if (!data.isEmpty()) coveredLayers++;
        if (!rules.isEmpty()) coveredLayers++;
        if (!verification.isEmpty()) coveredLayers++;

        double coverageRatio = (double) coveredLayers / totalLayers;

        String coverageStatus = coverageRatio >= 0.8 ? "COVERED"
                : coverageRatio >= 0.3 ? "PARTIAL" : "UNCOVERED";
        String riskLevel = coverageRatio < 0.5 ? "HIGH"
                : coverageRatio < 0.8 ? "MEDIUM" : "LOW";
        BigDecimal confidence = BigDecimal.valueOf(coverageRatio * 0.9)
                .setScale(2, RoundingMode.HALF_UP);

        return new CoverageResult(coverageStatus, riskLevel, confidence);
    }

    // ==================== 证据来源收集 ====================

    /**
     * 根据各层节点来源类型汇总证据来源。
     */
    private List<String> collectEvidenceSources(List<SliceNodeRef> entrances,
                                                 List<SliceNodeRef> implementation,
                                                 List<SliceNodeRef> data,
                                                 List<SliceNodeRef> rules,
                                                 List<SliceNodeRef> verification) {
        Set<String> sources = new LinkedHashSet<>();
        for (SliceNodeRef ref : entrances) {
            if (ref.getNodeType() != null && ref.getNodeType().equals("Page")) sources.add("FRONTEND");
            if (ref.getNodeType() != null && ref.getNodeType().equals("ApiEndpoint")) sources.add("BACKEND");
        }
        if (!implementation.isEmpty()) sources.add("CODE");
        if (!data.isEmpty()) {
            boolean hasSql = data.stream().anyMatch(r -> "SqlStatement".equals(r.getNodeType()));
            boolean hasDb = data.stream().anyMatch(r -> "Table".equals(r.getNodeType())
                    || "Column".equals(r.getNodeType()));
            if (hasSql) sources.add("SQL");
            if (hasDb) sources.add("DATABASE");
        }
        if (!verification.isEmpty()) sources.add("TEST");
        return new ArrayList<>(sources);
    }

    // ==================== 节点辅助方法 ====================

    /**
     * 根据 objectKey（可能为节点ID或节点key）查找 Neo4j 节点并添加到结果列表。
     * 仅当节点类型在 targetTypes 范围内时才添加。
     */
    private void resolveAndAddNodeRef(String projectId, String versionId,
                                       String objectKey, String objectType,
                                       String edgeType, Set<String> seenIds,
                                       List<SliceNodeRef> result, Set<String> targetTypes) {
        if (objectKey == null || seenIds.contains(objectKey)) return;

        // 尝试按 ID 查找
        Optional<GraphNode> nodeOpt = neo4jGraphDao.findNodeById(objectKey);
        if (nodeOpt.isEmpty()) {
            // 尝试按 nodeType + nodeKey 查找
            if (objectType != null && !objectType.isEmpty()) {
                nodeOpt = neo4jGraphDao.findNode(projectId, versionId, objectType, objectKey);
            }
        }

        if (nodeOpt.isPresent()) {
            GraphNode node = nodeOpt.get();
            if (targetTypes.contains(node.getNodeType())) {
                addGraphNodeAsRef(node, edgeType, seenIds, result, targetTypes);
            }
        } else if (targetTypes.contains(objectType)) {
            // 节点不在 Neo4j 中，但 objectType 是目标类型，创建引用（来自 Claim 的纯知识节点）
            if (seenIds.add(objectKey)) {
                result.add(SliceNodeRef.builder()
                        .nodeId(objectKey)
                        .nodeType(objectType)
                        .nodeName(objectKey)
                        .displayName(objectKey)
                        .edgeType(edgeType)
                        .sourceType("CLAIM_ONLY")
                        .build());
            }
        }
    }

    /**
     * 将 GraphNode 转换为 SliceNodeRef 并添加到结果列表（类型过滤 + 去重）。
     */
    private void addGraphNodeAsRef(GraphNode node, String edgeType,
                                    Set<String> seenIds, List<SliceNodeRef> result,
                                    Set<String> targetTypes) {
        if (node == null || node.getId() == null) return;
        if (!targetTypes.contains(node.getNodeType())) return;
        if (!seenIds.add(node.getId())) return;

        result.add(SliceNodeRef.builder()
                .nodeId(node.getId())
                .nodeType(node.getNodeType())
                .nodeName(node.getNodeName())
                .displayName(node.getDisplayName() != null ? node.getDisplayName() : node.getNodeName())
                .description(node.getDescription())
                .sourceType(node.getSourceType())
                .confidence(node.getConfidence())
                .edgeType(edgeType)
                .build());
    }

    /**
     * 从指定节点沿特定边类型查找连接的目标节点。
     *
     * @param outgoing true=出边（from→to），false=入边（to→from）
     */
    private List<GraphNode> findConnectedNodes(String projectId, String versionId,
                                                String nodeId, String edgeType,
                                                boolean outgoing) {
        // connectedNodeId 始终传 nodeId，使 Cypher 限定边的一端为该节点
        // 在 Java 侧按方向过滤，避免 outgoing=false 时退化为全项目扫描
        List<GraphEdge> edges = neo4jGraphDao.queryEdges(projectId, versionId,
                edgeType, null, nodeId,
                null, null, 50);

        List<GraphNode> nodes = new ArrayList<>();
        for (GraphEdge edge : edges) {
            String targetId = outgoing ? edge.getToNodeId() : edge.getFromNodeId();
            if (outgoing && !nodeId.equals(edge.getFromNodeId())) continue;
            if (!outgoing && !nodeId.equals(edge.getToNodeId())) continue;
            neo4jGraphDao.findNodeById(targetId).ifPresent(nodes::add);
        }
        return nodes;
    }

    /**
     * 从节点引用列表中提取指定类型的节点ID列表（用于填充传统固定层级字段）。
     */
    private List<String> extractIdsByType(List<SliceNodeRef> refs, String nodeType) {
        return refs.stream()
                .filter(r -> nodeType.equals(r.getNodeType()))
                .map(SliceNodeRef::getNodeId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }
}
