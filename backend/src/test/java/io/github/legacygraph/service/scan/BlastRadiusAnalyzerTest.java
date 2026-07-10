package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BlastRadiusAnalyzer 单元测试 — 验证 Blast Radius 传播分析、受影响节点标记与子图构建逻辑。
 *
 * <p>测试场景：
 * <ul>
 *   <li>analyzeBlastRadius：给定变更文件，验证能正确找到依赖者</li>
 *   <li>markAffectedNodes：验证节点被正确标记 affected=true 及 affectedReason</li>
 *   <li>getAffectedSubgraph：验证子图返回正确（受影响节点 + 邻居 + 边）</li>
 *   <li>空变更列表场景：返回空结果且不调用 DAO</li>
 *   <li>无依赖者的孤立节点场景：返回空受影响列表</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlastRadiusAnalyzer 传播分析服务测试")
class BlastRadiusAnalyzerTest {

    @Mock
    private Neo4jGraphDao graphDao;

    @Mock
    private FileChangeDetector fileChangeDetector;

    private BlastRadiusAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new BlastRadiusAnalyzer(graphDao, fileChangeDetector);
    }

    // ==================== analyzeBlastRadius ====================

    @Test
    @DisplayName("analyzeBlastRadius：给定变更文件，正确找到依赖者并构建传播路径")
    void analyzeBlastRadiusFindsDependents() {
        String filePath = "src/main/java/com/example/FooService.java";

        // 文件中的节点
        Map<String, Object> methodNode = new LinkedHashMap<>();
        methodNode.put("nodeId", "method-1");
        methodNode.put("nodeKey", "com.example.FooService.bar");
        methodNode.put("nodeName", "bar");
        methodNode.put("nodeType", "Method");
        methodNode.put("sourcePath", filePath);
        when(graphDao.findNodesBySourcePath(eq("p1"), isNull(), eq(filePath)))
                .thenReturn(List.of(methodNode));

        // 反向依赖：caller-1 CALLS method-1
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("sourceId", "caller-1");
        dep.put("sourceKey", "com.example.BazService");
        dep.put("sourceName", "BazService");
        dep.put("sourceType", "Service");
        dep.put("targetId", "method-1");
        dep.put("targetKey", "com.example.FooService.bar");
        dep.put("edgeType", "CALLS");
        when(graphDao.findReverseDependents(eq("p1"), isNull(), any(), anyList()))
                .thenReturn(List.of(dep));

        BlastRadiusAnalyzer.BlastRadiusResult result =
                analyzer.analyzeBlastRadius("p1", List.of(filePath));

        assertThat(result.affectedNodeIds()).containsExactly("caller-1");
        assertThat(result.propagationPaths()).hasSize(1);
        BlastRadiusAnalyzer.PropagationPath path = result.propagationPaths().get(0);
        assertThat(path.changedNodeId()).isEqualTo("method-1");
        assertThat(path.changedNodeKey()).isEqualTo("com.example.FooService.bar");
        assertThat(path.affectedNodeId()).isEqualTo("caller-1");
        assertThat(path.affectedNodeKey()).isEqualTo("com.example.BazService");
        assertThat(path.affectedNodeType()).isEqualTo("Service");
        assertThat(path.edgeType()).isEqualTo("CALLS");
        assertThat(path.changedFilePath()).isEqualTo(filePath);
    }

    @Test
    @DisplayName("analyzeBlastRadius：多个变更文件聚合去重受影响节点")
    void analyzeBlastRadiusAggregatesMultipleFiles() {
        String file1 = "src/main/java/FooService.java";
        String file2 = "src/main/java/BarMapper.java";

        // file1 的节点
        Map<String, Object> node1 = new LinkedHashMap<>();
        node1.put("nodeId", "m1");
        node1.put("nodeKey", "FooService.methodA");
        node1.put("nodeType", "Method");
        when(graphDao.findNodesBySourcePath(eq("p1"), isNull(), eq(file1)))
                .thenReturn(List.of(node1));

        // file2 的节点
        Map<String, Object> node2 = new LinkedHashMap<>();
        node2.put("nodeId", "m2");
        node2.put("nodeKey", "BarMapper.methodB");
        node2.put("nodeType", "Method");
        when(graphDao.findNodesBySourcePath(eq("p1"), isNull(), eq(file2)))
                .thenReturn(List.of(node2));

        // file1 的依赖者
        Map<String, Object> dep1 = new LinkedHashMap<>();
        dep1.put("sourceId", "caller-1");
        dep1.put("sourceKey", "CallerA");
        dep1.put("sourceType", "Service");
        dep1.put("targetId", "m1");
        dep1.put("targetKey", "FooService.methodA");
        dep1.put("edgeType", "CALLS");

        // file2 的依赖者 — caller-1 同时依赖两个文件（去重验证）
        Map<String, Object> dep2 = new LinkedHashMap<>();
        dep2.put("sourceId", "caller-1");
        dep2.put("sourceKey", "CallerA");
        dep2.put("sourceType", "Service");
        dep2.put("targetId", "m2");
        dep2.put("targetKey", "BarMapper.methodB");
        dep2.put("edgeType", "CALLS");

        Map<String, Object> dep3 = new LinkedHashMap<>();
        dep3.put("sourceId", "caller-2");
        dep3.put("sourceKey", "CallerB");
        dep3.put("sourceType", "Controller");
        dep3.put("targetId", "m2");
        dep3.put("targetKey", "BarMapper.methodB");
        dep3.put("edgeType", "CALLS");

        when(graphDao.findReverseDependents(eq("p1"), isNull(), any(), anyList()))
                .thenReturn(List.of(dep1))
                .thenReturn(List.of(dep2, dep3));

        BlastRadiusAnalyzer.BlastRadiusResult result =
                analyzer.analyzeBlastRadius("p1", List.of(file1, file2));

        // caller-1 去重，只出现一次
        assertThat(result.affectedNodeIds()).containsExactlyInAnyOrder("caller-1", "caller-2");
        assertThat(result.propagationPaths()).hasSize(3);
    }

    @Test
    @DisplayName("analyzeBlastRadius：空变更列表返回空结果且不调用 DAO")
    void analyzeBlastRadiusEmptyChangedFilesReturnsEmpty() {
        BlastRadiusAnalyzer.BlastRadiusResult result =
                analyzer.analyzeBlastRadius("p1", List.of());

        assertThat(result.affectedNodeIds()).isEmpty();
        assertThat(result.propagationPaths()).isEmpty();
        verify(graphDao, never()).findNodesBySourcePath(any(), any(), any());
        verify(graphDao, never()).findReverseDependents(any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("analyzeBlastRadius：null 变更列表返回空结果")
    void analyzeBlastRadiusNullChangedFilesReturnsEmpty() {
        BlastRadiusAnalyzer.BlastRadiusResult result =
                analyzer.analyzeBlastRadius("p1", null);

        assertThat(result.affectedNodeIds()).isEmpty();
        assertThat(result.propagationPaths()).isEmpty();
    }

    @Test
    @DisplayName("analyzeBlastRadius：无依赖者的孤立节点返回空受影响列表")
    void analyzeBlastRadiusIsolatedNodeReturnsEmptyAffected() {
        String filePath = "src/main/java/Isolated.java";

        Map<String, Object> isolatedNode = new LinkedHashMap<>();
        isolatedNode.put("nodeId", "iso-1");
        isolatedNode.put("nodeKey", "Isolated.method");
        isolatedNode.put("nodeType", "Method");
        when(graphDao.findNodesBySourcePath(eq("p1"), isNull(), eq(filePath)))
                .thenReturn(List.of(isolatedNode));

        // 没有任何反向依赖
        when(graphDao.findReverseDependents(eq("p1"), isNull(), any(), anyList()))
                .thenReturn(List.of());

        BlastRadiusAnalyzer.BlastRadiusResult result =
                analyzer.analyzeBlastRadius("p1", List.of(filePath));

        assertThat(result.affectedNodeIds()).isEmpty();
        assertThat(result.propagationPaths()).isEmpty();
    }

    @Test
    @DisplayName("analyzeBlastRadius：文件在图谱中无节点时跳过该文件")
    void analyzeBlastRadiusSkipsFileWithNoNodes() {
        String filePath = "src/main/java/Unknown.java";
        when(graphDao.findNodesBySourcePath(eq("p1"), isNull(), eq(filePath)))
                .thenReturn(List.of());

        BlastRadiusAnalyzer.BlastRadiusResult result =
                analyzer.analyzeBlastRadius("p1", List.of(filePath));

        assertThat(result.affectedNodeIds()).isEmpty();
        // 文件无节点时不调用反向依赖查询
        verify(graphDao, never()).findReverseDependents(any(), any(), any(), anyList());
    }

    // ==================== markAffectedNodes ====================

    @Test
    @DisplayName("markAffectedNodes：将受影响节点标记 affected=true 并写入 affectedReason")
    void markAffectedNodesSetsProperties() {
        BlastRadiusAnalyzer.PropagationPath path = new BlastRadiusAnalyzer.PropagationPath(
                "method-1", "com.example.FooService.bar",
                "caller-1", "com.example.BazService",
                "Service", "CALLS",
                "src/main/java/FooService.java");
        BlastRadiusAnalyzer.BlastRadiusResult result =
                new BlastRadiusAnalyzer.BlastRadiusResult(List.of("caller-1"), List.of(path));

        analyzer.markAffectedNodes("p1", result);

        // 验证 affected=true
        verify(graphDao).setNodeProperty("caller-1", "affected", true);
        // 验证 affectedReason 包含边类型和变更节点信息
        ArgumentCaptor<Object> reasonCaptor = ArgumentCaptor.forClass(Object.class);
        verify(graphDao).setNodeProperty(eq("caller-1"), eq("affectedReason"), reasonCaptor.capture());
        Object reasonValue = reasonCaptor.getValue();
        assertThat(reasonValue).isInstanceOf(String.class);
        assertThat((String) reasonValue).contains("CALLS");
        assertThat((String) reasonValue).contains("com.example.FooService.bar");
        assertThat((String) reasonValue).contains("FooService.java");
    }

    @Test
    @DisplayName("markAffectedNodes：空结果时不调用 setNodeProperty")
    void markAffectedNodesEmptyResultDoesNothing() {
        analyzer.markAffectedNodes("p1", BlastRadiusAnalyzer.BlastRadiusResult.empty());

        verify(graphDao, never()).setNodeProperty(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("markAffectedNodes：单个节点标记异常不阻塞其他节点")
    void markAffectedNodesToleratesSingleFailure() {
        BlastRadiusAnalyzer.PropagationPath path1 = new BlastRadiusAnalyzer.PropagationPath(
                "m1", "Foo.bar", "caller-1", "CallerA",
                "Service", "CALLS", "Foo.java");
        BlastRadiusAnalyzer.PropagationPath path2 = new BlastRadiusAnalyzer.PropagationPath(
                "m2", "Baz.qux", "caller-2", "CallerB",
                "Controller", "CALLS", "Baz.java");
        BlastRadiusAnalyzer.BlastRadiusResult result =
                new BlastRadiusAnalyzer.BlastRadiusResult(List.of("caller-1", "caller-2"),
                        List.of(path1, path2));

        // caller-1 标记时抛异常（setNodeProperty 返回 void，需用 doThrow）
        doThrow(new RuntimeException("neo4j write failed"))
                .when(graphDao).setNodeProperty(eq("caller-1"), anyString(), any());

        analyzer.markAffectedNodes("p1", result);

        // caller-2 仍被标记
        verify(graphDao).setNodeProperty("caller-2", "affected", true);
    }

    // ==================== getAffectedSubgraph ====================

    @Test
    @DisplayName("getAffectedSubgraph：返回受影响节点及其邻居构成的子图")
    void getAffectedSubgraphReturnsNodesAndEdges() {
        BlastRadiusAnalyzer.BlastRadiusResult result =
                new BlastRadiusAnalyzer.BlastRadiusResult(
                        List.of("caller-1"), List.of());

        // 邻居：caller-1 的邻居是 neighbor-1
        Map<String, Set<String>> neighborMap = new HashMap<>();
        neighborMap.put("caller-1", Set.of("neighbor-1"));
        when(graphDao.findNeighborNodeIdsBySources(eq("p1"), any(), anyInt()))
                .thenReturn(neighborMap);

        // 节点对象
        GraphNode callerNode = new GraphNode();
        callerNode.setId("caller-1");
        callerNode.setNodeKey("CallerA");
        GraphNode neighborNode = new GraphNode();
        neighborNode.setId("neighbor-1");
        neighborNode.setNodeKey("NeighborB");
        when(graphDao.findNodesByIds(anyList()))
                .thenReturn(List.of(callerNode, neighborNode));

        // 边
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", "edge-1");
        edge.put("type", "CALLS");
        edge.put("source", "caller-1");
        edge.put("target", "neighbor-1");
        when(graphDao.queryEdgesForNodesByProject(eq("p1"), anyList()))
                .thenReturn(List.of(edge));

        BlastRadiusAnalyzer.AffectedSubgraph subgraph =
                analyzer.getAffectedSubgraph("p1", result);

        assertThat(subgraph.nodes()).hasSize(2);
        assertThat(subgraph.nodes()).extracting(GraphNode::getId)
                .containsExactlyInAnyOrder("caller-1", "neighbor-1");
        assertThat(subgraph.edges()).hasSize(1);
        assertThat(subgraph.edges().get(0).get("type")).isEqualTo("CALLS");
    }

    @Test
    @DisplayName("getAffectedSubgraph：空结果返回空子图")
    void getAffectedSubgraphEmptyResultReturnsEmpty() {
        BlastRadiusAnalyzer.AffectedSubgraph subgraph =
                analyzer.getAffectedSubgraph("p1", BlastRadiusAnalyzer.BlastRadiusResult.empty());

        assertThat(subgraph.nodes()).isEmpty();
        assertThat(subgraph.edges()).isEmpty();
        verify(graphDao, never()).findNodesByIds(anyList());
        verify(graphDao, never()).queryEdgesForNodesByProject(any(), anyList());
    }

    @Test
    @DisplayName("getAffectedSubgraph：受影响节点无邻居时仅返回自身")
    void getAffectedSubgraphNoNeighborsReturnsOnlyAffected() {
        BlastRadiusAnalyzer.BlastRadiusResult result =
                new BlastRadiusAnalyzer.BlastRadiusResult(
                        List.of("isolated-1"), List.of());

        // 无邻居
        when(graphDao.findNeighborNodeIdsBySources(eq("p1"), any(), anyInt()))
                .thenReturn(new HashMap<>());

        GraphNode node = new GraphNode();
        node.setId("isolated-1");
        when(graphDao.findNodesByIds(anyList()))
                .thenReturn(List.of(node));
        when(graphDao.queryEdgesForNodesByProject(eq("p1"), anyList()))
                .thenReturn(List.of());

        BlastRadiusAnalyzer.AffectedSubgraph subgraph =
                analyzer.getAffectedSubgraph("p1", result);

        assertThat(subgraph.nodes()).hasSize(1);
        assertThat(subgraph.nodes().get(0).getId()).isEqualTo("isolated-1");
        assertThat(subgraph.edges()).isEmpty();
    }

    // ==================== 反向遍历边类型白名单验证 ====================

    @Test
    @DisplayName("analyzeBlastRadius：反向遍历覆盖所有指定边类型")
    void analyzeBlastRadiusCoversAllEdgeTypes() {
        String filePath = "src/main/java/Changed.java";

        Map<String, Object> changedNode = new LinkedHashMap<>();
        changedNode.put("nodeId", "changed-1");
        changedNode.put("nodeKey", "ChangedClass");
        changedNode.put("nodeType", "Service");
        when(graphDao.findNodesBySourcePath(eq("p1"), isNull(), eq(filePath)))
                .thenReturn(List.of(changedNode));

        // 验证传入 findReverseDependents 的边类型列表包含所有 9 种
        when(graphDao.findReverseDependents(eq("p1"), isNull(), any(), anyList()))
                .thenAnswer(inv -> {
                    List<String> edgeTypes = inv.getArgument(3);
                    assertThat(edgeTypes).containsExactlyInAnyOrder(
                            EdgeType.CALLS.name(),
                            EdgeType.READS.name(),
                            EdgeType.WRITES.name(),
                            EdgeType.BELONGS_TO.name(),
                            EdgeType.DEPENDS_ON.name(),
                            EdgeType.IMPLEMENTS.name(),
                            EdgeType.EXTENDS.name(),
                            EdgeType.IMPLEMENTED_BY.name(),
                            EdgeType.EXPOSED_BY.name());
                    return List.of();
                });

        analyzer.analyzeBlastRadius("p1", List.of(filePath));
    }

    // ==================== clearAffectedMarkers ====================

    @Test
    @DisplayName("clearAffectedMarkers：委托 Neo4jGraphDao 清除标记并返回清除节点数")
    void clearAffectedMarkersDelegatesAndReturnsCount() {
        when(graphDao.clearAffectedMarkers("p1", "v1")).thenReturn(5);

        int cleared = analyzer.clearAffectedMarkers("p1", "v1");

        assertThat(cleared).isEqualTo(5);
        verify(graphDao).clearAffectedMarkers("p1", "v1");
    }

    @Test
    @DisplayName("clearAffectedMarkers：无标记时返回 0")
    void clearAffectedMarkersReturnsZeroWhenNoMarkers() {
        when(graphDao.clearAffectedMarkers("p1", "v1")).thenReturn(0);

        int cleared = analyzer.clearAffectedMarkers("p1", "v1");

        assertThat(cleared).isZero();
        verify(graphDao).clearAffectedMarkers("p1", "v1");
    }

    @Test
    @DisplayName("clearAffectedMarkers：versionId 为 null 时仍正确委托")
    void clearAffectedMarkersWithNullVersionId() {
        when(graphDao.clearAffectedMarkers("p1", null)).thenReturn(3);

        int cleared = analyzer.clearAffectedMarkers("p1", null);

        assertThat(cleared).isEqualTo(3);
        verify(graphDao).clearAffectedMarkers("p1", null);
    }
}
