package io.github.legacygraph.service.qa;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.service.rerank.DocumentReranker;
import io.github.legacygraph.service.rerank.KeywordReranker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReRankingServiceTest {

    @Test
    void constructor_prefersNonKeywordReranker() {
        // given: 一个 cross-encoder 和一个 keyword reranker
        DocumentReranker crossEncoder = mock(DocumentReranker.class);
        when(crossEncoder.name()).thenReturn("cross-encoder");

        DocumentReranker keyword = mock(DocumentReranker.class);
        when(keyword.name()).thenReturn("keyword");

        // when
        ReRankingService service = new ReRankingService(List.of(keyword, crossEncoder));

        // then: 委托给 cross-encoder
        VectorDocument doc = new VectorDocument();
        doc.setId(1L);
        when(crossEncoder.rerank("查询", List.of(doc), 5)).thenReturn(List.of(doc));

        List<VectorDocument> result = service.reRank("查询", List.of(doc), 5);

        assertEquals(1, result.size());
        verify(crossEncoder).rerank("查询", List.of(doc), 5);
        verify(keyword, never()).rerank(any(), any(), anyInt());
    }

    @Test
    void constructor_fallsBackToKeywordReranker() {
        // given: 只有 keyword reranker
        DocumentReranker keyword = mock(DocumentReranker.class);
        when(keyword.name()).thenReturn("keyword");

        // when
        ReRankingService service = new ReRankingService(List.of(keyword));

        // then: 使用 keyword
        VectorDocument doc = new VectorDocument();
        doc.setId(1L);
        when(keyword.rerank("查询", List.of(doc), 5)).thenReturn(List.of(doc));

        List<VectorDocument> result = service.reRank("查询", List.of(doc), 5);

        assertEquals(1, result.size());
        verify(keyword).rerank("查询", List.of(doc), 5);
    }

    @Test
    void constructor_noRerankers_usesDefaultKeywordReranker() {
        // given: 空的 reranker 列表
        ReRankingService service = new ReRankingService(List.of());

        // then: 使用默认的 KeywordReranker，不抛异常
        VectorDocument doc = new VectorDocument();
        doc.setId(1L);
        doc.setContent("测试内容包含查询词");

        List<VectorDocument> result = service.reRank("测试", List.of(doc), 5);

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void reRank_delegatesToSelectedReranker() {
        DocumentReranker mockReranker = mock(DocumentReranker.class);
        when(mockReranker.name()).thenReturn("custom");

        VectorDocument doc1 = new VectorDocument();
        doc1.setId(1L);
        VectorDocument doc2 = new VectorDocument();
        doc2.setId(2L);

        when(mockReranker.rerank("查询", List.of(doc1, doc2), 1))
                .thenReturn(List.of(doc1));

        ReRankingService service = new ReRankingService(List.of(mockReranker));

        List<VectorDocument> result = service.reRank("查询", List.of(doc1, doc2), 1);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
        verify(mockReranker).rerank("查询", List.of(doc1, doc2), 1);
    }
}
