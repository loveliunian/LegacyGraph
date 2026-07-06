package io.github.legacygraph.graphify;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Graphify 导入快照，用于版本差异对比。
 *
 * @param projectId       项目ID
 * @param versionId       扫描版本ID
 * @param graphifyVersion Graphify 工具版本
 * @param sourceCommit    源码 commit hash
 * @param importedAt      导入时间
 * @param nodeKeys        导入的节点键集合
 * @param edgeKeys        导入的边键集合
 */
public record GraphifyImportSnapshot(
    String projectId,
    String versionId,
    String graphifyVersion,
    String sourceCommit,
    LocalDateTime importedAt,
    Set<String> nodeKeys,
    Set<String> edgeKeys
) {
    public GraphifyImportSnapshot {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId 不能为空");
        }
        if (nodeKeys == null) nodeKeys = Set.of();
        if (edgeKeys == null) edgeKeys = Set.of();
    }
}
