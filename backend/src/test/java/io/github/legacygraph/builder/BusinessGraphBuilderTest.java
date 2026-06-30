package io.github.legacygraph.builder;

import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessGraphBuilderTest {

    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private EvidenceRepository evidenceRepository;
    @Mock
    private NodeEvidenceRepository nodeEvidenceRepository;
    @Mock
    private EdgeEvidenceRepository edgeEvidenceRepository;
    @Mock
    private DocChunkRepository docChunkRepository;
    @Mock
    private EvidenceGraphWriter writer;

    private BusinessGraphBuilder businessGraphBuilder;

    @BeforeEach
    void setUp() {
        businessGraphBuilder = new BusinessGraphBuilder(neo4jGraphDao, docChunkRepository, writer);
    }

    @Test
    void testConstruction() {
        assertNotNull(businessGraphBuilder);
    }

    /**
     * 端到端验证：文档业务事实 -> 业务图谱节点/边 -> 证据关联
     * 覆盖 P1-3：domain + process(含 step) 落库为节点，关系落库为边，每个节点生成证据。
     */
    @Test
    void testBuildBusinessGraph_PersistsNodesEdgesAndEvidence() {
        when(writer.upsertNode(any(GraphNodeClaim.class))).thenAnswer(invocation -> {
            GraphNodeClaim claim = invocation.getArgument(0);
            GraphNode node = new GraphNode();
            node.setId(claim.getNodeType() + ":" + claim.getNodeKey());
            node.setProjectId(claim.getProjectId());
            node.setVersionId(claim.getVersionId());
            node.setNodeType(claim.getNodeType());
            node.setNodeKey(claim.getNodeKey());
            node.setNodeName(claim.getNodeName());
            node.setSourcePath(claim.getSourcePath());
            return node;
        });
        when(writer.upsertEdge(any(GraphEdgeClaim.class))).thenAnswer(invocation -> {
            GraphEdgeClaim claim = invocation.getArgument(0);
            GraphEdge edge = new GraphEdge();
            edge.setId(claim.getEdgeType() + ":" + claim.getEdgeKey());
            edge.setProjectId(claim.getProjectId());
            edge.setVersionId(claim.getVersionId());
            edge.setFromNodeId(claim.getFromNodeId());
            edge.setToNodeId(claim.getToNodeId());
            edge.setEdgeType(claim.getEdgeType());
            edge.setEdgeKey(claim.getEdgeKey());
            return edge;
        });

        DocUnderstandingAgent.BusinessFactExtraction facts =
                new DocUnderstandingAgent.BusinessFactExtraction();

        DocUnderstandingAgent.BusinessDomain domain = new DocUnderstandingAgent.BusinessDomain();
        domain.setName("订单管理");
        domain.setDescription("订单全生命周期管理");
        domain.setConfidence(0.9);
        facts.getBusinessDomains().add(domain);

        DocUnderstandingAgent.BusinessProcess process = new DocUnderstandingAgent.BusinessProcess();
        process.setKey("create-order");
        process.setName("创建订单");
        process.setDescription("用户下单流程");
        process.setSteps(List.of("选择商品", "提交订单"));
        process.setConfidence(0.85);
        facts.getBusinessProcesses().add(process);

        // when
        businessGraphBuilder.buildBusinessGraph("project-1", "version-1", facts, "/docs/order.md");

        // then: 节点落库 —— 1 domain + 1 process + 2 step features = 4 个节点
        ArgumentCaptor<GraphNodeClaim> nodeCaptor = ArgumentCaptor.forClass(GraphNodeClaim.class);
        verify(writer, times(4)).upsertNode(nodeCaptor.capture());

        List<GraphNodeClaim> nodes = nodeCaptor.getAllValues();
        assertTrue(nodes.stream().anyMatch(n ->
                NodeType.BusinessDomain.name().equals(n.getNodeType()) && "订单管理".equals(n.getNodeName())));
        assertTrue(nodes.stream().anyMatch(n ->
                NodeType.BusinessProcess.name().equals(n.getNodeType()) && "创建订单".equals(n.getNodeName())));
        assertEquals(2, nodes.stream().filter(n -> NodeType.Feature.name().equals(n.getNodeType())).count());
        // 全部来自文档 AI，项目/版本一致
        assertTrue(nodes.stream().allMatch(n ->
                "project-1".equals(n.getProjectId()) && "version-1".equals(n.getVersionId())));
        assertTrue(nodes.stream().allMatch(n -> "/docs/order.md".equals(n.getSourcePath())));

        // 边落库 —— 不再轮询分配 domain→process 边，仅 process CONTAINS 2 steps = 2 条边
        ArgumentCaptor<GraphEdgeClaim> edgeCaptor = ArgumentCaptor.forClass(GraphEdgeClaim.class);
        verify(writer, times(2)).upsertEdge(edgeCaptor.capture());
    }
}
