package io.github.legacygraph.graphify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Graphify 版本差异对比服务。
 * <p>
 * 支持同项目不同扫描版本之间的差异对比，区分源码变化、Graphify 版本变化和映射规则变化。
 * </p>
 */
@Slf4j
@Service
public class GraphifyDiffService {

    /**
     * 对比两个导入快照的差异。
     *
     * @param oldSnapshot 旧版本快照
     * @param newSnapshot 新版本快照
     * @return 差异结果
     * @throws IllegalArgumentException 如果两个快照不属于同一项目
     */
    public GraphifyDiff diff(GraphifyImportSnapshot oldSnapshot, GraphifyImportSnapshot newSnapshot) {
        if (oldSnapshot == null || newSnapshot == null) {
            throw new IllegalArgumentException("快照不能为空");
        }
        if (!oldSnapshot.projectId().equals(newSnapshot.projectId())) {
            throw new IllegalArgumentException(
                String.format("快照必须属于同一项目: old=%s, new=%s",
                    oldSnapshot.projectId(), newSnapshot.projectId()));
        }

        // 计算节点差异
        Set<String> addedNodes = new HashSet<>(newSnapshot.nodeKeys());
        addedNodes.removeAll(oldSnapshot.nodeKeys());

        Set<String> removedNodes = new HashSet<>(oldSnapshot.nodeKeys());
        removedNodes.removeAll(newSnapshot.nodeKeys());

        // 计算边差异
        Set<String> addedEdges = new HashSet<>(newSnapshot.edgeKeys());
        addedEdges.removeAll(oldSnapshot.edgeKeys());

        Set<String> removedEdges = new HashSet<>(oldSnapshot.edgeKeys());
        removedEdges.removeAll(newSnapshot.edgeKeys());

        // 判断版本和 commit 是否相同
        boolean sameGraphifyVersion = equalsOrNull(oldSnapshot.graphifyVersion(), newSnapshot.graphifyVersion());
        boolean sameSourceCommit = equalsOrNull(oldSnapshot.sourceCommit(), newSnapshot.sourceCommit());

        // 确定漂移类型
        GraphifyDiff.DriftType driftType = determineDriftType(
            sameGraphifyVersion, sameSourceCommit,
            addedNodes, removedNodes, addedEdges, removedEdges);

        log.info("Graphify diff: project={}, oldVersion={}, newVersion={}, " +
                "addedNodes={}, removedNodes={}, addedEdges={}, removedEdges={}, driftType={}",
            oldSnapshot.projectId(), oldSnapshot.versionId(), newSnapshot.versionId(),
            addedNodes.size(), removedNodes.size(), addedEdges.size(), removedEdges.size(), driftType);

        return new GraphifyDiff(
            addedNodes, removedNodes,
            addedEdges, removedEdges,
            sameGraphifyVersion, sameSourceCommit,
            driftType);
    }

    private GraphifyDiff.DriftType determineDriftType(
            boolean sameGraphifyVersion, boolean sameSourceCommit,
            Set<String> addedNodes, Set<String> removedNodes,
            Set<String> addedEdges, Set<String> removedEdges) {

        boolean noChange = addedNodes.isEmpty() && removedNodes.isEmpty()
            && addedEdges.isEmpty() && removedEdges.isEmpty();
        if (noChange) return GraphifyDiff.DriftType.NONE;

        if (sameSourceCommit && !sameGraphifyVersion) return GraphifyDiff.DriftType.GRAPHIFY_UPGRADE;
        if (!sameSourceCommit && sameGraphifyVersion) return GraphifyDiff.DriftType.SOURCE_CODE_CHANGE;
        if (!sameSourceCommit && !sameGraphifyVersion) return GraphifyDiff.DriftType.BOTH_CHANGED;
        return GraphifyDiff.DriftType.UNKNOWN;
    }

    private boolean equalsOrNull(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
