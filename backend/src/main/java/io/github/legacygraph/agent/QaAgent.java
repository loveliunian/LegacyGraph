package io.github.legacygraph.agent;

import io.github.legacygraph.dto.QaAnswer;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QaAgent - 自然语言知识库问答（RAG）
 *
 * <p>流程：规则过滤 → 向量召回（文档片段）→ 图邻域扩展（相似节点）→ 上下文拼装 → LLM 生成。
 * 回答必须返回证据列表、相关节点和置信度，遵循"AI 不能直接作为事实源"的设计原则。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaAgent {

    private final LlmGateway llmGateway;

    private final VectorRetrievalService vectorRetrievalService;

    private final Neo4jGraphDao neo4jGraphDao;

    /** 文档片段召回数量 */
    private static final int DOC_TOP_K = 5;
    /** 相似节点召回阈值 */
    private static final double NODE_SIM_THRESHOLD = 0.0;
    /** 每个命中节点扩展的一跳边上限 */
    private static final int GRAPH_EDGE_TOP_K = 5;

    /**
     * 回答自然语言问题
     */
    public QaAnswer answer(String projectId, String versionId, String question) {
        if (question == null || question.isBlank()) {
            QaAnswer empty = new QaAnswer();
            empty.setAnswer("问题为空，无法回答。");
            empty.setConfidence(0.0);
            return empty;
        }

        // 1. 向量召回：相关文档片段
        List<VectorDocument> docs = safeSemanticSearch(projectId, versionId, question);

        // 2. 图邻域：与问题语义相似的节点
        List<GraphNode> nodes = safeFindSimilarNodes(projectId, versionId, question);
        GraphNeighborhood neighborhood = safeExpandGraphNeighborhood(projectId, versionId, nodes);

        // 3. 拼装上下文 + 收集证据明细
        List<QaAnswer.EvidenceItem> evidences = new ArrayList<>();
        String context = buildContext(docs, nodes, neighborhood, evidences);

        // 4. LLM 生成
        Map<String, String> variables = new HashMap<>();
        variables.put("question", question);
        variables.put("context", context.isBlank() ? "（未检索到相关上下文）" : context);

        QaAnswer answer = llmGateway.callWithTemplate(projectId, "qa-answer", variables, QaAnswer.class);
        if (answer == null) {
            answer = new QaAnswer();
            answer.setAnswer("现有图谱信息不足以回答。");
            answer.setConfidence(0.0);
        }

        // 5. 回填服务端检索到的证据明细（供前端展示与跳转）
        answer.setEvidences(evidences);
        return answer;
    }

    /** 拼装供 LLM 使用的上下文文本，并同步填充结构化证据明细 */
    private String buildContext(List<VectorDocument> docs, List<GraphNode> nodes,
                                GraphNeighborhood neighborhood,
                                List<QaAnswer.EvidenceItem> evidences) {
        StringBuilder sb = new StringBuilder();

        if (!nodes.isEmpty()) {
            sb.append("## 图谱节点\n");
            for (GraphNode node : nodes) {
                String ref = node.getNodeKey() != null ? node.getNodeKey() : node.getId();
                sb.append("- [").append(node.getNodeType()).append("] ")
                        .append(node.getNodeName() != null ? node.getNodeName() : "")
                        .append(" (key=").append(ref).append(")");
                if (node.getDescription() != null && !node.getDescription().isBlank()) {
                    sb.append(" — ").append(node.getDescription());
                }
                sb.append("\n");

                QaAnswer.EvidenceItem ev = new QaAnswer.EvidenceItem();
                ev.setSourceKind("GRAPH_NODE");
                ev.setRef(ref);
                ev.setTitle(node.getNodeName());
                ev.setExcerpt(node.getDescription());
                ev.setSourcePath(node.getSourcePath());
                ev.setScore(node.getConfidence() != null ? node.getConfidence().doubleValue() : null);
                evidences.add(ev);
            }
        }

        if (neighborhood != null && (!neighborhood.nodes().isEmpty() || !neighborhood.edges().isEmpty())) {
            sb.append("\n## 图谱一跳邻域\n");
            Map<String, GraphNode> nodeById = new LinkedHashMap<>();
            for (GraphNode node : nodes) {
                nodeById.put(node.getId(), node);
            }
            for (GraphNode node : neighborhood.nodes()) {
                nodeById.put(node.getId(), node);
                String ref = node.getNodeKey() != null ? node.getNodeKey() : node.getId();
                sb.append("- 邻接节点 [").append(node.getNodeType()).append("] ")
                        .append(node.getNodeName() != null ? node.getNodeName() : "")
                        .append(" (key=").append(ref).append(")");
                if (node.getDescription() != null && !node.getDescription().isBlank()) {
                    sb.append(" — ").append(node.getDescription());
                }
                sb.append("\n");

                QaAnswer.EvidenceItem ev = new QaAnswer.EvidenceItem();
                ev.setSourceKind("GRAPH_NODE");
                ev.setRef(ref);
                ev.setTitle(node.getNodeName());
                ev.setExcerpt(node.getDescription());
                ev.setSourcePath(node.getSourcePath());
                ev.setScore(node.getConfidence() != null ? node.getConfidence().doubleValue() : null);
                evidences.add(ev);
            }
            for (GraphEdge edge : neighborhood.edges()) {
                GraphNode from = nodeById.get(edge.getFromNodeId());
                GraphNode to = nodeById.get(edge.getToNodeId());
                String fromName = from != null ? from.getNodeName() : edge.getFromNodeId();
                String toName = to != null ? to.getNodeName() : edge.getToNodeId();
                sb.append("- 关系 ").append(fromName)
                        .append(" -[").append(edge.getEdgeType()).append("]-> ")
                        .append(toName)
                        .append(" (status=").append(edge.getStatus()).append(")\n");

                QaAnswer.EvidenceItem ev = new QaAnswer.EvidenceItem();
                ev.setSourceKind("GRAPH_EDGE");
                ev.setRef(edge.getId() != null ? edge.getId() : edge.getEdgeKey());
                ev.setTitle(edge.getEdgeType());
                ev.setExcerpt(fromName + " -> " + toName);
                ev.setScore(edge.getConfidence() != null ? edge.getConfidence().doubleValue() : null);
                evidences.add(ev);
            }
        }

        if (!docs.isEmpty()) {
            sb.append("\n## 文档片段\n");
            int idx = 0;
            for (VectorDocument doc : docs) {
                String ref = "chunk#" + idx;
                sb.append("- ").append(ref).append(": ")
                        .append(truncate(doc.getContent(), 500)).append("\n");

                QaAnswer.EvidenceItem ev = new QaAnswer.EvidenceItem();
                ev.setSourceKind("DOC_CHUNK");
                ev.setRef(ref);
                ev.setTitle(doc.getChunkType());
                ev.setExcerpt(truncate(doc.getContent(), 500));
                ev.setSourcePath(doc.getSourceUri());
                evidences.add(ev);
                idx++;
            }
        }

        return sb.toString();
    }

    private List<VectorDocument> safeSemanticSearch(String projectId, String versionId, String question) {
        try {
            List<VectorDocument> docs = vectorRetrievalService.semanticSearch(
                    projectId, versionId, question, DOC_TOP_K, null);
            return dedupeDocs(docs);
        } catch (Exception e) {
            log.warn("QA semantic search failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<GraphNode> safeFindSimilarNodes(String projectId, String versionId, String question) {
        try {
            return vectorRetrievalService.findSimilarNodes(projectId, versionId, question, NODE_SIM_THRESHOLD);
        } catch (Exception e) {
            log.warn("QA find similar nodes failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private GraphNeighborhood safeExpandGraphNeighborhood(String projectId, String versionId, List<GraphNode> seeds) {
        if (seeds == null || seeds.isEmpty() || neo4jGraphDao == null) {
            return new GraphNeighborhood(List.of(), List.of());
        }
        try {
            Map<String, GraphNode> seedById = new LinkedHashMap<>();
            for (GraphNode seed : seeds) {
                if (seed.getId() != null) {
                    seedById.put(seed.getId(), seed);
                }
            }
            Map<String, GraphEdge> edgeById = new LinkedHashMap<>();
            Set<String> neighborIds = new LinkedHashSet<>();
            for (String seedId : seedById.keySet()) {
                List<GraphEdge> edges = neo4jGraphDao.queryEdges(
                        projectId, versionId, null, null, seedId, null, null, GRAPH_EDGE_TOP_K);
                if (edges == null) {
                    continue;
                }
                for (GraphEdge edge : edges) {
                    String edgeId = edge.getId() != null ? edge.getId()
                            : edge.getFromNodeId() + "->" + edge.getToNodeId() + ":" + edge.getEdgeType();
                    edgeById.putIfAbsent(edgeId, edge);
                    if (edge.getFromNodeId() != null && !seedById.containsKey(edge.getFromNodeId())) {
                        neighborIds.add(edge.getFromNodeId());
                    }
                    if (edge.getToNodeId() != null && !seedById.containsKey(edge.getToNodeId())) {
                        neighborIds.add(edge.getToNodeId());
                    }
                }
            }
            List<GraphNode> neighbors = neighborIds.isEmpty()
                    ? List.of()
                    : neo4jGraphDao.findNodesByIds(new ArrayList<>(neighborIds));
            if (neighbors == null) {
                neighbors = List.of();
            }
            return new GraphNeighborhood(neighbors, new ArrayList<>(edgeById.values()));
        } catch (Exception e) {
            log.warn("QA graph neighborhood expansion failed: {}", e.getMessage());
            return new GraphNeighborhood(List.of(), List.of());
        }
    }

    /** 按内容去重，保持顺序（重排序的轻量版） */
    private List<VectorDocument> dedupeDocs(List<VectorDocument> docs) {
        if (docs == null) {
            return new ArrayList<>();
        }
        Map<String, VectorDocument> seen = new LinkedHashMap<>();
        for (VectorDocument doc : docs) {
            if (doc.getContent() == null) {
                continue;
            }
            seen.putIfAbsent(doc.getContent(), doc);
        }
        return new ArrayList<>(seen.values());
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record GraphNeighborhood(List<GraphNode> nodes, List<GraphEdge> edges) {
    }
}
