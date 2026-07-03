package io.github.legacygraph.service;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorizationServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorDocumentRepository vectorDocumentRepository;

    private VectorizationService vectorizationService;

    @BeforeEach
    void setUp() {
        vectorizationService = new VectorizationService(vectorDocumentRepository);
        try {
            var f = VectorizationService.class.getDeclaredField("embeddingModel");
            f.setAccessible(true);
            f.set(vectorizationService, embeddingModel);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testEmbedAndStore_Success() {
        // given
        String content = "测试文档内容";
        float[] mockEmbedding = {0.1f, 0.2f, 0.3f, 0.4f};
        when(embeddingModel.embed(content)).thenReturn(mockEmbedding);
        // 模拟 DB 在 insert 时回填自增主键（@TableId(IdType.AUTO)）
        when(vectorDocumentRepository.insert(any(VectorDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, VectorDocument.class).setId(1L);
            return 1;
        });

        // when
        Long resultId = vectorizationService.embedAndStore(
                "1001", "v1", "CODE_SNIPPET", "/src/main/java/Test.java",
                0, content, "text-embedding-3-small");

        // then
        assertNotNull(resultId);
        verify(vectorDocumentRepository, times(1)).insert(any(VectorDocument.class));
        verify(embeddingModel, times(1)).embed(content);
    }

    @Test
    void testEmbedAndStore_EmptyContent() {
        // given: empty content — embedAndStore 不再调用 embed 对空内容，直接返回 null
        String content = "";

        // when
        Long resultId = vectorizationService.embedAndStore(
                "1001", "v1", "CODE_SNIPPET", "/src/main/java/Test.java",
                0, content, "text-embedding-3-small");

        // then — 空内容直接返回 null，不调用 embedding 和 insert
        assertNull(resultId);
        verify(vectorDocumentRepository, never()).insert(any(VectorDocument.class));
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void testChunkDocument_ShortContent() {
        String content = "短文本";
        List<String> chunks = vectorizationService.chunkDocument(content, 1000, 100);

        assertEquals(1, chunks.size());
        assertEquals(content, chunks.get(0));
    }

    @Test
    void testChunkDocument_LongContent() {
        // 构造超过 maxChars 的文本（小数据量避免 OOM）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append("第").append(i).append("行的内容是一些测试数据，用于验证分片逻辑。\n");
        }
        String content = sb.toString();

        List<String> chunks = vectorizationService.chunkDocument(content, 50, 10);

        assertTrue(chunks.size() > 1, "长文本应被切分成多个 chunk");
        for (String chunk : chunks) {
            assertFalse(chunk.isEmpty(), "每个 chunk 不应为空");
        }
    }

    @Test
    void testChunkDocument_NullContent() {
        List<String> chunks = vectorizationService.chunkDocument(null, 1000, 100);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testChunkDocument_EmptyContent() {
        List<String> chunks = vectorizationService.chunkDocument("", 1000, 100);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testChunkDocument_NewlineSplit() {
        // 验证在换行处截断
        String content = "第一行内容长度超过 maxChars。\n" +
                         "第二行内容开始。\n" +
                         "第三行内容。\n";

        // maxChars 设为略小于第一行的长度，应触发 newline 截断
        List<String> chunks = vectorizationService.chunkDocument(content, 30, 5);

        assertTrue(chunks.size() >= 2, "应被切分为至少 2 个 chunk");
        assertTrue(chunks.get(0).contains("第一行"), "第一个 chunk 应包含第一行内容");
    }

    @Test
    void testCosineSimilarity_Identical() {
        List<Double> a = Arrays.asList(1.0, 2.0, 3.0);
        List<Double> b = Arrays.asList(1.0, 2.0, 3.0);

        double similarity = vectorizationService.cosineSimilarity(a, b);

        assertEquals(1.0, similarity, 0.0001);
    }

    @Test
    void testCosineSimilarity_Orthogonal() {
        List<Double> a = Arrays.asList(1.0, 0.0);
        List<Double> b = Arrays.asList(0.0, 1.0);

        double similarity = vectorizationService.cosineSimilarity(a, b);

        assertEquals(0.0, similarity, 0.0001);
    }

    @Test
    void testCosineSimilarity_DifferentLengths() {
        List<Double> a = Arrays.asList(1.0, 2.0);
        List<Double> b = Arrays.asList(1.0, 2.0, 3.0);

        double similarity = vectorizationService.cosineSimilarity(a, b);

        assertEquals(0.0, similarity, 0.0001);
    }

    @Test
    void testIsProbablyDuplicate_AboveThreshold() {
        List<Double> a = Arrays.asList(1.0, 2.0, 3.0);
        List<Double> b = Arrays.asList(1.05, 2.1, 2.9);  // 接近但不完全相同

        boolean duplicate = vectorizationService.isProbablyDuplicate(a, b);

        assertTrue(duplicate);
    }

    @Test
    void testIsProbablyDuplicate_BelowThreshold() {
        List<Double> a = Arrays.asList(1.0, 0.0, 0.0);
        List<Double> b = Arrays.asList(0.0, 1.0, 0.0);  // 正交向量，余弦相似度为 0

        boolean duplicate = vectorizationService.isProbablyDuplicate(a, b);

        assertFalse(duplicate);
    }

    @Test
    void testEmbedAndStore_DifferentContentHashes() {
        // 验证不同内容的 embedAndStore 生成不同的 content hash（通过验证各自调用了 insert）
        String contentA = "内容A";
        String contentB = "内容B";
        float[] embeddingA = {0.1f};
        float[] embeddingB = {0.2f};

        when(embeddingModel.embed(contentA)).thenReturn(embeddingA);
        when(embeddingModel.embed(contentB)).thenReturn(embeddingB);

        vectorizationService.embedAndStore("1001", "v1", "TEXT", "/a.txt", 0, contentA, "model");
        vectorizationService.embedAndStore("1001", "v1", "TEXT", "/b.txt", 0, contentB, "model");

        verify(vectorDocumentRepository, times(2)).insert(any(VectorDocument.class));
    }
}
