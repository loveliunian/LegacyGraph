package io.github.legacygraph.dto;

import lombok.Data;

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

    /**
     * 从图谱节点构建证据项
     */
    public static EvidenceItem fromGraphNode(String nodeId, String nodeName, String nodeType,
                                              String sourcePath, String description) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceKind("GRAPH_NODE");
        item.setRef(nodeId);
        item.setTitle(nodeName);
        item.setExcerpt(description != null ? 
            (description.length() > 200 ? description.substring(0, 200) + "..." : description) : 
            "");
        item.setJumpUrl("/graph?node=" + nodeId);
        item.setNodeType(nodeType);
        item.setSourceFile(sourcePath);
        item.setRetrievalMethod("GRAPH_TRAVERSAL");
        return item;
    }

    /**
     * 从文档分块构建证据项
     */
    public static EvidenceItem fromDocChunk(String chunkId, String sourceUri, String content,
                                            String chunkType) {
        EvidenceItem item = new EvidenceItem();
        item.setSourceKind("DOC_CHUNK");
        item.setRef(chunkId);
        item.setTitle(sourceUri);
        item.setExcerpt(content != null ? 
            (content.length() > 200 ? content.substring(0, 200) + "..." : content) : 
            "");
        item.setJumpUrl("/documents/" + chunkId);
        item.setNodeType(chunkType);
        item.setSourceFile(sourceUri);
        item.setRetrievalMethod("VECTOR");
        return item;
    }
}
