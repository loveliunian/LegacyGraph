package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.FeatureSlice;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.KnowledgeClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.graph.FeatureSliceSynthesizer;
import io.github.legacygraph.service.graph.KnowledgeClaimService;

/**
 * FeatureSliceSynthesizer 单元测试。
 * <p>
 * 测试动态功能切片合成器的核心逻辑：
 * Feature 查找、Claim 查询、入口/实现/数据/规则/验证各层节点收集、
 * 缺口识别、以及切片合成输出。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class FeatureSliceSynthesizerTest {

    @Mock
    private KnowledgeClaimService knowledgeClaimService;

    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @InjectMocks
    private FeatureSliceSynthesizer synthesizer;

    private GraphNode featureNode;
    private GraphNode apiNode;
    private GraphNode serviceNode;

    @BeforeEach
    void setUp() {
        featureNode = new GraphNode();
        featureNode.setId("feat-1");
        featureNode.setNodeType("Feature");
        featureNode.setNodeName("user-management");
        featureNode.setDisplayName("用户管理");
        featureNode.setConfidence(BigDecimal.valueOf(0.9));
        featureNode.setStatus("ACTIVE");

        apiNode = new GraphNode();
        apiNode.setId("api-1");
        apiNode.setNodeType("ApiEndpoint");
        apiNode.setNodeName("GET /api/users");
        apiNode.setDisplayName("查询用户列表");
        apiNode.setConfidence(BigDecimal.valueOf(0.95));

        serviceNode = new GraphNode();
        serviceNode.setId("svc-1");
        serviceNode.setNodeType("Service");
        serviceNode.setNodeName("UserService");
        serviceNode.setDisplayName("用户服务");
        serviceNode.setConfidence(BigDecimal.valueOf(0.9));
    }

    /**
     * 测试：Feature 节点不存在时，返回 UNCOVERED 状态的空切片。
     */
    @Test
    void testSynthesize_FeatureNotFound() {
        // 模拟 Neo4j 中找不到 Feature 节点
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("Feature"),
                anyString(), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(Collections.emptyList());
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("Feature"),
                isNull(), isNull(), isNull(), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());
        // 模拟无 Claim
        when(knowledgeClaimService.listClaims(eq("proj-1"), eq("v1"), eq("Feature"),
                isNull(), isNull(), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());

        FeatureSlice slice = synthesizer.synthesizeFeatureSlice("proj-1", "v1", "nonexistent");

        assertNotNull(slice);
        assertEquals("nonexistent", slice.getSliceId());
        assertEquals("UNCOVERED", slice.getCoverageStatus());
        assertTrue(slice.getEntrances().isEmpty());
        assertTrue(slice.getImplementation().isEmpty());
    }

    /**
     * 测试：Feature 节点存在且有入口（ApiEndpoint），返回 COVERED 或 PARTIAL 切片。
     */
    @Test
    void testSynthesize_FeatureWithEntrance() {
        // 模拟找到 Feature 节点
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("Feature"),
                eq("user-management"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(List.of(featureNode));
        // 模拟无 Claim
        when(knowledgeClaimService.listClaims(eq("proj-1"), eq("v1"), eq("Feature"),
                isNull(), isNull(), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());
        // 模拟从 Feature 出发查找 EXPOSED_BY 边
        GraphEdge edge = new GraphEdge();
        edge.setFromNodeId("feat-1");
        edge.setToNodeId("api-1");
        edge.setEdgeType("EXPOSED_BY");
        when(neo4jGraphDao.queryEdges(eq("proj-1"), eq("v1"), eq("EXPOSED_BY"),
                isNull(), eq("feat-1"), isNull(), isNull(), eq(50)))
                .thenReturn(List.of(edge));
        when(neo4jGraphDao.findNodeById("api-1")).thenReturn(Optional.of(apiNode));
        // CALLS 边为空
        when(neo4jGraphDao.queryEdges(eq("proj-1"), eq("v1"), eq("CALLS"),
                isNull(), eq("feat-1"), isNull(), isNull(), eq(50)))
                .thenReturn(Collections.emptyList());

        FeatureSlice slice = synthesizer.synthesizeFeatureSlice("proj-1", "v1", "user-management");

        assertNotNull(slice);
        assertEquals("feat-1", slice.getSliceId());
        assertEquals("用户管理", slice.getFeatureName());
        // 应包含入口节点
        assertFalse(slice.getEntrances().isEmpty());
        assertEquals("ApiEndpoint", slice.getEntrances().get(0).getNodeType());
    }

    /**
     * 测试：Feature 有入口和实现（Service），返回 PARTIAL 或 COVERED 切片。
     */
    @Test
    void testSynthesize_FeatureWithEntranceAndImplementation() {
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("Feature"),
                eq("user-management"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(List.of(featureNode));
        when(knowledgeClaimService.listClaims(eq("proj-1"), eq("v1"), eq("Feature"),
                isNull(), isNull(), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());

        // 先用通用 stub 兜底（其他边类型返回空）
        // Mockito 规则：通用 stub 先定义，后面具体 stub 覆盖
        lenient().when(neo4jGraphDao.queryEdges(anyString(), anyString(), anyString(),
                isNull(), anyString(), isNull(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());

        // 入口边
        GraphEdge entranceEdge = new GraphEdge();
        entranceEdge.setFromNodeId("feat-1");
        entranceEdge.setToNodeId("api-1");
        entranceEdge.setEdgeType("EXPOSED_BY");
        when(neo4jGraphDao.queryEdges(eq("proj-1"), eq("v1"), eq("EXPOSED_BY"),
                isNull(), eq("feat-1"), isNull(), isNull(), eq(50)))
                .thenReturn(List.of(entranceEdge));

        // 实现边：ApiEndpoint → HANDLED_BY → Service
        GraphEdge implEdge = new GraphEdge();
        implEdge.setFromNodeId("api-1");
        implEdge.setToNodeId("svc-1");
        implEdge.setEdgeType("HANDLED_BY");
        when(neo4jGraphDao.queryEdges(eq("proj-1"), eq("v1"), eq("HANDLED_BY"),
                isNull(), eq("api-1"), isNull(), isNull(), eq(50)))
                .thenReturn(List.of(implEdge));

        when(neo4jGraphDao.findNodeById("api-1")).thenReturn(Optional.of(apiNode));
        when(neo4jGraphDao.findNodeById("svc-1")).thenReturn(Optional.of(serviceNode));

        FeatureSlice slice = synthesizer.synthesizeFeatureSlice("proj-1", "v1", "user-management");

        assertNotNull(slice);
        assertFalse(slice.getEntrances().isEmpty());
        assertFalse(slice.getImplementation().isEmpty());
        assertEquals("Service", slice.getImplementation().get(0).getNodeType());
    }

    /**
     * 测试：Feature 无入口 → 识别 NO_ENTRANCE 缺口。
     */
    @Test
    void testSynthesize_NoEntranceGap() {
        when(neo4jGraphDao.queryNodes(eq("proj-1"), eq("v1"), eq("Feature"),
                eq("orphan-feature"), isNull(), isNull(), isNull(), eq(1)))
                .thenReturn(List.of(featureNode));
        when(knowledgeClaimService.listClaims(eq("proj-1"), eq("v1"), eq("Feature"),
                isNull(), isNull(), isNull(), eq(500)))
                .thenReturn(Collections.emptyList());
        lenient().when(neo4jGraphDao.queryEdges(anyString(), anyString(), anyString(),
                isNull(), eq("feat-1"), isNull(), isNull(), eq(50)))
                .thenReturn(Collections.emptyList());

        FeatureSlice slice = synthesizer.synthesizeFeatureSlice("proj-1", "v1", "orphan-feature");

        assertNotNull(slice);
        // 缺口应包含 NO_ENTRANCE
        assertFalse(slice.getGaps().isEmpty());
        assertTrue(slice.getGaps().stream().anyMatch(g -> "NO_ENTRANCE".equals(g.getNodeName())));
    }
}
