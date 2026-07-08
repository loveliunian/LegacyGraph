package io.github.legacygraph.dto;

import io.github.legacygraph.dto.rag.GraphRagEvidenceCard;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 证据项 - 支持跳转链接
 */
@Data
public class EvidenceItem {

    /** 来源类型：GRAPH_NODE / DOC_CHUNK / KNOWLEDGE_CLAIM */
    private String sourceKind;

    /** 引用ID */
    private String ref;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String excerpt;

    /** 跳转链接 */
    private String jumpUrl;

    /** 节点类型（用于图标展示） */
    private String nodeType;

    /** 源文件路径 */
    private String sourceFile;

    /** 相关性分数 */
    private Double relevanceScore;

    /** 检索方法：VECTOR / KEYWORD / GRAPH_TRAVERSAL */
    private String retrievalMethod;

    /** 置信度（GraphRAG 证据卡携带，前端可忽略） */
    private Double confidence;

    /** 起始行号（GraphRAG 证据卡携带，前端可忽略） */
    private Integer startLine;

    /** 结束行号（GraphRAG 证据卡携带，前端可忽略） */
    private Integer endLine;

    /** 路径上出现的关系类型（GraphRAG 证据卡携带，前端可忽略） */
    private List<String> relationTypes;

    /**
     * 从图谱节点构建证据项
     */
    private static String graphJumpUrl(String projectId, String nodeId) {
        return "/projects/" + projectId + "/graph/unified?highlight=" + nodeId;
    }

    public static EvidenceItem fromGraphNode(String projectId, String nodeId, String nodeName,
                                              String nodeType, String sourcePath, String description) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceKind("GRAPH_NODE");
        item.setRef(nodeId);
        item.setTitle(nodeName);
        item.setExcerpt(description != null ?
            (description.length() > 200 ? description.substring(0, 200) + "..." : description) :
            "");
        item.setJumpUrl(graphJumpUrl(projectId, nodeId));
        item.setNodeType(nodeType);
        item.setSourceFile(sourcePath);
        item.setRetrievalMethod("GRAPH_TRAVERSAL");
        return item;
    }

    public static EvidenceItem fromDocChunk(String projectId, String chunkId, String sourceUri,
                                             String content, String chunkType) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceKind("DOC_CHUNK");
        item.setRef(chunkId);
        item.setTitle(sourceUri);
        item.setExcerpt(content != null ?
            (content.length() > 200 ? content.substring(0, 200) + "..." : content) :
            "");
        item.setJumpUrl("/projects/" + projectId + "/documents?highlight=" + chunkId);
        item.setNodeType(chunkType);
        item.setSourceFile(sourceUri);
        item.setRetrievalMethod("VECTOR");
        return item;
    }

    public static EvidenceItem fromGraphRagCard(String projectId, GraphRagEvidenceCard card) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceKind("GRAPH_RAG");
        String claimId = card.getClaimId();
        String nodeKey = card.getNodeKey();
        item.setRef(claimId != null && !claimId.isBlank() ? claimId : nodeKey);
        item.setTitle(nodeKey);
        String excerpt = card.getExcerpt();
        item.setExcerpt(excerpt != null
                ? (excerpt.length() > 200 ? excerpt.substring(0, 200) + "..." : excerpt)
                : "");
        item.setJumpUrl(claimId != null && !claimId.isBlank()
                ? "/projects/" + projectId + "/graph/unified?claim=" + claimId
                : graphJumpUrl(projectId, nodeKey != null ? nodeKey : ""));
        item.setNodeType(card.getSourceType());
        item.setSourceFile(card.getSourcePath());
        if (card.getConfidence() != null) {
            item.setConfidence(card.getConfidence().doubleValue());
        }
        item.setStartLine(card.getStartLine());
        item.setEndLine(card.getEndLine());
        item.setRelationTypes(card.getRelationTypes() != null
                ? new ArrayList<>(card.getRelationTypes())
                : new ArrayList<>());
        item.setRetrievalMethod("GRAPH_RAG");
        return item;
    }
}
