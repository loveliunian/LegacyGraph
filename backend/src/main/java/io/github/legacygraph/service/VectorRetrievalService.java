package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 向量检索服务 - 占位符实现（Spring AI 已禁用）
 * 所有方法返回默认值并记录警告日志
 */
@Slf4j
@Service
public class VectorRetrievalService {

    public void batchUpsertVectors(Long projectId, List<VectorDocument> documents) {
        log.warn("VectorRetrievalService.batchUpsertVectors is a placeholder - Spring AI disabled");
    }

    public List<VectorDocument> semanticSearch(Long projectId, String query, int topK, String chunkType) {
        log.warn("VectorRetrievalService.semanticSearch is a placeholder - Spring AI disabled");
        return Collections.emptyList();
    }

    public List<GraphNode> findSimilarNodes(String projectId, String nodeName, double threshold) {
        log.warn("VectorRetrievalService.findSimilarNodes is a placeholder - Spring AI disabled");
        return Collections.emptyList();
    }
}
