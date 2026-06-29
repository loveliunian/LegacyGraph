package io.github.legacygraph.agent;

import io.github.legacygraph.dto.GraphMergeDecision;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.llm.LlmGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphMergeAgentTest {

    @Mock
    private LlmGateway llmGateway;

    @InjectMocks
    private GraphMergeAgent graphMergeAgent;

    private GraphNode nodeA;
    private GraphNode nodeB;

    @BeforeEach
    void setUp() {
        nodeA = new GraphNode();
        nodeA.setNodeKey("POST /api/users");
        nodeA.setNodeName("创建用户接口");
        nodeA.setNodeType("ApiEndpoint");
        nodeA.setDescription("创建新用户的REST接口");
        nodeA.setProperties("{\"method\":\"POST\",\"path\":\"/api/users\"}");

        nodeB = new GraphNode();
        nodeB.setNodeKey("POST /api/users/v2");
        nodeB.setNodeName("创建用户接口V2");
        nodeB.setNodeType("ApiEndpoint");
        nodeB.setDescription("创建新用户的REST接口V2版本");
        nodeB.setProperties("{\"method\":\"POST\",\"path\":\"/api/users/v2\"}");
    }

    // ==================== decideMerge 测试 ====================

    @Test
    void testDecideMerge_shouldCallLlmWithCorrectVariables() {
        // Arrange
        GraphMergeDecision expectedDecision = new GraphMergeDecision();
        expectedDecision.setCandidateA("POST /api/users");
        expectedDecision.setCandidateB("POST /api/users/v2");
        expectedDecision.setDecision(GraphMergeDecision.Decision.REVIEW);
        expectedDecision.setScore(BigDecimal.valueOf(0.75));
        expectedDecision.setReasons(List.of("名称相似度高", "路径层级接近"));
        expectedDecision.setPositiveEvidenceIds(List.of("evt-1"));
        expectedDecision.setNegativeEvidenceIds(List.of());

        when(llmGateway.callWithTemplate(
                eq("project-1"),
                eq("graph-merge-decision"),
                anyMap(),
                eq(GraphMergeDecision.class)))
                .thenReturn(expectedDecision);

        // Act
        GraphMergeDecision actual = graphMergeAgent.decideMerge(
                "project-1", nodeA, nodeB,
                0.85, 0.7, 0.6, 0.5, 0.3);

        // Assert
        assertNotNull(actual);
        assertEquals(expectedDecision.getCandidateA(), actual.getCandidateA());
        assertEquals(expectedDecision.getDecision(), actual.getDecision());
        assertEquals(expectedDecision.getScore(), actual.getScore());
        assertEquals(expectedDecision.getReasons(), actual.getReasons());
        assertEquals(expectedDecision.getPositiveEvidenceIds(), actual.getPositiveEvidenceIds());

        // Verify the variables map was built correctly
        ArgumentCaptor<Map<String, String>> variablesCaptor = ArgumentCaptor.captor();
        verify(llmGateway).callWithTemplate(
                eq("project-1"),
                eq("graph-merge-decision"),
                variablesCaptor.capture(),
                eq(GraphMergeDecision.class));

        Map<String, String> captured = variablesCaptor.getValue();
        assertEquals("POST /api/users", captured.get("candidateAKey"));
        assertEquals("POST /api/users/v2", captured.get("candidateBKey"));
        assertTrue(captured.get("candidateAInfo").contains("创建用户接口"));
        assertTrue(captured.get("candidateBInfo").contains("创建用户接口V2"));
        assertEquals("0.85", captured.get("nameScore"));
        assertEquals("0.7", captured.get("semanticScore"));
        assertEquals("0.6", captured.get("structScore"));
        assertEquals("0.5", captured.get("neighborScore"));
        assertEquals("0.3", captured.get("evidenceScore"));
    }

    @Test
    void testDecideMerge_shouldHandleNullNodeProperties() {
        // Arrange
        GraphNode nullPropNode = new GraphNode();
        nullPropNode.setNodeKey("NODE-X");
        nullPropNode.setNodeName("NullPropNode");
        nullPropNode.setNodeType("Service");
        nullPropNode.setDescription(null);
        nullPropNode.setProperties(null);

        GraphNode normalNode = new GraphNode();
        normalNode.setNodeKey("NODE-Y");
        normalNode.setNodeName("NormalNode");
        normalNode.setNodeType("Service");
        normalNode.setDescription("Normal description");
        normalNode.setProperties("{\"version\":\"1.0\"}");

        GraphMergeDecision expectedDecision = new GraphMergeDecision();
        expectedDecision.setCandidateA("NODE-X");
        expectedDecision.setCandidateB("NODE-Y");
        expectedDecision.setDecision(GraphMergeDecision.Decision.REJECT);
        expectedDecision.setScore(BigDecimal.valueOf(0.3));
        expectedDecision.setReasons(List.of("信息不足，不能合并"));
        expectedDecision.setPositiveEvidenceIds(List.of());
        expectedDecision.setNegativeEvidenceIds(List.of("evt-2"));

        when(llmGateway.callWithTemplate(
                eq("project-2"),
                eq("graph-merge-decision"),
                anyMap(),
                eq(GraphMergeDecision.class)))
                .thenReturn(expectedDecision);

        // Act
        GraphMergeDecision actual = graphMergeAgent.decideMerge(
                "project-2", nullPropNode, normalNode,
                0.2, 0.3, 0.1, 0.0, 0.0);

        // Assert
        assertNotNull(actual);
        assertEquals(GraphMergeDecision.Decision.REJECT, actual.getDecision());
        assertTrue(actual.getReasons().contains("信息不足，不能合并"));

        // Verify null fields are handled in variable construction (no NPE)
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(
                (Class<Map<String, String>>) (Class<?>) Map.class);
        verify(llmGateway).callWithTemplate(
                eq("project-2"),
                eq("graph-merge-decision"),
                captor.capture(),
                eq(GraphMergeDecision.class));

        Map<String, String> vars = captor.getValue();
        assertEquals("NODE-X", vars.get("candidateAKey"));
        assertNotNull(vars.get("candidateAInfo"));
        assertFalse(vars.get("candidateAInfo").contains("null"));
    }

    // ==================== calculateFinalConfidence 测试 ====================

    @Test
    void testCalculateFinalConfidence() {
        // --- 用例 1: 高置信度场景 ---
        // support=0.95, semantic=0.9, struct=0.85, neighbor=0.8,
        // runtimeVerified=true, humanReviewed=true, conflict=0.1
        // result = 0.50*0.95 + 0.15*0.9 + 0.15*0.85 + 0.10*0.8 + 0.05*1 + 0.05*1 - 0.35*0.1
        //        = 0.475 + 0.135 + 0.1275 + 0.08 + 0.05 + 0.05 - 0.035
        //        = 0.8825
        BigDecimal confidence = graphMergeAgent.calculateFinalConfidence(
                0.95, 0.9, 0.85, 0.8, true, true, 0.1);
        assertEquals(BigDecimal.valueOf(0.8825).setScale(4, java.math.RoundingMode.HALF_UP), confidence);

        // --- 用例 2: 低置信度场景（冲突太高）---
        // support=0.6, semantic=0.5, struct=0.5, neighbor=0.5,
        // runtimeVerified=false, humanReviewed=false, conflict=0.9
        // result = 0.50*0.6 + 0.15*0.5 + 0.15*0.5 + 0.10*0.5 + 0.05*0 + 0.05*0 - 0.35*0.9
        //        = 0.30 + 0.075 + 0.075 + 0.05 + 0 + 0 - 0.315
        //        = 0.185
        BigDecimal lowConf = graphMergeAgent.calculateFinalConfidence(
                0.6, 0.5, 0.5, 0.5, false, false, 0.9);
        assertEquals(BigDecimal.valueOf(0.1850).setScale(4, java.math.RoundingMode.HALF_UP), lowConf);

        // --- 用例 3: clamp 下界 ---
        // result = 0.50*0 + 0.15*0 + 0.15*0 + 0.10*0 + 0.05*0 + 0.05*0 - 0.35*1.0 = -0.35
        // clamp → 0
        BigDecimal clampedLow = graphMergeAgent.calculateFinalConfidence(
                0, 0, 0, 0, false, false, 1.0);
        assertEquals(BigDecimal.valueOf(0.0000).setScale(4), clampedLow);

        // --- 用例 4: clamp 上界 ---
        // result = 0.50*1 + 0.15*1 + 0.15*1 + 0.10*1 + 0.05*1 + 0.05*1 - 0.35*0 = 1.0
        // clamp → 1
        BigDecimal clampedHigh = graphMergeAgent.calculateFinalConfidence(
                1, 1, 1, 1, true, true, 0);
        assertEquals(BigDecimal.valueOf(1.0000).setScale(4), clampedHigh);

        // --- 用例 5: 完全无证据场景（所有值归零）---
        BigDecimal zeroConf = graphMergeAgent.calculateFinalConfidence(
                0, 0, 0, 0, false, false, 0);
        assertEquals(BigDecimal.valueOf(0.0000).setScale(4), zeroConf);

        // --- 用例 6: 只有 support 占主导的边界场景 ---
        // support=1.0, everything else=0, conflict=0
        // result = 0.5*1.0 = 0.5
        BigDecimal halfConf = graphMergeAgent.calculateFinalConfidence(
                1.0, 0, 0, 0, false, false, 0);
        assertEquals(BigDecimal.valueOf(0.5000).setScale(4), halfConf);
    }
}
