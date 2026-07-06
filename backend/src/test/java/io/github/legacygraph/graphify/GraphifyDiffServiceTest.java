package io.github.legacygraph.graphify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphifyDiffService 单元测试。
 */
class GraphifyDiffServiceTest {

    private GraphifyDiffService service;

    @BeforeEach
    void setUp() {
        service = new GraphifyDiffService();
    }

    @Test
    @DisplayName("对比两个快照: 新增节点和边")
    void comparesNodesAndEdgesBetweenSnapshots() {
        GraphifyImportSnapshot oldSnapshot = snapshot("proj-1", "v1",
            "0.9.7", "abc123",
            Set.of("class:A"), Set.of("CALLS:A->B"));
        GraphifyImportSnapshot newSnapshot = snapshot("proj-1", "v2",
            "0.9.7", "def456",
            Set.of("class:A", "class:B"), Set.of("CALLS:A->B", "CALLS:B->C"));

        GraphifyDiff diff = service.diff(oldSnapshot, newSnapshot);

        assertEquals(Set.of("class:B"), diff.addedNodes());
        assertTrue(diff.removedNodes().isEmpty());
        assertEquals(Set.of("CALLS:B->C"), diff.addedEdges());
        assertTrue(diff.removedEdges().isEmpty());
        assertTrue(diff.sameGraphifyVersion());
        assertFalse(diff.sameSourceCommit());
        assertEquals(GraphifyDiff.DriftType.SOURCE_CODE_CHANGE, diff.driftType());
    }

    @Test
    @DisplayName("Graphify 版本升级导致漂移")
    void detectsGraphifyUpgradeDrift() {
        GraphifyImportSnapshot oldSnapshot = snapshot("proj-1", "v1",
            "0.9.7", "abc123",
            Set.of("class:A"), Set.of("CALLS:A->B"));
        GraphifyImportSnapshot newSnapshot = snapshot("proj-1", "v2",
            "0.9.8", "abc123",
            Set.of("class:A", "class:A$inner"), Set.of("CALLS:A->B", "CONTAINS:A->A$inner"));

        GraphifyDiff diff = service.diff(oldSnapshot, newSnapshot);

        assertTrue(diff.sameSourceCommit());
        assertFalse(diff.sameGraphifyVersion());
        assertEquals(GraphifyDiff.DriftType.GRAPHIFY_UPGRADE, diff.driftType());
        assertEquals("Graphify 版本升级导致的图谱漂移", diff.getDriftDescription());
    }

    @Test
    @DisplayName("源码和 Graphify 版本都变化")
    void detectsBothChanged() {
        GraphifyImportSnapshot oldSnapshot = snapshot("proj-1", "v1",
            "0.9.7", "abc123",
            Set.of("class:A"), Set.of("CALLS:A->B"));
        GraphifyImportSnapshot newSnapshot = snapshot("proj-1", "v2",
            "0.9.8", "def456",
            Set.of("class:A", "class:C"), Set.of("CALLS:A->C"));

        GraphifyDiff diff = service.diff(oldSnapshot, newSnapshot);

        assertFalse(diff.sameSourceCommit());
        assertFalse(diff.sameGraphifyVersion());
        assertEquals(GraphifyDiff.DriftType.BOTH_CHANGED, diff.driftType());
    }

    @Test
    @DisplayName("无变化返回 NONE")
    void noChangeReturnsNone() {
        GraphifyImportSnapshot oldSnapshot = snapshot("proj-1", "v1",
            "0.9.7", "abc123",
            Set.of("class:A"), Set.of("CALLS:A->B"));
        GraphifyImportSnapshot newSnapshot = snapshot("proj-1", "v2",
            "0.9.7", "abc123",
            Set.of("class:A"), Set.of("CALLS:A->B"));

        GraphifyDiff diff = service.diff(oldSnapshot, newSnapshot);

        assertFalse(diff.hasDrift());
        assertEquals(GraphifyDiff.DriftType.NONE, diff.driftType());
        assertEquals("无变化", diff.getDriftDescription());
    }

    @Test
    @DisplayName("版本和commit都相同但有变化 -> UNKNOWN")
    void unknownDriftWhenVersionAndCommitSameButGraphChanged() {
        GraphifyImportSnapshot oldSnapshot = snapshot("proj-1", "v1",
            "0.9.7", "abc123",
            Set.of("class:A"), Set.of("CALLS:A->B"));
        GraphifyImportSnapshot newSnapshot = snapshot("proj-1", "v2",
            "0.9.7", "abc123",
            Set.of("class:A", "class:B"), Set.of("CALLS:A->B"));

        GraphifyDiff diff = service.diff(oldSnapshot, newSnapshot);

        assertTrue(diff.sameSourceCommit());
        assertTrue(diff.sameGraphifyVersion());
        assertTrue(diff.hasDrift());
        assertEquals(GraphifyDiff.DriftType.UNKNOWN, diff.driftType());
    }

    @Test
    @DisplayName("检测移除的节点和边")
    void detectsRemovedNodesAndEdges() {
        GraphifyImportSnapshot oldSnapshot = snapshot("proj-1", "v1",
            "0.9.7", "abc123",
            Set.of("class:A", "class:B", "class:C"),
            Set.of("CALLS:A->B", "CALLS:B->C"));
        GraphifyImportSnapshot newSnapshot = snapshot("proj-1", "v2",
            "0.9.7", "def456",
            Set.of("class:A"), Set.of("CALLS:A->B"));

        GraphifyDiff diff = service.diff(oldSnapshot, newSnapshot);

        assertEquals(Set.of("class:B", "class:C"), diff.removedNodes());
        assertEquals(Set.of("CALLS:B->C"), diff.removedEdges());
        assertTrue(diff.addedNodes().isEmpty());
        assertTrue(diff.addedEdges().isEmpty());
    }

    @Test
    @DisplayName("跨项目快照对比抛出异常")
    void rejectsCrossProjectDiff() {
        GraphifyImportSnapshot oldSnapshot = snapshot("proj-1", "v1",
            "0.9.7", "abc", Set.of(), Set.of());
        GraphifyImportSnapshot newSnapshot = snapshot("proj-2", "v1",
            "0.9.7", "abc", Set.of(), Set.of());

        assertThrows(IllegalArgumentException.class, () -> service.diff(oldSnapshot, newSnapshot));
    }

    @Test
    @DisplayName("空快照抛出异常")
    void rejectsNullSnapshot() {
        GraphifyImportSnapshot snap = snapshot("proj-1", "v1",
            "0.9.7", "abc", Set.of(), Set.of());

        assertThrows(IllegalArgumentException.class, () -> service.diff(null, snap));
        assertThrows(IllegalArgumentException.class, () -> service.diff(snap, null));
    }

    private GraphifyImportSnapshot snapshot(String projectId, String versionId,
            String graphifyVersion, String sourceCommit,
            Set<String> nodeKeys, Set<String> edgeKeys) {
        return new GraphifyImportSnapshot(
            projectId, versionId, graphifyVersion, sourceCommit,
            LocalDateTime.now(), nodeKeys, edgeKeys);
    }
}
