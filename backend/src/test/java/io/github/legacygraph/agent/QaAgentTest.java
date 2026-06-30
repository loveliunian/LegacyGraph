package io.github.legacygraph.agent;

import io.github.legacygraph.dto.QaAnswer;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.llm.LlmGateway;
import io.github.legacygraph.service.VectorRetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QaAgent 测试 — 验证 RAG 问答流程：向量召回 + 图邻域 → 上下文拼装 → LLM 生成 → 证据回填。
 */
@ExtendWith(MockitoExtension.class)
class QaAgentTest {

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private VectorRetrievalService vectorRetrievalService;
    @Mock
    private Neo4jGraphDao neo4jGraphDao;

    @InjectMocks
    private QaAgent qaAgent;

    @Captor
    private ArgumentCaptor<Map<String, String>> variablesCaptor;

    private GraphNode node;
    private VectorDocument doc;

    @BeforeEach
    void setUp() {
        node = new GraphNode();
        node.setId("n1");
        node.setNodeType("API");
        node.setNodeKey("api:/user/register");
        node.setNodeName("用户注册接口");
        node.setDescription("注册新用户，写入 t_user");
        node.setConfidence(new BigDecimal("0.9"));
        node.setSourcePath("UserController.java");

        doc = new VectorDocument();
        doc.setChunkType("doc");
        doc.setSourceUri("register.md");
        doc.setContent("用户注册流程：校验手机号，写入用户表 t_user");
        doc.setChunkIndex(0);
    }

    @Test
    void testAnswer_BuildsContextFromRecallAndReturnsEvidence() {
        // given
        when(vectorRetrievalService.semanticSearch(eq("proj-1"), eq("v1"), anyString(), anyInt(), isNull()))
                .thenReturn(List.of(doc));
        when(vectorRetrievalService.findSimilarNodes(eq("proj-1"), eq("v1"), anyString(), anyDouble()))
                .thenReturn(List.of(node));

        QaAnswer llmAnswer = new QaAnswer();
        llmAnswer.setAnswer("用户注册涉及 t_user 表。");
        llmAnswer.setConfidence(0.85);
        llmAnswer.setUsedEvidence(List.of("api:/user/register", "chunk#0"));
        llmAnswer.setRelatedNodeKeys(List.of("api:/user/register"));

        when(llmGateway.callWithTemplate(eq("proj-1"), eq("qa-answer"), anyMap(), eq(QaAnswer.class)))
                .thenReturn(llmAnswer);

        // when
        QaAnswer result = qaAgent.answer("proj-1", "v1", "用户注册涉及哪些表？");

        // then: 返回 LLM 答案
        assertNotNull(result);
        assertEquals("用户注册涉及 t_user 表。", result.getAnswer());
        assertEquals(0.85, result.getConfidence());

        // 证据被服务端回填：1 个节点 + 1 个文档片段
        assertEquals(2, result.getEvidences().size());
        assertTrue(result.getEvidences().stream().anyMatch(e -> "GRAPH_NODE".equals(e.getSourceKind())));
        assertTrue(result.getEvidences().stream().anyMatch(e -> "DOC_CHUNK".equals(e.getSourceKind())));

        // 上下文中应同时包含节点与文档信息
        verify(llmGateway).callWithTemplate(eq("proj-1"), eq("qa-answer"),
                variablesCaptor.capture(), eq(QaAnswer.class));
        String context = variablesCaptor.getValue().get("context");
        assertTrue(context.contains("api:/user/register"));
        assertTrue(context.contains("t_user"));
        assertEquals("用户注册涉及哪些表？", variablesCaptor.getValue().get("question"));
    }

    @Test
    void testAnswer_ExpandsOneHopGraphNeighborhoodIntoContext() {
        GraphNode neighbor = new GraphNode();
        neighbor.setId("n2");
        neighbor.setNodeType("Service");
        neighbor.setNodeKey("service:ProfileService");
        neighbor.setNodeName("ProfileService");
        neighbor.setDescription("维护用户资料");
        neighbor.setConfidence(new BigDecimal("0.8"));

        GraphEdge edge = new GraphEdge();
        edge.setId("e1");
        edge.setFromNodeId("n1");
        edge.setToNodeId("n2");
        edge.setEdgeType("CALLS");
        edge.setConfidence(new BigDecimal("0.7"));
        edge.setStatus("CONFIRMED");

        when(vectorRetrievalService.semanticSearch(eq("proj-1"), eq("v1"), anyString(), anyInt(), isNull()))
                .thenReturn(List.of());
        when(vectorRetrievalService.findSimilarNodes(eq("proj-1"), eq("v1"), anyString(), anyDouble()))
                .thenReturn(List.of(node));
        when(neo4jGraphDao.queryEdges(eq("proj-1"), eq("v1"), isNull(), isNull(), eq("n1"), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(edge));
        when(neo4jGraphDao.findNodesByIds(eq(List.of("n2")))).thenReturn(List.of(neighbor));

        QaAnswer llmAnswer = new QaAnswer();
        llmAnswer.setAnswer("用户注册会调用 ProfileService。");
        llmAnswer.setConfidence(0.8);
        when(llmGateway.callWithTemplate(eq("proj-1"), eq("qa-answer"), anyMap(), eq(QaAnswer.class)))
                .thenReturn(llmAnswer);

        QaAnswer result = qaAgent.answer("proj-1", "v1", "用户注册调用哪些服务？");

        verify(llmGateway).callWithTemplate(eq("proj-1"), eq("qa-answer"),
                variablesCaptor.capture(), eq(QaAnswer.class));
        String context = variablesCaptor.getValue().get("context");
        assertTrue(context.contains("ProfileService"));
        assertTrue(context.contains("CALLS"));
        assertTrue(result.getEvidences().stream().anyMatch(e -> "GRAPH_EDGE".equals(e.getSourceKind())));
    }

    @Test
    void testAnswer_EmptyQuestion_ShortCircuits() {
        QaAnswer result = qaAgent.answer("proj-1", "v1", "  ");
        assertNotNull(result);
        assertEquals(0.0, result.getConfidence());
        verifyNoInteractions(llmGateway);
        verifyNoInteractions(vectorRetrievalService);
    }

    @Test
    void testAnswer_NoContext_StillCallsLlmWithPlaceholder() {
        when(vectorRetrievalService.semanticSearch(any(), any(), anyString(), anyInt(), isNull()))
                .thenReturn(List.of());
        when(vectorRetrievalService.findSimilarNodes(any(), any(), anyString(), anyDouble()))
                .thenReturn(List.of());

        QaAnswer llmAnswer = new QaAnswer();
        llmAnswer.setAnswer("现有图谱信息不足以回答。");
        llmAnswer.setConfidence(0.1);
        when(llmGateway.callWithTemplate(any(), eq("qa-answer"), anyMap(), eq(QaAnswer.class)))
                .thenReturn(llmAnswer);

        QaAnswer result = qaAgent.answer("proj-1", null, "无关问题");

        assertNotNull(result);
        assertTrue(result.getEvidences().isEmpty());
        verify(llmGateway).callWithTemplate(any(), eq("qa-answer"), variablesCaptor.capture(), eq(QaAnswer.class));
        assertTrue(variablesCaptor.getValue().get("context").contains("未检索到相关上下文"));
    }

    @Test
    void testAnswer_RetrievalFailure_DegradesGracefully() {
        when(vectorRetrievalService.semanticSearch(any(), any(), anyString(), anyInt(), isNull()))
                .thenThrow(new RuntimeException("pgvector down"));
        when(vectorRetrievalService.findSimilarNodes(any(), any(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("pgvector down"));

        QaAnswer llmAnswer = new QaAnswer();
        llmAnswer.setAnswer("信息不足");
        llmAnswer.setConfidence(0.0);
        when(llmGateway.callWithTemplate(any(), eq("qa-answer"), anyMap(), eq(QaAnswer.class)))
                .thenReturn(llmAnswer);

        QaAnswer result = qaAgent.answer("proj-1", "v1", "问题");
        assertNotNull(result, "检索失败时应降级而非抛出");
        assertTrue(result.getEvidences().isEmpty());
    }
}
