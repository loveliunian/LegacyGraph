package io.github.legacygraph.service;

import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.system.CacheService;
import io.github.legacygraph.service.qa.VectorRetrievalService;
import io.github.legacygraph.service.qa.VectorizationService;

@ExtendWith(MockitoExtension.class)
class VectorRetrievalServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private VectorDocumentRepository vectorDocumentRepository;
    @Mock
    private Neo4jGraphDao neo4jGraphDao;
    @Mock
    private VectorizationService vectorizationService;
    @Mock
    private io.github.legacygraph.repository.SemanticCacheRepository semanticCacheRepository;

    private VectorRetrievalService vectorRetrievalService;

    @BeforeEach
    void setUp() {
        vectorRetrievalService = new VectorRetrievalService(
                vectorDocumentRepository, neo4jGraphDao, vectorizationService, semanticCacheRepository);
        setField(vectorRetrievalService, "embeddingModel", embeddingModel);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testSemanticSearch_Success() {
        String query = "用户管理";
        float[] mockEmbedding = {0.1f, 0.2f};
        when(embeddingModel.embed(query)).thenReturn(mockEmbedding);

        VectorDocument doc = new VectorDocument();
        doc.setId(1L);
        doc.setContent("用户管理文档");
        when(vectorDocumentRepository.findSimilar(
                anyString(), anyString(), anyList(), anyInt(), anyString()))
                .thenReturn(Collections.singletonList(doc));

        List<VectorDocument> results = vectorRetrievalService.semanticSearch(
                "project-1", "v1", query, 10, "CODE_SNIPPET");

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("用户管理文档", results.get(0).getContent());
    }

    @Test
    void testSemanticSearch_ExceptionReturnsEmpty() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("API error"));

        List<VectorDocument> results = vectorRetrievalService.semanticSearch(
                "p1", "v1", "query", 5, null);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void testBatchUpsertVectors_SkipsEmptyContent() {
        VectorDocument doc = new VectorDocument();
        doc.setProjectId("1001");
        doc.setChunkType("CODE");
        doc.setSourceUri("/path/Test.java");
        doc.setContent("");
        doc.setEmbeddingModel("model");

        vectorRetrievalService.batchUpsertVectors("1001", "v1", Collections.singletonList(doc));

        verify(vectorizationService, never()).embedAndStore(
                anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void testFindSimilarNodes_UsesBatchQuery() {
        String searchText = "订单";
        float[] embedding = {0.5f, 0.6f};
        when(embeddingModel.embed(searchText)).thenReturn(embedding);

        VectorDocument doc = new VectorDocument();
        doc.setSourceUri("node-1");
        when(vectorDocumentRepository.findSimilar(
                anyString(), anyString(), anyList(), eq(20), isNull()))
                .thenReturn(Collections.singletonList(doc));

        GraphNode node = new GraphNode();
        node.setId("node-1");
        node.setNodeName("订单节点");
        // 批量查询替代逐条 findNodeById
        when(neo4jGraphDao.findNodesByIds(List.of("node-1"))).thenReturn(List.of(node));

        List<GraphNode> results = vectorRetrievalService.findSimilarNodes(
                "p1", "v1", searchText, 0.7);

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("订单节点", results.get(0).getNodeName());
        verify(neo4jGraphDao).findNodesByIds(anyList());
        verify(neo4jGraphDao, never()).findNodeById(anyString());
    }

    @Test
    void testSemanticSearch_CacheHit_SkipsEmbedding() {
        // 注入 CacheService 并命中缓存：不应触发 embedding 与 pgvector 查询
        CacheService cacheService = mock(CacheService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                vectorRetrievalService, "cacheService", cacheService);

        VectorDocument cachedDoc = new VectorDocument();
        cachedDoc.setContent("缓存命中文档");
        when(cacheService.get(anyString(), eq(List.class)))
                .thenReturn(Collections.singletonList(cachedDoc));

        List<VectorDocument> results = vectorRetrievalService.semanticSearch(
                "p1", "v1", "重复问题", 5, "DOC_CHUNK");

        assertEquals(1, results.size());
        assertEquals("缓存命中文档", results.get(0).getContent());
        verify(embeddingModel, never()).embed(anyString());
        verify(vectorDocumentRepository, never()).findSimilar(any(), any(), anyList(), anyInt(), any());
    }

    @Test
    void testSemanticSearch_CacheMiss_QueriesAndBackfills() {
        CacheService cacheService = mock(CacheService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                vectorRetrievalService, "cacheService", cacheService);
        when(cacheService.get(anyString(), eq(List.class))).thenReturn(null);

        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        VectorDocument doc = new VectorDocument();
        doc.setContent("新查询结果");
        when(vectorDocumentRepository.findSimilar(anyString(), anyString(), anyList(), anyInt(), anyString()))
                .thenReturn(Collections.singletonList(doc));

        List<VectorDocument> results = vectorRetrievalService.semanticSearch(
                "p1", "v1", "新问题", 5, "DOC_CHUNK");

        assertEquals(1, results.size());
        // 未命中时回填缓存
        verify(cacheService).put(anyString(), anyList(), any());
    }

    /**
     * P1-4 端到端链路：长文档分片 -> 逐片 embedding 存储 -> 语义检索召回。
     * 使用真实 VectorizationService（仅 mock embedding 模型与持久层），验证整条链路打通。
     */
    @Test
    void testEndToEnd_ChunkEmbedStoreThenSearch() {
        // 真实分片 + 真实存储逻辑，仅 mock 外部 embedding 与 repository
        VectorizationService realVectorization =
                new VectorizationService(vectorDocumentRepository);
        VectorRetrievalService retrieval = new VectorRetrievalService(
                vectorDocumentRepository, neo4jGraphDao, realVectorization, semanticCacheRepository);
        setField(realVectorization, "embeddingModel", embeddingModel);
        setField(retrieval, "embeddingModel", embeddingModel);

        // 任意文本都返回固定向量；插入时回填自增主键
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(vectorDocumentRepository.insert(any(VectorDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, VectorDocument.class).setId(99L);
            return 1;
        });

        // 1) 分片
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append("第").append(i).append("段：业务文档内容，用于验证分片与向量化链路。\n");
        }
        List<String> chunks = realVectorization.chunkDocument(sb.toString(), 40, 8);
        assertTrue(chunks.size() > 1, "长文档应被分成多个 chunk");

        // 2) 逐片 embedding 存储
        int idx = 0;
        for (String chunk : chunks) {
            Long id = realVectorization.embedAndStore(
                    "1001", "v1", "DOC_CHUNK", "/docs/spec.md", idx++, chunk, "text-embedding-3-small");
            assertNotNull(id);
        }
        // 每个 chunk 都落库一次
        verify(vectorDocumentRepository, times(chunks.size())).insert(any(VectorDocument.class));

        // 3) 语义检索召回（pgvector 查询用 mock 返回首个 chunk）
        VectorDocument hit = new VectorDocument();
        hit.setId(1L);
        hit.setContent(chunks.get(0));
        when(vectorDocumentRepository.findSimilar(
                eq("1001"), eq("v1"), anyList(), eq(5), eq("DOC_CHUNK")))
                .thenReturn(Collections.singletonList(hit));

        List<VectorDocument> found = retrieval.semanticSearch("1001", "v1", "业务文档", 5, "DOC_CHUNK");
        assertEquals(1, found.size());
        assertEquals(chunks.get(0), found.get(0).getContent());
    }
}
