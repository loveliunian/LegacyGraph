package io.github.legacygraph.service;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.qa.VectorizationService;

@ExtendWith(MockitoExtension.class)
class VectorizationServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorDocumentRepository vectorDocumentRepository;

    private VectorizationService vectorizationService;

    // L14 修复：使用构造函数注入替代反射，消除对内部字段名变更的脆弱依赖
    @BeforeEach
    void setUp() {
        vectorizationService = new VectorizationService(vectorDocumentRepository, embeddingModel);
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
        ArgumentCaptor<VectorDocument> docCaptor = ArgumentCaptor.forClass(VectorDocument.class);
        verify(vectorDocumentRepository, times(1)).insert(docCaptor.capture());
        VectorDocument savedDoc = docCaptor.getValue();
        assertEquals("[0.1,0.2,0.3,0.4]", savedDoc.getEmbedding());
        assertEquals(4, savedDoc.getEmbeddingDim());
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

    @Test
    void embedDocument_allowsSameSourceUriInDifferentVersion() {
        String sourceUri = "/docs/design.md";
        when(vectorDocumentRepository.countBySourceUriAndVersionId(sourceUri, "v2")).thenReturn(0);
        when(embeddingModel.embed("设计文档")).thenReturn(new float[]{0.1f});
        when(vectorDocumentRepository.insert(any(VectorDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, VectorDocument.class).setId(2L);
            return 1;
        });

        int stored = vectorizationService.embedDocument(
                "1001", "v2", "DOC", sourceUri, "设计文档", 1000, 100, "model");

        assertEquals(1, stored);
        verify(vectorDocumentRepository).countBySourceUriAndVersionId(sourceUri, "v2");
        verify(vectorDocumentRepository, never()).countBySourceUri(sourceUri);
    }

    // ===== Task 12: chunk 级 diff 增量向量化测试 =====

    @Test
    void embedDocumentIncremental_noOldChunks_insertsAll() {
        // 旧 chunk 为空 → 所有新 chunk 都插入
        String content = "fresh content";
        when(vectorDocumentRepository.findBySourceUriAndVersionId("/a.md", "v1"))
                .thenReturn(Collections.emptyList());
        when(embeddingModel.embed(content)).thenReturn(new float[]{0.1f, 0.2f});
        when(vectorDocumentRepository.insert(any(VectorDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, VectorDocument.class).setId(10L);
            return 1;
        });

        int stored = vectorizationService.embedDocumentIncremental(
                "1001", "v1", "DOC", "/a.md", content, 1000, 100, "model");

        assertEquals(1, stored);
        verify(vectorDocumentRepository, never()).deleteById(anyLong());
        verify(vectorDocumentRepository, times(1)).insert(any(VectorDocument.class));
    }

    @Test
    void embedDocumentIncremental_allUnchanged_retainsOldChunks() {
        // 旧 chunk 的 contentSha256 与新内容一致 → 不删除、不插入
        String content = "unchanged content";
        String sha = sha256Hex(content);
        VectorDocument oldDoc = new VectorDocument();
        oldDoc.setId(1L);
        oldDoc.setContentSha256(sha);

        when(vectorDocumentRepository.findBySourceUriAndVersionId("/a.md", "v1"))
                .thenReturn(Collections.singletonList(oldDoc));

        int stored = vectorizationService.embedDocumentIncremental(
                "1001", "v1", "DOC", "/a.md", content, 1000, 100, "model");

        assertEquals(0, stored);
        verify(vectorDocumentRepository, never()).deleteById(anyLong());
        verify(vectorDocumentRepository, never()).insert(any(VectorDocument.class));
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void embedDocumentIncremental_changedChunk_deletesOldAndInsertsNew() {
        // 旧 chunk 内容变更 → 删除旧 chunk + 插入新 chunk
        String newContent = "new content";
        String oldSha = sha256Hex("old content");
        VectorDocument oldDoc = new VectorDocument();
        oldDoc.setId(7L);
        oldDoc.setContentSha256(oldSha);

        when(vectorDocumentRepository.findBySourceUriAndVersionId("/a.md", "v1"))
                .thenReturn(Collections.singletonList(oldDoc));
        when(embeddingModel.embed(newContent)).thenReturn(new float[]{0.3f, 0.4f});
        when(vectorDocumentRepository.insert(any(VectorDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, VectorDocument.class).setId(20L);
            return 1;
        });

        int stored = vectorizationService.embedDocumentIncremental(
                "1001", "v1", "DOC", "/a.md", newContent, 1000, 100, "model");

        assertEquals(1, stored);
        verify(vectorDocumentRepository).deleteById(7L);
        verify(vectorDocumentRepository, times(1)).insert(any(VectorDocument.class));
    }

    @Test
    void embedDocumentIncremental_partialUnchange_keepsSomeDeletesSome() {
        // 构造可切分为多片的内容：第一片与某个旧 chunk 相同（保留），另有 stale 旧 chunk（删除），其余新片（插入）
        String content = "part0_content\npart1_new_content";
        int maxChars = 15;
        int overlap = 2;
        // 用与生产代码相同的分片逻辑拿到真实 chunk，用于构造「未变更」旧 chunk 的 contentSha256
        List<String> realChunks = vectorizationService.chunkDocument(content, maxChars, overlap);
        assertTrue(realChunks.size() >= 2, "前提：内容应被切分为至少 2 个 chunk");

        String sha0 = sha256Hex(realChunks.get(0));
        VectorDocument oldMatching = new VectorDocument();
        oldMatching.setId(1L);
        oldMatching.setContentSha256(sha0);
        VectorDocument oldStale = new VectorDocument();
        oldStale.setId(2L);
        oldStale.setContentSha256(sha256Hex("stale_content"));

        when(vectorDocumentRepository.findBySourceUriAndVersionId("/a.md", "v1"))
                .thenReturn(Arrays.asList(oldMatching, oldStale));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.5f});
        when(vectorDocumentRepository.insert(any(VectorDocument.class))).thenAnswer(inv -> {
            inv.getArgument(0, VectorDocument.class).setId(30L);
            return 1;
        });

        int stored = vectorizationService.embedDocumentIncremental(
                "1001", "v1", "DOC", "/a.md", content, maxChars, overlap, "model");

        assertTrue(stored >= 1, "应至少插入一个新 chunk");
        verify(vectorDocumentRepository).deleteById(2L);          // stale 被删除
        verify(vectorDocumentRepository, never()).deleteById(1L); // matching 保留
    }

    @Test
    void embedDocumentIncremental_emptyContent_returnsZero() {
        int stored = vectorizationService.embedDocumentIncremental(
                "1001", "v1", "DOC", "/a.md", "", 1000, 100, "model");

        assertEquals(0, stored);
        verify(vectorDocumentRepository, never()).findBySourceUriAndVersionId(anyString(), anyString());
        verify(vectorDocumentRepository, never()).insert(any(VectorDocument.class));
    }

    @Test
    void deleteBySourceUriAndVersion_delegatesToRepo() {
        when(vectorDocumentRepository.deleteBySourceUriAndVersion("/a.md", "v1")).thenReturn(3);

        int deleted = vectorizationService.deleteBySourceUriAndVersion("/a.md", "v1");

        assertEquals(3, deleted);
        verify(vectorDocumentRepository).deleteBySourceUriAndVersion("/a.md", "v1");
        // 不应调用跨版本的 deleteBySourceUri
        verify(vectorDocumentRepository, never()).deleteBySourceUri(anyString());
    }

    @Test
    void deleteBySourceUriAndVersion_blankInput_returnsZero() {
        int deleted = vectorizationService.deleteBySourceUriAndVersion("", "v1");
        assertEquals(0, deleted);
        verify(vectorDocumentRepository, never()).deleteBySourceUriAndVersion(anyString(), anyString());
    }

    @Test
    void findBySourceUriAndVersionId_delegatesToRepo() {
        VectorDocument doc = new VectorDocument();
        doc.setId(1L);
        doc.setContentSha256("abc");
        List<VectorDocument> expected = Collections.singletonList(doc);
        when(vectorDocumentRepository.findBySourceUriAndVersionId("/a.md", "v1")).thenReturn(expected);

        List<VectorDocument> result = vectorizationService.findBySourceUriAndVersionId("/a.md", "v1");

        assertSame(expected, result);
        verify(vectorDocumentRepository).findBySourceUriAndVersionId("/a.md", "v1");
    }

    @Test
    void findBySourceUriAndVersionId_blankInput_returnsEmpty() {
        List<VectorDocument> result = vectorizationService.findBySourceUriAndVersionId("", "v1");
        assertTrue(result.isEmpty());
        verify(vectorDocumentRepository, never()).findBySourceUriAndVersionId(anyString(), anyString());
    }

    /** 与 VectorizationService.sha256Hex 相同算法的测试辅助方法 */
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
