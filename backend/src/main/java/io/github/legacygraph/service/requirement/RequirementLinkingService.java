package io.github.legacygraph.service.requirement;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.RequirementAnalysis;
import io.github.legacygraph.dto.RequirementItemDTO;
import io.github.legacygraph.dto.requirement.LinkedTarget;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.TerminologyMapping;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.TerminologyMappingRepository;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import io.github.legacygraph.util.IdUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求-图谱链接服务（Task 7）。
 * <p>确定性优先的三步链接策略，将需求条目链接到图谱中的目标节点并创建 AFFECTS 边：
 * <ol>
 *   <li>显式引用匹配：从需求文本提取 schema.table.column / FQN / URL 路径 / 文件路径，
 *       在图谱中按 nodeKey / nodeName 精确查找 → CONFIRMED</li>
 *   <li>术语映射：查询项目术语映射表（如「结算单」→ Table:lg_settlement），
 *       命中则按目标词在图谱中查找 → CONFIRMED</li>
 *   <li>向量语义匹配：使用 {@link VectorRetrievalService} 查询语义相似节点
 *       （相似度 &gt; 0.80）→ PENDING_CONFIRM</li>
 * </ol>
 * 低于阈值的不创建边。
 * </p>
 */
@Slf4j
@Service
public class RequirementLinkingService {

    /** 语义匹配相似度阈值（高于此值才创建边） */
    static final double SEMANTIC_THRESHOLD = 0.80;

    /** 语义匹配召回数量上限 */
    private static final int SEMANTIC_TOP_K = 10;

    /** 显式引用正则：schema.table.column（3 段点分隔标识符） */
    private static final Pattern SCHEMA_TABLE_COLUMN =
            Pattern.compile("(?<![\\w.])([a-zA-Z_]\\w*)\\.([a-zA-Z_]\\w*)\\.([a-zA-Z_]\\w*)(?![\\w.])");
    /** 显式引用正则：Java FQN（小写包名.大写类名，至少 2 段） */
    private static final Pattern FQN =
            Pattern.compile("(?<![\\w.])([a-z][\\w]*(?:\\.[a-zA-Z_][\\w]*)+)(?![\\w.])");
    /** 显式引用正则：URL 路径（/api/v1/orders） */
    private static final Pattern URL_PATH =
            Pattern.compile("(?<![\\w])(/[a-zA-Z][\\w\\-/]*)");
    /** 显式引用正则：文件路径（含 / 和扩展名） */
    private static final Pattern FILE_PATH =
            Pattern.compile("([\\w\\-/]+/)+[\\w\\-]+\\.[a-zA-Z]{1,8}");
    /** 显式引用正则：反引号包裹的标识符 */
    private static final Pattern BACKTICK =
            Pattern.compile("`([^`]+)`");

    private final Neo4jGraphDao neo4jGraphDao;
    private final VectorRetrievalService vectorRetrievalService;
    private final TerminologyMappingRepository terminologyMappingRepository;

    public RequirementLinkingService(Neo4jGraphDao neo4jGraphDao,
                                      VectorRetrievalService vectorRetrievalService,
                                      TerminologyMappingRepository terminologyMappingRepository) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.vectorRetrievalService = vectorRetrievalService;
        this.terminologyMappingRepository = terminologyMappingRepository;
    }

    /**
     * 将需求条目链接到图谱节点并创建 AFFECTS 边。
     *
     * @param projectId     项目 ID
     * @param versionId     扫描版本 ID（可为 null）
     * @param requirementId 需求 ID（用于定位 RequirementItem 节点）
     * @param analysis      LLM 抽取的需求分析结果
     * @return 链接目标列表（含匹配类型、置信度、状态）
     */
    public List<LinkedTarget> link(String projectId, String versionId,
                                    String requirementId, RequirementAnalysis analysis) {
        if (analysis == null || analysis.getItems() == null || analysis.getItems().isEmpty()) {
            return Collections.emptyList();
        }

        // 加载术语映射表（全局，status=ACTIVE）
        Map<String, List<String>> terminologyMap = loadTerminologyMap();

        List<LinkedTarget> allTargets = new ArrayList<>();
        for (RequirementItemDTO item : analysis.getItems()) {
            String code = item.getCode() != null ? item.getCode() : "R1";
            String text = item.getText() != null ? item.getText() : "";
            Set<String> matchedNodeIds = new HashSet<>();
            List<LinkedTarget> itemTargets = new ArrayList<>();

            // Step 1: 显式引用匹配（CONFIRMED, confidence=1.0）
            for (LinkedTarget t : matchExplicitReferences(projectId, versionId, code, text)) {
                if (t.getNodeId() != null && matchedNodeIds.add(t.getNodeId())) {
                    itemTargets.add(t);
                }
            }
            // Step 2: 术语映射匹配（CONFIRMED, confidence=0.9）
            for (LinkedTarget t : matchTerminology(projectId, versionId, code, text, terminologyMap)) {
                if (t.getNodeId() != null && matchedNodeIds.add(t.getNodeId())) {
                    itemTargets.add(t);
                }
            }
            // Step 3: 向量语义匹配（PENDING_CONFIRM, confidence=similarity）
            for (LinkedTarget t : matchSemantic(projectId, versionId, code, text)) {
                if (t.getNodeId() != null && matchedNodeIds.add(t.getNodeId())) {
                    itemTargets.add(t);
                }
            }
            allTargets.addAll(itemTargets);

            // 创建 AFFECTS 边：RequirementItem → 每个去重后的目标节点
            String itemNodeKey = "req:" + requirementId + ":" + code;
            Optional<GraphNode> itemNode = neo4jGraphDao.queryNodes(
                    projectId, versionId, NodeType.RequirementItem.name(),
                    itemNodeKey, null, null, null, 1).stream().findFirst();
            if (itemNode.isPresent()) {
                String fromNodeId = itemNode.get().getId();
                for (LinkedTarget target : itemTargets) {
                    createAffectsEdge(projectId, versionId, fromNodeId, target);
                }
            } else {
                log.warn("RequirementItem node not found for key={}, skip AFFECTS edges", itemNodeKey);
            }
        }

        log.info("Requirement linking completed: projectId={}, requirementId={}, targets={}",
                projectId, requirementId, allTargets.size());
        return allTargets;
    }

    // ==================== Step 1: 显式引用匹配 ====================

    /**
     * 从需求文本提取显式引用（schema.table.column / FQN / URL / 文件路径 / 反引号标识符），
     * 在图谱中按 nodeKey 和 nodeName 精确查找。
     */
    List<LinkedTarget> matchExplicitReferences(String projectId, String versionId,
                                                String itemCode, String text) {
        Set<String> refs = extractReferences(text);
        if (refs.isEmpty()) {
            return Collections.emptyList();
        }
        List<LinkedTarget> targets = new ArrayList<>();
        for (String ref : refs) {
            // 按 nodeKey 精确查找（nodeType=null 查所有类型）
            List<GraphNode> byKey = neo4jGraphDao.queryNodes(
                    projectId, versionId, null, ref, null, null, null, 5);
            for (GraphNode n : byKey) {
                targets.add(new LinkedTarget(n.getId(), n.getNodeKey(), n.getNodeName(),
                        n.getNodeType(), MatchType.EXACT_REFERENCE.name(),
                        BigDecimal.ONE, NodeStatus.CONFIRMED.name(), itemCode, text));
            }
            // 按 nodeName 精确查找（覆盖裸表名如 t_order）
            if (byKey.isEmpty()) {
                for (GraphNode n : findNodesByName(projectId, versionId, ref)) {
                    targets.add(new LinkedTarget(n.getId(), n.getNodeKey(), n.getNodeName(),
                            n.getNodeType(), MatchType.EXACT_REFERENCE.name(),
                            BigDecimal.ONE, NodeStatus.CONFIRMED.name(), itemCode, text));
                }
            }
        }
        return dedup(targets);
    }

    /**
     * 从需求文本提取显式引用标识符集合。
     */
    Set<String> extractReferences(String text) {
        Set<String> refs = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return refs;
        }
        // schema.table.column → 同时收集完整串和前两段
        Matcher m = SCHEMA_TABLE_COLUMN.matcher(text);
        while (m.find()) {
            refs.add(m.group(1) + "." + m.group(2) + "." + m.group(3));
            refs.add(m.group(1) + "." + m.group(2));
        }
        // FQN（小写包名.类名，至少 2 段）— 未被上面 schema.table.column 覆盖的
        m = FQN.matcher(text);
        while (m.find()) {
            refs.add(m.group(1));
        }
        // URL 路径
        m = URL_PATH.matcher(text);
        while (m.find()) {
            refs.add(m.group(1));
        }
        // 文件路径
        m = FILE_PATH.matcher(text);
        while (m.find()) {
            refs.add(m.group());
        }
        // 反引号标识符
        m = BACKTICK.matcher(text);
        while (m.find()) {
            refs.add(m.group(1).trim());
        }
        refs.removeIf(String::isBlank);
        return refs;
    }

    // ==================== Step 2: 术语映射匹配 ====================

    /**
     * 术语映射匹配：检查需求文本是否包含术语映射表中的源术语，
     * 命中则用目标词在图谱中按 nodeKey/nodeName 查找。
     */
    List<LinkedTarget> matchTerminology(String projectId, String versionId,
                                         String itemCode, String text,
                                         Map<String, List<String>> terminologyMap) {
        if (terminologyMap.isEmpty() || text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<LinkedTarget> targets = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : terminologyMap.entrySet()) {
            String sourceTerm = entry.getKey();
            if (!text.contains(sourceTerm)) {
                continue;
            }
            for (String targetTerm : entry.getValue()) {
                if (targetTerm == null || targetTerm.isBlank()) {
                    continue;
                }
                // 目标词格式 "Table:lg_settlement" → 拆分 nodeType:nodeName
                String nodeType = null;
                String nodeValue = targetTerm;
                int colonIdx = targetTerm.indexOf(':');
                if (colonIdx > 0) {
                    nodeType = targetTerm.substring(0, colonIdx);
                    nodeValue = targetTerm.substring(colonIdx + 1);
                }
                List<GraphNode> nodes = findNodeByTerm(projectId, versionId, nodeType, nodeValue);
                for (GraphNode n : nodes) {
                    targets.add(new LinkedTarget(n.getId(), n.getNodeKey(), n.getNodeName(),
                            n.getNodeType(), MatchType.TERMINOLOGY.name(),
                            BigDecimal.valueOf(0.9), NodeStatus.CONFIRMED.name(), itemCode, text));
                }
            }
        }
        return dedup(targets);
    }

    // ==================== Step 3: 向量语义匹配 ====================

    /**
     * 向量语义匹配：对需求条目文本做语义搜索，相似度 &gt; 阈值的目标标记 PENDING_CONFIRM。
     */
    List<LinkedTarget> matchSemantic(String projectId, String versionId,
                                      String itemCode, String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<VectorDocument> docs = vectorRetrievalService.semanticSearch(
                projectId, versionId, text, SEMANTIC_TOP_K, null);
        if (docs == null || docs.isEmpty()) {
            return Collections.emptyList();
        }
        // 收命中的 nodeId（VectorDocument.sourceUri 即关联节点 ID）
        List<String> nodeIds = new ArrayList<>();
        Map<String, Double> similarityById = new HashMap<>();
        for (VectorDocument doc : docs) {
            Double distance = doc.getDistance();
            if (distance == null || doc.getSourceUri() == null || doc.getSourceUri().isBlank()) {
                continue;
            }
            double similarity = 1.0 - distance;
            if (similarity >= SEMANTIC_THRESHOLD) {
                nodeIds.add(doc.getSourceUri());
                similarityById.put(doc.getSourceUri(), similarity);
            }
        }
        if (nodeIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<GraphNode> nodes = neo4jGraphDao.findNodesByIds(nodeIds);
        List<LinkedTarget> targets = new ArrayList<>();
        for (GraphNode n : nodes) {
            Double sim = similarityById.getOrDefault(n.getId(), 0.0);
            targets.add(new LinkedTarget(n.getId(), n.getNodeKey(), n.getNodeName(),
                    n.getNodeType(), MatchType.SEMANTIC.name(),
                    BigDecimal.valueOf(sim), NodeStatus.PENDING_CONFIRM.name(), itemCode, text));
        }
        return dedup(targets);
    }

    // ==================== 辅助方法 ====================

    /**
     * 加载术语映射表（status=ACTIVE），构建 sourceTerm → targetTerms 的内存 Map。
     * <p>术语映射为全局配置（无 projectId 维度），加载全表。无映射时返回空 Map。</p>
     */
    private Map<String, List<String>> loadTerminologyMap() {
        try {
            List<TerminologyMapping> all = terminologyMappingRepository.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TerminologyMapping>()
                            .eq(TerminologyMapping::getStatus, "ACTIVE")
                            .eq(TerminologyMapping::getDeleted, 0));
            Map<String, List<String>> map = new HashMap<>();
            for (TerminologyMapping tm : all) {
                if (tm.getSourceTerm() != null && tm.getTargetTerms() != null) {
                    map.put(tm.getSourceTerm(), tm.getTargetTerms());
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("Failed to load terminology mapping, skip terminology step: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 按 nodeName 精确查找节点（覆盖裸表名/类名匹配）。
     * <p>用 executeReadQuery 返回具体列（非整 Node），避免 asMap() 类型转换问题。</p>
     */
    private List<GraphNode> findNodesByName(String projectId, String versionId, String name) {
        String cypher = "MATCH (n) WHERE n.projectId=$projectId " +
                "AND ($versionId IS NULL OR n.versionId=$versionId) " +
                "AND n.nodeName=$name " +
                "RETURN n.id AS id, n.nodeKey AS nodeKey, n.nodeName AS nodeName, n.nodeType AS nodeType LIMIT 5";
        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("versionId", versionId != null ? IdUtil.normalizeId(versionId) : null);
        params.put("name", name);
        try {
            List<Map<String, Object>> rows = neo4jGraphDao.executeReadQuery(cypher, params);
            List<GraphNode> nodes = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                GraphNode node = new GraphNode();
                node.setId(asString(row.get("id")));
                node.setNodeKey(asString(row.get("nodeKey")));
                node.setNodeName(asString(row.get("nodeName")));
                node.setNodeType(asString(row.get("nodeType")));
                node.setProjectId(projectId);
                node.setVersionId(versionId);
                nodes.add(node);
            }
            return nodes;
        } catch (Exception e) {
            log.debug("findNodesByName failed for '{}': {}", name, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * 按术语查找节点：先 nodeKey 后 nodeName。
     */
    private List<GraphNode> findNodeByTerm(String projectId, String versionId,
                                           String nodeType, String nodeValue) {
        List<GraphNode> result = new ArrayList<>();
        // 按 nodeKey 精确查找
        List<GraphNode> byKey = neo4jGraphDao.queryNodes(
                projectId, versionId, nodeType, nodeValue, null, null, null, 5);
        result.addAll(byKey);
        if (result.isEmpty()) {
            result.addAll(findNodesByName(projectId, versionId, nodeValue));
        }
        return result;
    }

    /**
     * 创建 AFFECTS 边（RequirementItem → 目标节点），properties 含 matchType/confidence/status。
     */
    private void createAffectsEdge(String projectId, String versionId,
                                   String fromNodeId, LinkedTarget target) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(target.getNodeId());
        edge.setEdgeType(EdgeType.AFFECTS.name());
        edge.setEdgeKey(fromNodeId + "->AFFECTS->" + target.getNodeKey());
        edge.setSourceType(SourceType.DOC_AI.name());
        edge.setConfidence(target.getConfidence());
        edge.setStatus(target.getStatus());
        edge.setProperties(buildEdgeProperties(target));
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        try {
            neo4jGraphDao.createEdge(edge);
        } catch (Exception e) {
            log.warn("Failed to create AFFECTS edge {} -> {}: {}",
                    fromNodeId, target.getNodeId(), e.getMessage());
        }
    }

    private String buildEdgeProperties(LinkedTarget target) {
        return "{\"matchType\":\"" + target.getMatchType()
                + "\",\"confidence\":" + target.getConfidence()
                + ",\"status\":\"" + target.getStatus() + "\"}";
    }

    /** 按 nodeId 去重 */
    private List<LinkedTarget> dedup(List<LinkedTarget> targets) {
        Map<String, LinkedTarget> seen = new LinkedHashMap<>();
        for (LinkedTarget t : targets) {
            if (t.getNodeId() != null) {
                seen.putIfAbsent(t.getNodeId(), t);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** 匹配类型枚举 */
    public enum MatchType {
        EXACT_REFERENCE,
        TERMINOLOGY,
        SEMANTIC
    }
}
