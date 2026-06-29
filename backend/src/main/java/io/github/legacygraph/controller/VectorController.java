package io.github.legacygraph.controller;

import io.github.legacygraph.common.Result;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.service.VectorRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 向量检索控制器
 * 提供语义检索和相似节点发现功能
 */
@RestController
@RequestMapping("/lg/vector/projects/{projectId}")
@Tag(name = "向量检索", description = "语义相似度检索、相似节点发现")
public class VectorController {

    private final VectorRetrievalService vectorRetrievalService;

    public VectorController(VectorRetrievalService vectorRetrievalService) {
        this.vectorRetrievalService = vectorRetrievalService;
    }

    @PostMapping("/upsert")
    @Operation(summary = "批量upsert向量文档", description = "批量向量化并存储文档/代码片段")
    public Result<Void> batchUpsert(
            @PathVariable String projectId,
            @RequestBody List<VectorDocument> documents) {
        vectorRetrievalService.batchUpsertVectors(projectId, documents);
        return Result.success();
    }

    @PostMapping("/search")
    @Operation(summary = "语义相似度检索", description = "根据查询文本检索语义相似的文档片段")
    public Result<List<VectorDocument>> semanticSearch(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "10") int topK,
            @RequestParam(required = false) String chunkType,
            @RequestBody String query) {
        List<VectorDocument> results = vectorRetrievalService.semanticSearch(
            projectId, null, query, topK, chunkType
        );
        return Result.success(results);
    }

    @GetMapping("/similar-nodes")
    @Operation(summary = "查找相似节点", description = "根据节点名称查找可能重复的节点")
    public Result<List<GraphNode>> findSimilarNodes(
            @PathVariable String projectId,
            @RequestParam String nodeName,
            @RequestParam(defaultValue = "0.85") double threshold) {
        List<GraphNode> results = vectorRetrievalService.findSimilarNodes(
            projectId, null, nodeName, threshold
        );
        return Result.success(results);
    }
}
