package io.github.legacygraph.federation;

import io.github.legacygraph.common.EdgeType;
import java.util.Set;

/**
 * 跨仓库关系候选，表示两个不同项目/仓库之间的潜在关联
 */
public record CrossRepositoryLinkCandidate(
    String fromProjectId,       // 源项目ID
    String fromNodeKey,         // 源节点键
    String toProjectId,         // 目标项目ID
    String toNodeKey,           // 目标节点键
    EdgeType edgeType,          // 边类型（AFFECTS, CALLS_EXTERNAL, TRIGGERS）
    double confidence,          // 置信度（0.0-1.0）
    String reason,              // 生成原因
    Set<String> evidenceIds     // 证据ID集合
) {
    public CrossRepositoryLinkCandidate {
        if (fromProjectId == null || fromProjectId.isBlank()) {
            throw new IllegalArgumentException("fromProjectId 不能为空");
        }
        if (fromNodeKey == null || fromNodeKey.isBlank()) {
            throw new IllegalArgumentException("fromNodeKey 不能为空");
        }
        if (toProjectId == null || toProjectId.isBlank()) {
            throw new IllegalArgumentException("toProjectId 不能为空");
        }
        if (toNodeKey == null || toNodeKey.isBlank()) {
            throw new IllegalArgumentException("toNodeKey 不能为空");
        }
        if (edgeType == null) {
            throw new IllegalArgumentException("edgeType 不能为空");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence 必须在 0.0-1.0 之间");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        if (evidenceIds == null) {
            evidenceIds = Set.of();
        }
        
        // 跨仓库关系必须有共享表/API/topic/外部系统证据
        if (evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("跨仓库候选关系必须提供证据");
        }
    }
    
    /**
     * 生成唯一的候选标识符
     */
    public String toCandidateKey() {
        return String.format("%s:%s->%s:%s:%s", 
            fromProjectId, fromNodeKey, toProjectId, toNodeKey, edgeType);
    }
}
