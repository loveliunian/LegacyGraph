package io.github.legacygraph.graphify;

import java.util.Set;

/**
 * Graphify 版本差异结果。
 *
 * @param addedNodes          新增的节点键
 * @param removedNodes        移除的节点键
 * @param addedEdges          新增的边键
 * @param removedEdges        移除的边键
 * @param sameGraphifyVersion Graphify 版本是否相同
 * @param sameSourceCommit    源码 commit 是否相同
 * @param driftType           漂移类型
 */
public record GraphifyDiff(
    Set<String> addedNodes,
    Set<String> removedNodes,
    Set<String> addedEdges,
    Set<String> removedEdges,
    boolean sameGraphifyVersion,
    boolean sameSourceCommit,
    DriftType driftType
) {
    public enum DriftType {
        /** 无漂移 */
        NONE,
        /** Graphify 版本升级导致的漂移 */
        GRAPHIFY_UPGRADE,
        /** 源码变化导致的漂移 */
        SOURCE_CODE_CHANGE,
        /** 两者都变化 */
        BOTH_CHANGED,
        /** 无法确定（版本和commit都相同但有变化，可能是映射规则变化） */
        UNKNOWN
    }

    public GraphifyDiff {
        if (addedNodes == null) addedNodes = Set.of();
        if (removedNodes == null) removedNodes = Set.of();
        if (addedEdges == null) addedEdges = Set.of();
        if (removedEdges == null) removedEdges = Set.of();
    }

    /**
     * 判断是否存在漂移。
     */
    public boolean hasDrift() {
        return !addedNodes.isEmpty() || !removedNodes.isEmpty()
            || !addedEdges.isEmpty() || !removedEdges.isEmpty();
    }

    /**
     * 获取漂移描述。
     */
    public String getDriftDescription() {
        if (!hasDrift()) return "无变化";
        return switch (driftType) {
            case GRAPHIFY_UPGRADE -> "Graphify 版本升级导致的图谱漂移";
            case SOURCE_CODE_CHANGE -> "源码变化导致的图谱漂移";
            case BOTH_CHANGED -> "Graphify 版本和源码都发生变化";
            case NONE, UNKNOWN -> "图谱发生变化";
        };
    }
}
