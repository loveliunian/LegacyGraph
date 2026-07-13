package io.github.legacygraph.service.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.agent.GraphMergeAgent;
import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.config.AgentConfigProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 图谱合并服务 — 证据化裁决版（见 doc/架构与三类图谱AI优化建议.md 3.5）。
 * <p>
 * 改进：
 * <ul>
 *   <li>Blocking：按 nodeType + normalized name 前缀分组，避免 O(n²)</li>
 *   <li>多证据评分：名称分 + 结构邻域分 + 共享证据分 + 运行时共现分 + 人工历史分</li>
 *   <li>LLM 只处理中间态候选</li>
 *   <li>合并保留 lineage：mergedFrom[], alias, 原始 evidence, 边重写记录</li>
 * </ul>
 */
@Slf4j
@Service
public class GraphMergeService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Neo4jGraphDao neo4jGraphDao;
    private final GraphCacheInvalidator graphCacheInvalidator;
    private final AgentConfigProperties agentConfig;
    private final NodeBlockingService nodeBlockingService;
    private final io.github.legacygraph.service.similarity.SemanticSimilarityCalculator semanticSimilarityCalculator;
    /** 可选 LLM 仲裁：注入后中间档候选会走 LLM 复审并计入 LLM 预算（graph-merge-optimization-plan.md §四.4.2） */
    private final GraphMergeAgent graphMergeAgent;

    public GraphMergeService(Neo4jGraphDao neo4jGraphDao,
                             GraphCacheInvalidator graphCacheInvalidator,
                             AgentConfigProperties agentConfig,
                             NodeBlockingService nodeBlockingService,
                             io.github.legacygraph.service.similarity.SemanticSimilarityCalculator semanticSimilarityCalculator) {
        this(neo4jGraphDao, graphCacheInvalidator, agentConfig, nodeBlockingService,
                semanticSimilarityCalculator, null);
    }

    /** 主构造函数：支持注入 GraphMergeAgent，无 LLM 时回退到规则决策 */
    public GraphMergeService(Neo4jGraphDao neo4jGraphDao,
                             GraphCacheInvalidator graphCacheInvalidator,
                             AgentConfigProperties agentConfig,
                             NodeBlockingService nodeBlockingService,
                             io.github.legacygraph.service.similarity.SemanticSimilarityCalculator semanticSimilarityCalculator,
                             GraphMergeAgent graphMergeAgent) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.graphCacheInvalidator = graphCacheInvalidator;
        this.agentConfig = agentConfig;
        this.nodeBlockingService = nodeBlockingService;
        this.semanticSimilarityCalculator = semanticSimilarityCalculator;
        this.graphMergeAgent = graphMergeAgent;
    }

    /**
     * 增强版合并候选对。
     */
    @Data
    public static class MergeCandidate {
        private String nodeAId;
        private String nodeBId;
        /** 综合相似度 (0-1) */
        private double similarityScore;
        /** 名称相似度 */
        private double nameScore;
        /** 语义相似度（改进③新增） */
        private double semanticScore;
        /** 结构邻域相似度 */
        private double structScore;
        /** 共享证据分 */
        private double evidenceScore;
        /** 运行时共现分 */
        private double runtimeCooccurrenceScore;
        /** 人工历史分 */
        private double historyScore;
    }

    // ==================== 候选生成（Blocking） ====================

    /**
     * 查找合并候选对 — 使用三通道 Blocking 避免 O(n²)（graph-merge-optimization-plan.md 改进①）。
     * <p>
     * 三通道 Blocking（由 {@link NodeBlockingService} 实现）：
     * <ul>
     *   <li>Channel A（字符级）：nodeType + normalized name 前3字符 — 现有逻辑</li>
     *   <li>Channel B（n-gram）：中文字符 bigram + 拼音首字母 — 新增，解决中文归一为空串问题</li>
     *   <li>Channel C（别名重叠）：aliasNames 交集 — 新增，替代 embedding ANN</li>
     * </ul>
     * 同一 block 内的节点才进行两两比较，候选对去重后排序。
     * </p>
     */
    public List<MergeCandidate> findMergeCandidates(String projectId, String nodeType) {
        List<GraphNode> nodes = neo4jGraphDao.queryNodes(
                projectId, null, nodeType, null, null, null, 500);
        List<GraphNode> activeNodes = nodes.stream()
                .filter(n -> n.getDeleted() == null || n.getDeleted() == 0)
                .toList();

        // 三通道 Blocking（改进①核心）
        Map<String, List<GraphNode>> blocks = nodeBlockingService.buildBlocks(activeNodes, nodeType);

        List<MergeCandidate> candidates = new ArrayList<>();
        // 候选对去重：同一 (nodeAId, nodeBId) 只保留最高分
        Map<String, MergeCandidate> dedupMap = new HashMap<>();

        for (List<GraphNode> block : blocks.values()) {
            for (int i = 0; i < block.size(); i++) {
                for (int j = i + 1; j < block.size(); j++) {
                    GraphNode nodeA = block.get(i);
                    GraphNode nodeB = block.get(j);
                    if (Objects.equals(nodeA.getId(), nodeB.getId())) continue;

                    String pairKey = pairKey(nodeA.getId(), nodeB.getId());
                    if (dedupMap.containsKey(pairKey)) continue; // 已评分过

                    MergeCandidate candidate = scoreCandidate(projectId, nodeA, nodeB);
                    if (candidate.getSimilarityScore() > 0.15) {
                        candidates.add(candidate);
                        dedupMap.put(pairKey, candidate);
                    }
                }
            }
        }

        // 跨 block 的模糊匹配兜底（sourceType 相同但名称有细微差异，50 邻居滑动）
        for (int i = 0; i < activeNodes.size(); i++) {
            for (int j = i + 1; j < activeNodes.size() && j < i + 50; j++) {
                GraphNode nodeA = activeNodes.get(i);
                GraphNode nodeB = activeNodes.get(j);
                if (Objects.equals(nodeA.getSourceType(), nodeB.getSourceType())
                        && !Objects.equals(nodeA.getNodeKey(), nodeB.getNodeKey())) {
                    String pairKey = pairKey(nodeA.getId(), nodeB.getId());
                    if (dedupMap.containsKey(pairKey)) continue;

                    double nameScore = calculateNameScore(nodeA.getNodeName(), nodeB.getNodeName());
                    if (nameScore > 0.6) {
                        MergeCandidate candidate = scoreCandidate(projectId, nodeA, nodeB);
                        candidates.add(candidate);
                        dedupMap.put(pairKey, candidate);
                    }
                }
            }
        }

        candidates.sort((a, b) -> Double.compare(b.getSimilarityScore(), a.getSimilarityScore()));
        log.info("Found {} merge candidates for project={}, nodeType={} ({} nodes in {} blocks)",
                candidates.size(), projectId, nodeType, activeNodes.size(), blocks.size());
        return candidates;
    }

    /** 生成候选对去重 key（id 较小者在前） */
    private String pairKey(String idA, String idB) {
        return idA.compareTo(idB) <= 0 ? idA + "|" + idB : idB + "|" + idA;
    }

    /**
     * 对候选对进行多证据评分（改进③④：6 维评分 + 类型感知权重）。
     * <p>
     * 6 维：name + semantic + struct + evidence + runtime + history。
     * 业务域类型（BusinessDomain/Process/Object/Rule/Role）走语义优先权重，
     * 其他类型走结构优先权重（通过 scoreWeightsByType 配置）。
     * </p>
     */
    private MergeCandidate scoreCandidate(String projectId, GraphNode nodeA, GraphNode nodeB) {
        MergeCandidate c = new MergeCandidate();
        c.setNodeAId(nodeA.getId());
        c.setNodeBId(nodeB.getId());

        // 1. 名称相似度
        c.setNameScore(calculateNameScore(nodeA.getNodeName(), nodeB.getNodeName()));

        // 2. 语义相似度（改进③新增，EmbeddingModel 不可用时降级为 0.0）
        c.setSemanticScore(semanticSimilarityCalculator.compute(nodeA, nodeB));

        // 3. 结构邻域相似度（共享邻居比例）
        c.setStructScore(calculateStructScore(projectId, nodeA.getId(), nodeB.getId()));

        // 4. 共享证据分
        c.setEvidenceScore(calculateSharedEvidenceScore(nodeA, nodeB));

        // 5. 运行时共现分
        c.setRuntimeCooccurrenceScore(calculateRuntimeScore(nodeA, nodeB));

        // 6. 人工历史分（简化：同项目同类型且同名接近）
        c.setHistoryScore(c.getNameScore() > 0.7 ? 0.3 : 0.0);

        // 加权综合 — 类型感知权重（改进④）
        AgentConfigProperties.MergeConfig cfg = agentConfig.getMerge();
        AgentConfigProperties.ScoreWeights typeWeights = cfg.weightsFor(nodeA.getNodeType());
        if (typeWeights != null) {
            // 业务域类型：使用 scoreWeightsByType 中的权重
            c.setSimilarityScore(
                    typeWeights.getName() * c.getNameScore()
                    + typeWeights.getSemantic() * c.getSemanticScore()
                    + typeWeights.getStruct() * c.getStructScore()
                    + typeWeights.getEvidence() * c.getEvidenceScore()
                    + typeWeights.getRuntime() * c.getRuntimeCooccurrenceScore()
                    + typeWeights.getHistory() * c.getHistoryScore()
            );
        } else {
            // 默认类型：使用全局权重（含 scoreSemanticWeight）
            c.setSimilarityScore(
                    cfg.getScoreNameWeight() * c.getNameScore()
                    + cfg.getScoreSemanticWeight() * c.getSemanticScore()
                    + cfg.getScoreStructWeight() * c.getStructScore()
                    + cfg.getScoreEvidenceWeight() * c.getEvidenceScore()
                    + cfg.getScoreRuntimeWeight() * c.getRuntimeCooccurrenceScore()
                    + cfg.getScoreHistoryWeight() * c.getHistoryScore()
            );
        }

        return c;
    }

    /**
     * 公开：根据评分候选对重新评分（供外部 Controller 复用）。
     */
    public MergeCandidate scoreCandidatePublic(String projectId, GraphNode nodeA, GraphNode nodeB) {
        return scoreCandidate(projectId, nodeA, nodeB);
    }

    /**
     * 公开：根据节点 id 查询节点（供外部 Controller 复用，避免重复注入 DAO）。
     */
    public GraphNode findNodeByIdPublic(String nodeId) {
        return neo4jGraphDao.findNodeById(nodeId).orElse(null);
    }

    // ==================== 名称相似度 ====================

    public double calculateNameScore(String name1, String name2) {
        if (name1 == null || name2 == null || name1.isEmpty() || name2.isEmpty()) {
            return 0.0;
        }
        String lower1 = name1.toLowerCase().trim();
        String lower2 = name2.toLowerCase().trim();
        if (lower1.equals(lower2)) return 1.0;

        int lcsLength = longestCommonSubsequence(lower1, lower2);
        double lcsScore = (2.0 * lcsLength) / (lower1.length() + lower2.length());

        int minLen = Math.min(lower1.length(), lower2.length());
        int prefixLen = commonPrefix(lower1, lower2, minLen);
        int suffixLen = commonSuffix(lower1, lower2, minLen);
        double prefixScore = (double) prefixLen / minLen;
        double suffixScore = (double) suffixLen / minLen;

        return lcsScore * 0.6 + prefixScore * 0.25 + suffixScore * 0.15;
    }

    // ==================== 结构邻域相似度 ====================

    /**
     * 计算两个节点的邻居重叠度（共享邻居 / 总邻居）。
     * 通过查询 Neo4j 中与两节点相邻的节点集合来计算 Jaccard 相似度。
     */
    private double calculateStructScore(String projectId, String nodeAId, String nodeBId) {
        Set<String> neighborsA = getNeighborIds(projectId, nodeAId);
        Set<String> neighborsB = getNeighborIds(projectId, nodeBId);
        if (neighborsA.isEmpty() && neighborsB.isEmpty()) return 1.0; // 都孤立
        if (neighborsA.isEmpty() || neighborsB.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(neighborsA);
        intersection.retainAll(neighborsB);
        Set<String> union = new HashSet<>(neighborsA);
        union.addAll(neighborsB);

        return (double) intersection.size() / union.size();
    }

    private Set<String> getNeighborIds(String projectId, String nodeId) {
        // B-S3: 旧实现对每个 EdgeType 执行 queryEdges(projectId, null, 0.0, null, 50)
        // （查全项目边前 50 条）再 Java 端按 nodeId 过滤，既不是目标节点邻居又有 N+1 放大。
        // 改为单次 Cypher 直查目标节点的邻居 id。
        return neo4jGraphDao.findNeighborNodeIds(projectId, nodeId);
    }

    // ==================== 共享证据分 ====================

    private double calculateSharedEvidenceScore(GraphNode nodeA, GraphNode nodeB) {
        String evA = nodeA.getEvidenceIds();
        String evB = nodeB.getEvidenceIds();
        if (evA == null || evA.isBlank() || evB == null || evB.isBlank()) return 0.0;

        Set<String> idsA = new HashSet<>(Arrays.asList(evA.split(",")));
        Set<String> idsB = new HashSet<>(Arrays.asList(evB.split(",")));

        Set<String> shared = new HashSet<>(idsA);
        shared.retainAll(idsB);

        return idsA.isEmpty() || idsB.isEmpty() ? 0.0
                : (double) shared.size() / Math.max(idsA.size(), idsB.size());
    }

    // ==================== 运行时共现分 ====================

    private double calculateRuntimeScore(GraphNode nodeA, GraphNode nodeB) {
        boolean aRuntime = Boolean.TRUE.equals(nodeA.getRuntimeVerified());
        boolean bRuntime = Boolean.TRUE.equals(nodeB.getRuntimeVerified());
        if (aRuntime && bRuntime) return 0.5;
        if (aRuntime || bRuntime) return 0.2;
        return 0.0;
    }

    // ==================== 决策 ====================

    /**
     * 证据化决策：根据多证据评分决定 MERGE / REVIEW / REJECT。
     * 中等分数的候选应交给 LLM（GraphMergeAgent）做进一步判断。
     */
    public GraphMergeDecision decideMerge(String projectId, GraphNode nodeA, GraphNode nodeB,
                                           MergeCandidate candidate) {
        double score = candidate != null ? candidate.getSimilarityScore()
                : scoreCandidate(projectId, nodeA, nodeB).getSimilarityScore();

        GraphMergeDecision decision = new GraphMergeDecision();
        decision.setCandidateA(nodeA.getId());
        decision.setCandidateB(nodeB.getId());
        decision.setScore(java.math.BigDecimal.valueOf(score));

        List<String> reasons = new ArrayList<>();
        reasons.add(String.format("综合相似度: %.2f (名称=%.2f, 语义=%.2f, 结构=%.2f, 证据=%.2f, 运行时=%.2f, 历史=%.2f)",
                score,
                candidate != null ? candidate.getNameScore() : 0,
                candidate != null ? candidate.getSemanticScore() : 0,
                candidate != null ? candidate.getStructScore() : 0,
                candidate != null ? candidate.getEvidenceScore() : 0,
                candidate != null ? candidate.getRuntimeCooccurrenceScore() : 0,
                candidate != null ? candidate.getHistoryScore() : 0));

        // AI 来源的合并必须 REVIEW
        boolean bothAi = "AI_INFERENCE".equals(nodeA.getSourceType())
                && "AI_INFERENCE".equals(nodeB.getSourceType());
        // 冲突证据提示
        boolean conflictingSource = !Objects.equals(nodeA.getSourceType(), nodeB.getSourceType());

        double autoMergeThreshold = agentConfig.getMerge().getAutoMergeThreshold();
        double reviewThreshold = agentConfig.getMerge().getReviewThreshold();

        if (score >= autoMergeThreshold && !bothAi && !conflictingSource) {
            decision.setDecision(GraphMergeDecision.Decision.AUTO_MERGE);
            reasons.add("高综合相似度，已自动合并");
        } else if (score >= reviewThreshold) {
            decision.setDecision(GraphMergeDecision.Decision.REVIEW);
            reasons.add("中综合相似度，建议人工审核");
            if (bothAi) reasons.add("双方均来自AI推断，需人工确认");
            if (conflictingSource) reasons.add("来源类型不同，需人工确认");
        } else {
            decision.setDecision(GraphMergeDecision.Decision.REJECT);
            reasons.add("低综合相似度，不建议合并");
        }
        decision.setReasons(reasons);

        return decision;
    }

    // ==================== 执行合并（含 lineage） ====================

    /**
     * 执行合并操作 — 保留 lineage 记录。
     * <p>
     * 合并后 targetNode.properties.mergedFrom 记录被合并节点信息。
     */
    @Transactional
    public void executeMerge(String projectId, String targetNodeId, String mergeNodeId) {
        GraphNode mergeNode = neo4jGraphDao.findNodeById(mergeNodeId).orElse(null);
        GraphNode targetNode = neo4jGraphDao.findNodeById(targetNodeId).orElse(null);

        // 1. 更新所有边指向
        neo4jGraphDao.updateEdgeFromNode(mergeNodeId, targetNodeId, projectId);
        neo4jGraphDao.updateEdgeToNode(mergeNodeId, targetNodeId, projectId);

        // 2. 记录 lineage：targetNode 的 properties 中追加 mergedFrom
        if (targetNode != null && mergeNode != null) {
            recordLineage(targetNode, mergeNode);
        }

        // 3. 删除被合并节点
        if (mergeNode != null) {
            neo4jGraphDao.deleteNode(projectId, mergeNode.getVersionId(), mergeNodeId);
        }

        log.info("Merged node {} into {} in project {}", mergeNodeId, targetNodeId, projectId);

        if (graphCacheInvalidator != null) {
            graphCacheInvalidator.invalidateVersion(mergeNode != null ? mergeNode.getVersionId() : null);
            graphCacheInvalidator.invalidateProjectOverview(projectId);
        }
    }

    /**
     * 在 targetNode 上记录合并 lineage。
     */
    private void recordLineage(GraphNode targetNode, GraphNode mergeNode) {
        String existingProps = targetNode.getProperties() != null ? targetNode.getProperties() : "{}";
        ObjectNode properties = parseProperties(existingProps);

        ArrayNode mergedFrom;
        JsonNode existingLineage = properties.get("mergedFrom");
        if (existingLineage != null && existingLineage.isArray()) {
            mergedFrom = (ArrayNode) existingLineage;
        } else {
            mergedFrom = OBJECT_MAPPER.createArrayNode();
            properties.set("mergedFrom", mergedFrom);
        }

        ObjectNode lineageEntry = OBJECT_MAPPER.createObjectNode();
        lineageEntry.put("id", mergeNode.getId());
        lineageEntry.put("name", mergeNode.getNodeName());
        lineageEntry.put("type", mergeNode.getNodeType());
        lineageEntry.put("mergedAt", java.time.LocalDateTime.now().toString());
        mergedFrom.add(lineageEntry);

        targetNode.setProperties(properties.toString());
        neo4jGraphDao.updateNode(targetNode);
    }

    private ObjectNode parseProperties(String properties) {
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(properties);
            if (parsed != null && parsed.isObject()) {
                return (ObjectNode) parsed;
            }
        } catch (Exception e) {
            log.warn("Invalid node properties JSON, reset before recording lineage: {}", e.getMessage());
        }
        return OBJECT_MAPPER.createObjectNode();
    }

    // ==================== 辅助方法 ====================

    private String normalizeForBlocking(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .replaceAll("controller|service|mapper|dao|impl|entity|dto|vo", "");
    }

    private int commonPrefix(String a, String b, int maxLen) {
        int len = 0;
        for (int i = 0; i < maxLen; i++) {
            if (a.charAt(i) == b.charAt(i)) len++;
            else break;
        }
        return len;
    }

    private int commonSuffix(String a, String b, int maxLen) {
        int len = 0;
        for (int i = 0; i < maxLen; i++) {
            if (a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) len++;
            else break;
        }
        return len;
    }

    private int longestCommonSubsequence(String a, String b) {
        int m = a.length(), n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp[m][n];
    }

    // ==================== 批量合并入口（P5 调度联动） ====================

    /**
     * 批量合并入口 — 供 GraphMergeScheduler 调用（graph-merge-optimization-plan.md §4.1）。
     * <p>
     * 对指定项目按节点类型逐一执行：findMergeCandidates → decideMerge → AUTO_MERGE 直接执行；
     * REVIEW / REJECT 不执行（REVIEW 留待前端人工裁决，避免 LLM 调用失控）。
     * </p>
     * <p>LLM 调用次数上限由 {@code maxLlmCalls} 限制；当前 decideMerge 走规则评分未调 LLM，
     * 该参数作为后续接入 GraphMergeAgent 的预算控制保留。</p>
     *
     * @param projectId   项目 ID
     * @param nodeTypes   要合并的节点类型列表（null/empty 时使用业务域默认类型）
     * @param maxLlmCalls LLM 调用预算上限（≤0 表示不限制）
     * @return 合并运行统计
     */
    public MergeRunResult runMergeForProject(String projectId, List<String> nodeTypes, int maxLlmCalls) {
        MergeRunResult result = new MergeRunResult();
        result.setProjectId(projectId);
        if (nodeTypes == null || nodeTypes.isEmpty()) {
            nodeTypes = List.of("BusinessDomain", "BusinessProcess", "BusinessObject", "BusinessRule", "Role");
        }
        result.setNodeTypes(nodeTypes);

        long startMs = System.currentTimeMillis();
        int llmBudget = maxLlmCalls;
        int usedLlmCalls = 0;

        for (String nodeType : nodeTypes) {
            int candidatesForType = 0;
            int autoMergedForType = 0;
            int reviewForType = 0;
            int rejectedForType = 0;

            try {
                List<MergeCandidate> candidates = findMergeCandidates(projectId, nodeType);
                candidatesForType = candidates.size();

                for (MergeCandidate candidate : candidates) {
                    // LLM 预算耗尽后停止处理（REVIEW 候选会调 LLM 复审，AUTO/REJECT 不调）
                    if (maxLlmCalls > 0 && usedLlmCalls >= llmBudget) {
                        log.info("LLM budget exhausted (used={}/{}) for project={}, type={}, skip remaining {} candidates",
                                usedLlmCalls, maxLlmCalls, projectId, nodeType, candidates.size() - autoMergedForType - reviewForType - rejectedForType);
                        break;
                    }

                    GraphNode nodeA = neo4jGraphDao.findNodeById(candidate.getNodeAId()).orElse(null);
                    GraphNode nodeB = neo4jGraphDao.findNodeById(candidate.getNodeBId()).orElse(null);
                    if (nodeA == null || nodeB == null) continue;

                    GraphMergeDecision decision = decideMerge(projectId, nodeA, nodeB, candidate);
                    // REVIEW 候选在批跑模式下额外送 LLM 复审，并计入 LLM 预算（方案 §四.4.2）
                    if (decision.getDecision() == GraphMergeDecision.Decision.REVIEW
                            && graphMergeAgent != null
                            && (maxLlmCalls <= 0 || usedLlmCalls < llmBudget)) {
                        try {
                            GraphMergeDecision llmDecision = graphMergeAgent.decideMerge(
                                    projectId, nodeA, nodeB,
                                    candidate.getNameScore(),
                                    candidate.getSemanticScore(),
                                    candidate.getStructScore(),
                                    candidate.getStructScore(),
                                    candidate.getEvidenceScore());
                            usedLlmCalls++;
                            if (llmDecision != null && llmDecision.getDecision() != null) {
                                decision = llmDecision;
                            }
                        } catch (Exception llmEx) {
                            log.warn("LLM review failed for ({}, {}), keep rule decision: {}",
                                    candidate.getNodeAId(), candidate.getNodeBId(), llmEx.getMessage());
                        }
                    }
                    switch (decision.getDecision()) {
                        case AUTO_MERGE -> {
                            try {
                                executeMerge(projectId, candidate.getNodeAId(), candidate.getNodeBId());
                                autoMergedForType++;
                            } catch (Exception ex) {
                                log.warn("Auto-merge failed for pair ({}, {}): {}",
                                        candidate.getNodeAId(), candidate.getNodeBId(), ex.getMessage());
                            }
                        }
                        case REVIEW -> reviewForType++;
                        case REJECT -> rejectedForType++;
                    }
                }
            } catch (Exception e) {
                log.warn("runMergeForProject failed for project={}, type={}: {}",
                        projectId, nodeType, e.getMessage(), e);
            }

            result.getCandidateCountByType().put(nodeType, candidatesForType);
            result.getAutoMergedByType().put(nodeType, autoMergedForType);
            result.getReviewByType().put(nodeType, reviewForType);
            result.getRejectedByType().put(nodeType, rejectedForType);
            result.setTotalCandidates(result.getTotalCandidates() + candidatesForType);
            result.setTotalAutoMerged(result.getTotalAutoMerged() + autoMergedForType);
            result.setTotalReview(result.getTotalReview() + reviewForType);
            result.setTotalRejected(result.getTotalRejected() + rejectedForType);
        }

        result.setLlmCallsUsed(usedLlmCalls);
        result.setDurationMs(System.currentTimeMillis() - startMs);
        log.info("Merge run for project={}: {} candidates, {} auto-merged, {} review, {} rejected, LLM={}, took={}ms",
                projectId, result.getTotalCandidates(), result.getTotalAutoMerged(),
                result.getTotalReview(), result.getTotalRejected(), usedLlmCalls, result.getDurationMs());
        return result;
    }

    /**
     * 合并运行统计结果。
     */
    @Data
    public static class MergeRunResult {
        private String projectId;
        private List<String> nodeTypes;
        private int totalCandidates;
        private int totalAutoMerged;
        private int totalReview;
        private int totalRejected;
        private int llmCallsUsed;
        private long durationMs;
        /** 每类型候选数 */
        private Map<String, Integer> candidateCountByType = new LinkedHashMap<>();
        /** 每类型自动合并数 */
        private Map<String, Integer> autoMergedByType = new LinkedHashMap<>();
        /** 每类型待审核数 */
        private Map<String, Integer> reviewByType = new LinkedHashMap<>();
        /** 每类型拒绝数 */
        private Map<String, Integer> rejectedByType = new LinkedHashMap<>();
    }

}
