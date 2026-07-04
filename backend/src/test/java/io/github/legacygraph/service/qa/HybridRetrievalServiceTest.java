package io.github.legacygraph.service.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.repository.VectorDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HybridRetrievalServiceTest {

    @Mock
    private VectorDocumentRepository vectorDocumentRepository;

    @Mock
    private DocChunkRepository docChunkRepository;

    @Mock
    private VectorRetrievalService vectorRetrievalService;

    private HybridRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new HybridRetrievalService(vectorDocumentRepository, docChunkRepository, vectorRetrievalService);
    }

    @Test
    void retrieve_mergesVectorAndKeywordResults() {
        // given
        VectorDocument doc1 = new VectorDocument();
        doc1.setId(1L);
        doc1.setContent("向量检索结果");

        VectorDocument doc2 = new VectorDocument();
        doc2.setId(2L);
        doc2.setContent("关键词检索结果");

        when(vectorRetrievalService.semanticSearch(eq("p1"), eq("v1"), eq("测试查询"), eq(10), isNull()))
                .thenReturn(List.of(doc1));
        when(vectorDocumentRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc2));

        // when
        List<VectorDocument> result = service.retrieve("p1", "v1", "测试查询", null, 10);

        // then
        assertEquals(2, result.size());
        verify(vectorRetrievalService).semanticSearch("p1", "v1", "测试查询", 10, null);
    }

    @Test
    void retrieve_deduplicatesById() {
        // given: 向量检索和关键词检索返回相同文档
        VectorDocument doc = new VectorDocument();
        doc.setId(1L);
        doc.setContent("共同结果");

        when(vectorRetrievalService.semanticSearch(eq("p1"), eq("v1"), eq("查询"), eq(10), isNull()))
                .thenReturn(List.of(doc));
        when(vectorDocumentRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(doc));

        // when
        List<VectorDocument> result = service.retrieve("p1", "v1", "查询", null, 10);

        // then: 去重后只有1条
        assertEquals(1, result.size());
    }

    @Test
    void retrieve_withQueryVariants_includesVariantResults() {
        VectorDocument mainDoc = new VectorDocument();
        mainDoc.setId(1L);
        mainDoc.setContent("主查询结果");

        VectorDocument variantDoc = new VectorDocument();
        variantDoc.setId(2L);
        variantDoc.setContent("变体查询结果");

        when(vectorRetrievalService.semanticSearch(eq("p1"), eq("v1"), eq("原始查询"), eq(10), isNull()))
                .thenReturn(List.of(mainDoc));
        when(vectorRetrievalService.semanticSearch(eq("p1"), eq("v1"), eq("变体1"), eq(5), isNull()))
                .thenReturn(List.of(variantDoc));
        when(vectorDocumentRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());

        List<VectorDocument> result = service.retrieve("p1", "v1", "原始查询", List.of("变体1"), 10);

        assertEquals(2, result.size());
        verify(vectorRetrievalService).semanticSearch("p1", "v1", "变体1", 5, null);
    }

    @Test
    void retrieve_vectorSearchFails_stillReturnsKeywordResults() {
        VectorDocument keywordDoc = new VectorDocument();
        keywordDoc.setId(1L);
        keywordDoc.setContent("关键词结果");

        when(vectorRetrievalService.semanticSearch(any(), any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("向量服务不可用"));
        when(vectorDocumentRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(keywordDoc));

        List<VectorDocument> result = service.retrieve("p1", "v1", "查询", null, 10);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void retrieve_keywordSearchFails_stillReturnsVectorResults() {
        VectorDocument vectorDoc = new VectorDocument();
        vectorDoc.setId(1L);
        vectorDoc.setContent("向量结果");

        when(vectorRetrievalService.semanticSearch(eq("p1"), eq("v1"), eq("查询"), eq(10), isNull()))
                .thenReturn(List.of(vectorDoc));
        when(vectorDocumentRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenThrow(new RuntimeException("数据库不可用"));

        List<VectorDocument> result = service.retrieve("p1", "v1", "查询", null, 10);

        assertEquals(1, result.size());
    }

    @Test
    void retrieve_allFail_returnsEmptyList() {
        when(vectorRetrievalService.semanticSearch(any(), any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("向量服务不可用"));
        when(vectorDocumentRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenThrow(new RuntimeException("数据库不可用"));

        List<VectorDocument> result = service.retrieve("p1", "v1", "查询", null, 10);

        assertTrue(result.isEmpty());
    }

    @Test
    void retrieve_emptyResults_returnsEmptyList() {
        when(vectorRetrievalService.semanticSearch(eq("p1"), eq("v1"), eq("查询"), eq(10), isNull()))
                .thenReturn(List.of());
        when(vectorDocumentRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of());

        List<VectorDocument> result = service.retrieve("p1", "v1", "查询", null, 10);

        assertTrue(result.isEmpty());
    }
}
