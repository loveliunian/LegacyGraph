package io.github.legacygraph.service;

import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.VectorDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorRetrievalServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorDocumentRepository vectorDocumentRepository;

    @Mock
    private GraphNodeRepository graphNodeRepository;

    @Mock
    private VectorizationService vectorizationService;

    private VectorRetrievalService vectorRetrievalService;

    @BeforeEach
    void setUp() {
        vectorRetrievalService = new VectorRetrievalService(
                embeddingModel, vectorDocumentRepository, graphNodeRepository, vectorizationService);
    }

    @Test
    void testSemanticSearch_Success() {
        // given
        String query = "用户管理接口";
        float[] mockEmbedding = {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed(query)).thenReturn(mockEmbedding);

        VectorDocument doc1 = new VectorDocument();
        doc1.setId(1L);
        doc1.setContent("用户管理文档");
        VectorDocument doc2 = new VectorDocument();
        doc2.setId(2L);
        doc2.setContent("权限控制文档");
        List<VectorDocument> mockResults = Arrays.asList(doc1, doc2);
        when(vectorDocumentRepository.findSimilar(
                eq("project-1"), eq("v1"), anyList(), eq(10), eq("CODE_SNIPPET")))
                .thenReturn(mockResults);

        // when
        List<VectorDocument> results = vectorRetrievalService.semanticSearch(
                "project-1", "v1", query, 10, "CODE_SNIPPET");

        // then
        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals("用户管理文档", results.get(0).getContent());
        verify(embeddingModel, times(1)).embed(query);
    }

    @Test
    void testSemanticSearch_EmptyQuery() {
        String query = "";
        float[] mockEmbedding = new float[0];
        when(embeddingModel.embed(query)).thenReturn(mockEmbedding);

        when(vectorDocumentRepository.findSimilar(
                anyString(), anyString(), anyList(), anyInt(), anyString()))
                .thenReturn(Collections.emptyList());

        List<VectorDocument> results = vectorRetrievalService.semanticSearch(
                "project-1", "v1", query, 5, null);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testSemanticSearch_EmbeddingThrowsException_ReturnsEmpty() {
        String query = "破碎查询";
        when(embeddingModel.embed(query)).thenThrow(new RuntimeException("API 调用失败"));

        List<VectorDocument> results = vectorRetrievalService.semanticSearch(
                "project-1", "v1", query, 5, null);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testFindSimilarNodes_MapsDocumentsToNodes() {
        // given
        String searchText = "订单处理";
        float[] mockEmbedding = {0.5f, 0.6f, 0.7f};
        when(embeddingModel.embed(searchText)).thenReturn(mockEmbedding);

        VectorDocument doc1 = new VectorDocument();
        doc1.setSourceUri("node-1");
        doc1.setContent("订单创建");
        VectorDocument doc2 = new VectorDocument();
        doc2.setSourceUri("node-2");
        doc2.setContent("订单查询");
        when(vectorDocumentRepository.findSimilar(
                eq("project-1"), eq("v1"), anyList(), eq(20), isNull()))
                .thenReturn(Arrays.asList(doc1, doc2));

        GraphNode node1 = new GraphNode();
        node1.setId("node-1");
        node1.setNodeType("ApiEndpoint");
        node1.setNodeName("创建订单接口");
        node1.setConfidence(BigDecimal.valueOf(0.9));
        GraphNode node2 = new GraphNode();
        node2.setId("node-2");
        node2.setNodeType("ApiEndpoint");
        node2.setNodeName("查询订单接口");
        node2.setConfidence(BigDecimal.valueOf(0.8));
        when(graphNodeRepository.selectById("node-1")).thenReturn(node1);
        when(graphNodeRepository.selectById("node-2")).thenReturn(node2);

        // when
        List<GraphNode> results = vectorRetrievalService.findSimilarNodes(
                "project-1", "v1", searchText, 0.7);

        // then
        assertNotNull(results);
        assertEquals(2, results.size());
        assertEquals("创建订单接口", results.get(0).getNodeName());
        assertEquals("查询订单接口", results.get(1).getNodeName());
    }

    @Test
    void testFindSimilarNodes_EmptyResults() {
        String searchText = "不存在的概念";
        float[] mockEmbedding = {0.1f, 0.2f};
        when(embeddingModel.embed(searchText)).thenReturn(mockEmbedding);
        when(vectorDocumentRepository.findSimilar(
                anyString(), anyString(), anyList(), anyInt(), isNull()))
                .thenReturn(Collections.emptyList());

        List<GraphNode> results = vectorRetrievalService.findSimilarNodes(
                "project-1", "v1", searchText, 0.7);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testFindSimilarNodes_ThrowsException_ReturnsEmpty() {
        String searchText = "错误场景";
        when(embeddingModel.embed(searchText)).thenThrow(new RuntimeException("API 错误"));

        List<GraphNode> results = vectorRetrievalService.findSimilarNodes(
                "project-1", "v1", searchText, 0.7);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testBatchUpsertVectors_DelegatesToVectorizationService() {
        // given
        VectorDocument doc1 = new VectorDocument();
        doc1.setProjectId(1001L);
        doc1.setChunkType("CODE");
        doc1.setSourceUri("/path/Test.java");
        doc1.setContent("public class Test {}");
        doc1.setEmbeddingModel("text-embedding-3-small");

        VectorDocument doc2 = new VectorDocument();
        doc2.setProjectId(1001L);
        doc2.setChunkType("DOC");
        doc2.setSourceUri("/path/doc.md");
        doc2.setContent("用户文档内容");
        doc2.setEmbeddingModel("text-embedding-3-small");

        List<VectorDocument> documents = Arrays.asList(doc1, doc2);

        // 模拟 embedAndStore 返回
        when(vectorizationService.embedAndStore(
                eq(1001L), anyString(), eq("CODE"), eq("/path/Test.java"), eq(0),
                eq("public class Test {}"), eq("text-embedding-3-small")))
                .thenReturn(1L);
        when(vectorizationService.embedAndStore(
                eq(1001L), anyString(), eq("DOC"), eq("/path/doc.md"), eq(0),
                eq("用户文档内容"), eq("text-embedding-3-small")))
                .thenReturn(2L);

        // when
        vectorRetrievalService.batchUpsertVectors("1001", documents);

        // then
        verify(vectorizationService, times(2)).embedAndStore(
                anyLong(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void testBatchUpsertVectors_SkipsEmptyContent() {
        VectorDocument doc = new VectorDocument();
        doc.setProjectId(1001L);
        doc.setChunkType("CODE");
        doc.setSourceUri("/path/empty.java");
        doc.setContent("");  // 空内容，应被跳过
        doc.setEmbeddingModel("model");

        vectorRetrievalService.batchUpsertVectors("1001", Collections.singletonList(doc));

        verify(vectorizationService, never()).embedAndStore(
                anyLong(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }
}
