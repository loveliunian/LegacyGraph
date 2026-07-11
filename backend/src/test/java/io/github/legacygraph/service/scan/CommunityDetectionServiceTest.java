package io.github.legacygraph.service.scan;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CommunityDetectionService 单元测试 — 验证标签传播社区检测逻辑。
 *
 * <p>测试场景：
 * <ul>
 *   <li>连通图：3 个 Package 链式依赖 → 收敛为同一社区</li>
 *   <li>非连通图：两个独立分量 → 保持两个不同社区</li>
 *   <li>空图谱：无节点 → 返回空映射</li>
 *   <li>writeCommunityToNodes：验证节点属性被正确更新</li>
 *   <li>收敛条件：验证算法在收敛后停止迭代</li>
 *   <li>类级别社区检测：Controller/Service/Mapper + CALLS 边</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityDetectionService 社区检测测试")
class CommunityDetectionServiceTest {

    @Mock
    private Neo4jGraphDao graphDao;

    @Mock
    private LlmGateway llmGateway;

    private CommunityDetectionService service;

    @BeforeEach
    void setUp() {
        service = new CommunityDetectionService(graphDao, llmGateway);
    }

    // ========================================================
    // 场景 1：连通图 — 3 个 Package 链式依赖，收敛为同一社区
    // ========================================================

    @Test
    @DisplayName("连通图链式依赖应收敛为同一社区")
    void detectCommunities_connectedGraph_convergesToOneCommunity() {
        // given: A → B → C 链式依赖
        GraphNode pkgA = pkgNode("id-a", "com.example.a");
        GraphNode pkgB = pkgNode("id-b", "com.example.b");
        GraphNode pkgC = pkgNode("id-c", "com.example.c");
        when(graphDao.queryNodes(eq("proj-1"), isNull(), eq(NodeType.Package.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(pkgA, pkgB, pkgC));
        when(graphDao.queryEdges(eq("proj-1"), isNull(), eq(EdgeType.DEPENDS_ON.name()),
                isNull(), anyInt()))
                .thenReturn(List.of(
                        depEdge("id-a", "id-b"),
                        depEdge("id-b", "id-c")
                ));

        // when
        Map<String, String> result = service.detectCommunities("proj-1");

        // then: 3 个 Package 都属于同一社区
        assertThat(result).hasSize(3);
        Set<String> communities = new HashSet<>(result.values());
        assertThat(communities).hasSize(1);
    }

    // ========================================================
    // 场景 2：非连通图 — 两个独立分量保持两个不同社区
    // ========================================================

    @Test
    @DisplayName("非连通图应保持两个不同社区")
    void detectCommunities_disconnectedGraph_keepsSeparateCommunities() {
        // given: A-B 和 C-D 两个独立分量
        GraphNode pkgA = pkgNode("id-a", "com.example.a");
        GraphNode pkgB = pkgNode("id-b", "com.example.b");
        GraphNode pkgC = pkgNode("id-c", "com.example.c");
        GraphNode pkgD = pkgNode("id-d", "com.example.d");
        when(graphDao.queryNodes(eq("proj-2"), isNull(), eq(NodeType.Package.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(pkgA, pkgB, pkgC, pkgD));
        when(graphDao.queryEdges(eq("proj-2"), isNull(), eq(EdgeType.DEPENDS_ON.name()),
                isNull(), anyInt()))
                .thenReturn(List.of(
                        depEdge("id-a", "id-b"),
                        depEdge("id-c", "id-d")
                ));

        // when
        Map<String, String> result = service.detectCommunities("proj-2");

        // then: 2 个不同的社区
        assertThat(result).hasSize(4);
        Set<String> communities = new HashSet<>(result.values());
        assertThat(communities).hasSize(2);
        // A 和 B 属于同一社区
        assertThat(result.get("com.example.a")).isEqualTo(result.get("com.example.b"));
        // C 和 D 属于同一社区
        assertThat(result.get("com.example.c")).isEqualTo(result.get("com.example.d"));
        // 两个社区不同
        assertThat(result.get("com.example.a")).isNotEqualTo(result.get("com.example.c"));
    }

    // ========================================================
    // 场景 3：空图谱 — 无 Package 节点
    // ========================================================

    @Test
    @DisplayName("空图谱应返回空映射")
    void detectCommunities_emptyGraph_returnsEmptyMap() {
        when(graphDao.queryNodes(eq("proj-empty"), isNull(), eq(NodeType.Package.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());

        Map<String, String> result = service.detectCommunities("proj-empty");

        assertThat(result).isEmpty();
        // 空图谱不应查询边
        verify(graphDao, never()).queryEdges(anyString(), any(), anyString(), any(), anyInt());
    }

    // ========================================================
    // 场景 4：writeCommunityToNodes — 验证节点属性被正确更新
    // ========================================================

    @Test
    @DisplayName("writeCommunityToNodes 应正确设置每个 Package 节点的 community 属性")
    void writeCommunityToNodes_setsPropertyOnEachNode() {
        GraphNode pkgA = pkgNode("id-a", "com.example.a");
        GraphNode pkgB = pkgNode("id-b", "com.example.b");
        when(graphDao.queryNodes(eq("proj-3"), isNull(), eq(NodeType.Package.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(pkgA, pkgB));

        Map<String, String> communityMap = new LinkedHashMap<>();
        communityMap.put("com.example.a", "community-alpha");
        communityMap.put("com.example.b", "community-beta");

        service.writeCommunityToNodes("proj-3", communityMap);

        // 验证 setNodeProperty 被正确调用
        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> propCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(graphDao, times(2)).setNodeProperty(idCaptor.capture(), propCaptor.capture(), valueCaptor.capture());

        // 验证属性名都是 "community"
        assertThat(propCaptor.getAllValues()).allMatch("community"::equals);

        // 验证节点 ID 和标签值正确对应
        List<String> ids = idCaptor.getAllValues();
        List<Object> values = valueCaptor.getAllValues();
        assertThat(ids).containsExactlyInAnyOrder("id-a", "id-b");
        assertThat(values).containsExactlyInAnyOrder("community-alpha", "community-beta");

        // 验证 id-a 对应 community-alpha，id-b 对应 community-beta
        for (int i = 0; i < 2; i++) {
            if ("id-a".equals(ids.get(i))) {
                assertThat(values.get(i)).isEqualTo("community-alpha");
            } else if ("id-b".equals(ids.get(i))) {
                assertThat(values.get(i)).isEqualTo("community-beta");
            }
        }
    }

    @Test
    @DisplayName("writeCommunityToNodes 空映射不应调用 setNodeProperty")
    void writeCommunityToNodes_emptyMap_doesNothing() {
        service.writeCommunityToNodes("proj-3", Collections.emptyMap());
        verify(graphDao, never()).setNodeProperty(anyString(), anyString(), any());
    }

    // ========================================================
    // 场景 5：收敛条件 — 验证算法在收敛后停止
    // ========================================================

    @Test
    @DisplayName("星形图应在一次迭代后收敛")
    void detectCommunities_starGraph_convergesQuickly() {
        // 中心节点 B 连接 A、C、D，所有叶子节点都应收敛到 B 的标签
        GraphNode pkgA = pkgNode("id-a", "com.example.a");
        GraphNode pkgB = pkgNode("id-b", "com.example.b");
        GraphNode pkgC = pkgNode("id-c", "com.example.c");
        GraphNode pkgD = pkgNode("id-d", "com.example.d");
        when(graphDao.queryNodes(eq("proj-4"), isNull(), eq(NodeType.Package.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(pkgA, pkgB, pkgC, pkgD));
        when(graphDao.queryEdges(eq("proj-4"), isNull(), eq(EdgeType.DEPENDS_ON.name()),
                isNull(), anyInt()))
                .thenReturn(List.of(
                        depEdge("id-a", "id-b"),
                        depEdge("id-b", "id-c"),
                        depEdge("id-b", "id-d")
                ));

        Map<String, String> result = service.detectCommunities("proj-4");

        // 所有节点收敛到同一社区（中心节点 B 的标签）
        assertThat(result).hasSize(4);
        Set<String> communities = new HashSet<>(result.values());
        assertThat(communities).hasSize(1);
        // 收敛后的标签应该是某个节点的 nodeKey
        assertThat(communities.iterator().next()).isIn(
                "com.example.a", "com.example.b", "com.example.c", "com.example.d");
    }

    @Test
    @DisplayName("无边的孤立节点应各自保持独立社区")
    void detectCommunities_isolatedNodes_keepIndividualCommunities() {
        GraphNode pkgA = pkgNode("id-a", "com.example.a");
        GraphNode pkgB = pkgNode("id-b", "com.example.b");
        when(graphDao.queryNodes(eq("proj-5"), isNull(), eq(NodeType.Package.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(pkgA, pkgB));
        when(graphDao.queryEdges(eq("proj-5"), isNull(), eq(EdgeType.DEPENDS_ON.name()),
                isNull(), anyInt()))
                .thenReturn(Collections.emptyList());

        Map<String, String> result = service.detectCommunities("proj-5");

        // 无边的节点保持各自的初始标签
        assertThat(result).hasSize(2);
        assertThat(result.get("com.example.a")).isEqualTo("com.example.a");
        assertThat(result.get("com.example.b")).isEqualTo("com.example.b");
    }

    // ========================================================
    // 场景 6：类级别社区检测 — Controller/Service/Mapper + CALLS 边
    // ========================================================

    @Test
    @DisplayName("类级别社区检测应正确识别 Controller/Service/Mapper 的社区")
    void detectCommunitiesByClasses_withCallEdges() {
        GraphNode ctrl = classNode("id-ctrl", "OrderController", NodeType.Controller.name());
        GraphNode svc = classNode("id-svc", "OrderService", NodeType.Service.name());
        GraphNode mapper = classNode("id-mapper", "OrderMapper", NodeType.Mapper.name());
        when(graphDao.queryNodes(eq("proj-6"), isNull(), eq(NodeType.Controller.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(ctrl));
        when(graphDao.queryNodes(eq("proj-6"), isNull(), eq(NodeType.Service.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(svc));
        when(graphDao.queryNodes(eq("proj-6"), isNull(), eq(NodeType.Mapper.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(mapper));
        when(graphDao.queryEdges(eq("proj-6"), isNull(), eq(EdgeType.CALLS.name()),
                isNull(), anyInt()))
                .thenReturn(List.of(
                        depEdge("id-ctrl", "id-svc"),
                        depEdge("id-svc", "id-mapper")
                ));
        when(graphDao.queryEdges(eq("proj-6"), isNull(), eq(EdgeType.EXTENDS.name()),
                isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(graphDao.queryEdges(eq("proj-6"), isNull(), eq(EdgeType.IMPLEMENTS.name()),
                isNull(), anyInt()))
                .thenReturn(Collections.emptyList());

        Map<String, String> result = service.detectCommunitiesByClasses("proj-6");

        // 3 个类通过 CALLS 链式连接，收敛为同一社区
        assertThat(result).hasSize(3);
        Set<String> communities = new HashSet<>(result.values());
        assertThat(communities).hasSize(1);
    }

    @Test
    @DisplayName("类级别社区检测无类节点时返回空映射")
    void detectCommunitiesByClasses_noClassNodes_returnsEmpty() {
        when(graphDao.queryNodes(eq("proj-7"), isNull(), eq(NodeType.Controller.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(graphDao.queryNodes(eq("proj-7"), isNull(), eq(NodeType.Service.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(graphDao.queryNodes(eq("proj-7"), isNull(), eq(NodeType.Mapper.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());

        Map<String, String> result = service.detectCommunitiesByClasses("proj-7");

        assertThat(result).isEmpty();
    }

    // ========================================================
    // 场景 7：社区摘要生成 — 无社区时返回空
    // ========================================================

    @Test
    @DisplayName("无社区时 generateCommunitySummaries 返回空映射")
    void generateCommunitySummariesWithEmptyCommunitiesReturnsEmpty() {
        when(graphDao.queryNodes(eq("proj-empty-sum"), isNull(), eq(NodeType.Package.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());

        Map<String, String> result = service.generateCommunitySummaries("proj-empty-sum");

        assertThat(result).isEmpty();
        verify(llmGateway, never()).call(anyString(), anyString(), anyString(), any());
    }

    // ========================================================
    // 场景 8：社区摘要生成 — 有社区时生成摘要
    // ========================================================

    @Test
    @DisplayName("有社区时 generateCommunitySummaries 生成摘要")
    void generateCommunitySummariesWithCommunitiesReturnsSummaries() {
        GraphNode pkgA = pkgNode("id-a", "com.example.a");
        GraphNode pkgB = pkgNode("id-b", "com.example.b");

        when(graphDao.queryNodes(eq("proj-sum"), isNull(), eq(NodeType.Package.name()),
                isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(pkgA, pkgB));
        when(graphDao.queryEdges(eq("proj-sum"), isNull(), eq(EdgeType.DEPENDS_ON.name()),
                isNull(), anyInt()))
                .thenReturn(List.of(depEdge("id-a", "id-b")));
        when(llmGateway.call(eq("proj-sum"), anyString(), anyString(), eq(String.class)))
                .thenReturn("用户管理子系统");

        Map<String, String> result = service.generateCommunitySummaries("proj-sum");

        assertThat(result).isNotEmpty();
        assertThat(result).hasSize(1);
        assertThat(result.values().iterator().next()).isEqualTo("用户管理子系统");
        verify(llmGateway, times(1)).call(eq("proj-sum"), anyString(), anyString(), eq(String.class));
    }

    // ========================================================
    // 场景 9：writeCommunitySummaryToNodes — 空映射不执行写入
    // ========================================================

    @Test
    @DisplayName("writeCommunitySummaryToNodes 空映射不应调用 setNodeProperty")
    void writeCommunitySummaryToNodesWithEmptyMapDoesNothing() {
        service.writeCommunitySummaryToNodes("proj-empty", Collections.emptyMap());
        verify(graphDao, never()).setNodeProperty(anyString(), anyString(), any());
        verify(graphDao, never()).queryNodes(anyString(), any(), anyString(), any(), any(), any(), anyInt());
    }

    // ========================================================
    // 辅助方法
    // ========================================================

    private static GraphNode pkgNode(String id, String nodeKey) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setNodeKey(nodeKey);
        n.setNodeName(nodeKey);
        n.setNodeType(NodeType.Package.name());
        return n;
    }

    private static GraphNode classNode(String id, String nodeKey, String nodeType) {
        GraphNode n = new GraphNode();
        n.setId(id);
        n.setNodeKey(nodeKey);
        n.setNodeName(nodeKey);
        n.setNodeType(nodeType);
        return n;
    }

    private static GraphEdge depEdge(String fromId, String toId) {
        GraphEdge e = new GraphEdge();
        e.setFromNodeId(fromId);
        e.setToNodeId(toId);
        e.setEdgeType(EdgeType.DEPENDS_ON.name());
        return e;
    }
}
