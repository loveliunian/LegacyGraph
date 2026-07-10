package io.github.legacygraph.task.step;

import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.repository.DocumentRepository;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocExtractStep 分段与合并逻辑测试。
 * <p>P0-2：验证 splitContent 返回带元数据的 DocChunk、mergeByKey 取较高置信度且合并 evidence、
 * 大文档分块能覆盖末尾内容（不再静默截断）。</p>
 * <p>Task 4：向量语义去重（semanticDeduplicateFeatures / cosineSimilarity）。</p>
 * <p>Task 6：大文档分段 2 路受限并行（extractFromChunks）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocExtractStepTest {

    @Mock private AiScanStepSupport support;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocUnderstandingAgent docUnderstandingAgent;
    @Mock private BusinessGraphBuilder businessGraphBuilder;
    @Mock private Neo4jGraphDao neo4jGraphDao;
    @Mock private Counter agentCallCounter;
    @Mock private Counter graphNodeCounter;
    @Mock private Counter graphEdgeCounter;

    /** 构造 DocExtractStep 实例（embeddingModel 默认为 null，需反射注入） */
    private DocExtractStep createStep() {
        return new DocExtractStep(support, documentRepository, docUnderstandingAgent,
                businessGraphBuilder, neo4jGraphDao, agentCallCounter, graphNodeCounter, graphEdgeCounter);
    }

    /** 反射调用 private extractFromChunks */
    private DocUnderstandingAgent.BusinessFactExtraction invokeExtractFromChunks(
            DocExtractStep step, String projectId, Document doc, String content) throws Exception {
        java.lang.reflect.Method m = DocExtractStep.class.getDeclaredMethod(
                "extractFromChunks", String.class, Document.class, String.class);
        m.setAccessible(true);
        return (DocUnderstandingAgent.BusinessFactExtraction) m.invoke(step, projectId, doc, content);
    }

    /** 反射调用带分块覆盖度的抽取方法。 */
    private DocExtractStep.ChunkExtractionResult invokeExtractFromChunksWithCoverage(
            DocExtractStep step, String projectId, Document doc, String content) throws Exception {
        java.lang.reflect.Method m = DocExtractStep.class.getDeclaredMethod(
                "extractFromChunksWithCoverage", String.class, Document.class, String.class);
        m.setAccessible(true);
        return (DocExtractStep.ChunkExtractionResult) m.invoke(step, projectId, doc, content);
    }

    /** 生成指定长度的重复文本（含换行，模拟真实文档） */
    private static String generateContent(int length) {
        StringBuilder sb = new StringBuilder(length);
        String unit = "测试文档内容。\n";
        while (sb.length() + unit.length() <= length) {
            sb.append(unit);
        }
        if (sb.length() < length) {
            sb.append(unit, 0, length - sb.length());
        }
        return sb.toString();
    }

    @Test
    void splitContent_largeDocUses800ChunkSize() {
        // 模拟大文档分段，使用 800 字符 chunk（对应 >50KB 文档的 LARGE_DOC_CHUNK_SIZE）
        String content = generateContent(3000);
        int chunkSize = 800;
        int overlap = 400;

        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, chunkSize, overlap);

        assertFalse(chunks.isEmpty(), "大文档应产生多个分段");
        // 每段长度不超过 chunkSize（最后一段可能更短）
        for (DocExtractStep.DocChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= chunkSize,
                    "分段长度 " + chunk.content().length() + " 超过 chunkSize " + chunkSize);
        }
        // 3000 / (800-400) ≈ 7-8 段
        assertTrue(chunks.size() > 3, "大文档应产生足够多的分段，实际: " + chunks.size());
    }

    @Test
    void splitContent_normalDocUses2500ChunkSize() {
        // 普通文档使用默认 2500 chunk size
        String content = generateContent(5000);
        int chunkSize = 2500;
        int overlap = 400;

        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, chunkSize, overlap);

        assertFalse(chunks.isEmpty());
        for (DocExtractStep.DocChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= chunkSize);
        }
        // 5000 / (2500-400) ≈ 2-3 段
        assertTrue(chunks.size() >= 2, "普通文档分段数应合理，实际: " + chunks.size());
    }

    @Test
    void splitContent_emptyOrNullReturnsEmpty() {
        assertTrue(DocExtractStep.splitContent(null, 800, 400).isEmpty());
        assertTrue(DocExtractStep.splitContent("", 800, 400).isEmpty());
    }

    @Test
    void splitContent_singleChunkWhenSmallerThanChunkSize() {
        String content = generateContent(500);
        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, 800, 400);
        assertEquals(1, chunks.size(), "小于 chunkSize 的内容应只产生 1 段");
        assertEquals(500, chunks.get(0).content().length());
    }

    @Test
    void splitContent_overlapProducesMultipleChunks() {
        // 验证 overlap 机制：内容远大于 chunkSize 时产生多段
        String content = generateContent(2000);
        int chunkSize = 500;
        int overlap = 200;

        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, chunkSize, overlap);

        assertTrue(chunks.size() >= 3, "应产生至少 3 段，实际: " + chunks.size());
        // 验证每段不超过 chunkSize
        for (DocExtractStep.DocChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= chunkSize);
        }
    }

    @Test
    void splitContent_chunkSizeParameterControlsSegmentSize() {
        // 核心验证：800 chunk size 产生的分段比 2500 chunk size 更小更多
        String content = generateContent(5000);

        List<DocExtractStep.DocChunk> smallChunks = DocExtractStep.splitContent(content, 800, 400);
        List<DocExtractStep.DocChunk> largeChunks = DocExtractStep.splitContent(content, 2500, 400);

        // 800 chunk 应产生更多分段
        assertTrue(smallChunks.size() > largeChunks.size(),
                "800 chunk 应比 2500 chunk 产生更多分段: " + smallChunks.size() + " vs " + largeChunks.size());

        // 验证 800 chunk 的每段确实更短
        int maxSmall = smallChunks.stream().mapToInt(c -> c.content().length()).max().orElse(0);
        int maxLarge = largeChunks.stream().mapToInt(c -> c.content().length()).max().orElse(0);
        assertTrue(maxSmall <= 800, "800 chunk 的最大段长应 <= 800, 实际: " + maxSmall);
        assertTrue(maxLarge <= 2500, "2500 chunk 的最大段长应 <= 2500, 实际: " + maxLarge);
    }

    // ──────────────────────────────────────────────
    // P0-2 新增：DocChunk 元数据正确性
    // ──────────────────────────────────────────────

    @Test
    void splitContent_docChunkMetadataCorrect() {
        // 验证 DocChunk 携带正确的 chunkIndex / charStart / charEnd
        String content = generateContent(2000);
        int chunkSize = 800;
        int overlap = 400;

        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, chunkSize, overlap);

        assertFalse(chunks.isEmpty());
        for (int i = 0; i < chunks.size(); i++) {
            DocExtractStep.DocChunk c = chunks.get(i);
            assertEquals(i, c.chunkIndex(), "chunkIndex 应从 0 递增");
            assertTrue(c.charStart() >= 0, "charStart 应非负");
            assertTrue(c.charEnd() > c.charStart(), "charEnd 应大于 charStart");
            assertTrue(c.charEnd() <= content.length(), "charEnd 不应超过内容总长度");
            // chunk.content 长度应等于 charEnd - charStart
            assertEquals(c.charEnd() - c.charStart(), c.content().length(),
                    "content 长度应等于 charEnd - charStart");
            // sectionTitle / pageNumber 暂为 null（待 Docling 集成）
            assertNull(c.sectionTitle(), "sectionTitle 当前应为 null");
            assertNull(c.pageNumber(), "pageNumber 当前应为 null");
        }
        // 第 0 块的 charStart 应为 0
        assertEquals(0, chunks.get(0).charStart(), "首块 charStart 应为 0");
    }

    @Test
    void splitContent_contentMatchesOriginalSubstring() {
        // 验证每个 chunk 的 content 确实是原文中 [charStart, charEnd) 的子串
        String content = generateContent(1500);
        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, 600, 300);

        assertFalse(chunks.isEmpty());
        for (DocExtractStep.DocChunk c : chunks) {
            assertEquals(content.substring(c.charStart(), c.charEnd()), c.content(),
                    "chunk content 应与原文 [charStart, charEnd) 子串一致");
        }
    }

    @Test
    void splitContent_largeContentCoversEnd() {
        // P0-2 核心验证：150KB 内容的分块能覆盖末尾（不再静默截断）
        // 用 ASCII 文本模拟 150KB（1 char = 1 byte），避免测试本身耗时/耗内存过高
        int targetChars = 150 * 1024;
        StringBuilder sb = new StringBuilder(targetChars);
        String unit = "abcdefghij\n"; // 11 chars
        while (sb.length() + unit.length() <= targetChars) {
            sb.append(unit);
        }
        if (sb.length() < targetChars) {
            sb.append(unit, 0, targetChars - sb.length());
        }
        String content = sb.toString();
        int totalLen = content.length();

        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, 800, 400);

        assertFalse(chunks.isEmpty(), "150KB 内容应产生多个分段");
        // 最后一块的 charEnd 应等于内容总长度——证明末尾内容进入了图谱
        int lastCharEnd = chunks.get(chunks.size() - 1).charEnd();
        assertEquals(totalLen, lastCharEnd,
                "最后一块的 charEnd 应等于内容总长度，证明未截断末尾");
        // 验证最后一块的 content 确实是原文末尾
        DocExtractStep.DocChunk last = chunks.get(chunks.size() - 1);
        assertEquals(content.substring(last.charStart(), last.charEnd()), last.content());
    }

    // ──────────────────────────────────────────────
    // P0-2 新增：mergeByKey 置信度与 evidence 合并
    // ──────────────────────────────────────────────

    @Test
    void mergeByKey_keepsHigherConfidence() {
        // 同名项应保留较高置信度
        DocUnderstandingAgent.BusinessFactExtraction r1 = new DocUnderstandingAgent.BusinessFactExtraction();
        DocUnderstandingAgent.BusinessObject o1 = new DocUnderstandingAgent.BusinessObject();
        o1.setName("订单");
        o1.setConfidence(0.5);
        r1.setBusinessObjects(List.of(o1));

        DocUnderstandingAgent.BusinessFactExtraction r2 = new DocUnderstandingAgent.BusinessFactExtraction();
        DocUnderstandingAgent.BusinessObject o2 = new DocUnderstandingAgent.BusinessObject();
        o2.setName("订单");
        o2.setConfidence(0.9);
        r2.setBusinessObjects(List.of(o2));

        List<DocUnderstandingAgent.BusinessObject> merged = DocExtractStep.mergeByKey(
                List.of(r1, r2),
                DocUnderstandingAgent.BusinessFactExtraction::getBusinessObjects,
                DocUnderstandingAgent.BusinessObject::getName);

        assertEquals(1, merged.size(), "同名项应去重为 1 条");
        assertEquals(0.9, merged.get(0).getConfidence(), 0.001, "应保留较高置信度 0.9");
    }

    @Test
    void mergeByKey_keepsHigherConfidenceRegardlessOfOrder() {
        // 验证顺序无关：先低后高 与 先高后低 都应保留高置信度
        DocUnderstandingAgent.BusinessObject high = new DocUnderstandingAgent.BusinessObject();
        high.setName("商品");
        high.setConfidence(0.95);
        DocUnderstandingAgent.BusinessFactExtraction rHigh = new DocUnderstandingAgent.BusinessFactExtraction();
        rHigh.setBusinessObjects(List.of(high));

        DocUnderstandingAgent.BusinessObject low = new DocUnderstandingAgent.BusinessObject();
        low.setName("商品");
        low.setConfidence(0.3);
        DocUnderstandingAgent.BusinessFactExtraction rLow = new DocUnderstandingAgent.BusinessFactExtraction();
        rLow.setBusinessObjects(List.of(low));

        // 先低后高
        List<DocUnderstandingAgent.BusinessObject> m1 = DocExtractStep.mergeByKey(
                List.of(rLow, rHigh),
                DocUnderstandingAgent.BusinessFactExtraction::getBusinessObjects,
                DocUnderstandingAgent.BusinessObject::getName);
        assertEquals(0.95, m1.get(0).getConfidence(), 0.001, "先低后高应保留 0.95");

        // 先高后低
        List<DocUnderstandingAgent.BusinessObject> m2 = DocExtractStep.mergeByKey(
                List.of(rHigh, rLow),
                DocUnderstandingAgent.BusinessFactExtraction::getBusinessObjects,
                DocUnderstandingAgent.BusinessObject::getName);
        assertEquals(0.95, m2.get(0).getConfidence(), 0.001, "先高后低应保留 0.95");
    }

    @Test
    void mergeByKey_mergesEvidence() {
        // 同名项合并后，winner 的 evidence 列表应包含双方的 evidence
        DocUnderstandingAgent.EvidenceRef ev1 = new DocUnderstandingAgent.EvidenceRef();
        ev1.setSourceUri("uri1");
        DocUnderstandingAgent.EvidenceRef ev2 = new DocUnderstandingAgent.EvidenceRef();
        ev2.setSourceUri("uri2");

        DocUnderstandingAgent.BusinessObject winner = new DocUnderstandingAgent.BusinessObject();
        winner.setName("订单");
        winner.setConfidence(0.9);
        winner.setEvidence(new ArrayList<>(List.of(ev1)));
        DocUnderstandingAgent.BusinessFactExtraction r1 = new DocUnderstandingAgent.BusinessFactExtraction();
        r1.setBusinessObjects(List.of(winner));

        DocUnderstandingAgent.BusinessObject loser = new DocUnderstandingAgent.BusinessObject();
        loser.setName("订单");
        loser.setConfidence(0.5);
        loser.setEvidence(new ArrayList<>(List.of(ev2)));
        DocUnderstandingAgent.BusinessFactExtraction r2 = new DocUnderstandingAgent.BusinessFactExtraction();
        r2.setBusinessObjects(List.of(loser));

        List<DocUnderstandingAgent.BusinessObject> merged = DocExtractStep.mergeByKey(
                List.of(r1, r2),
                DocUnderstandingAgent.BusinessFactExtraction::getBusinessObjects,
                DocUnderstandingAgent.BusinessObject::getName);

        assertEquals(1, merged.size());
        assertEquals(0.9, merged.get(0).getConfidence(), 0.001, "应保留较高置信度");
        assertEquals(2, merged.get(0).getEvidence().size(), "应合并双方 evidence 列表");
    }

    @Test
    void mergeByKey_dedupesByKeyName() {
        // 不同名称应保留为独立项
        DocUnderstandingAgent.BusinessObject o1 = new DocUnderstandingAgent.BusinessObject();
        o1.setName("订单");
        o1.setConfidence(0.8);
        DocUnderstandingAgent.BusinessFactExtraction r1 = new DocUnderstandingAgent.BusinessFactExtraction();
        r1.setBusinessObjects(List.of(o1));

        DocUnderstandingAgent.BusinessObject o2 = new DocUnderstandingAgent.BusinessObject();
        o2.setName("商品");
        o2.setConfidence(0.7);
        DocUnderstandingAgent.BusinessFactExtraction r2 = new DocUnderstandingAgent.BusinessFactExtraction();
        r2.setBusinessObjects(List.of(o2));

        List<DocUnderstandingAgent.BusinessObject> merged = DocExtractStep.mergeByKey(
                List.of(r1, r2),
                DocUnderstandingAgent.BusinessFactExtraction::getBusinessObjects,
                DocUnderstandingAgent.BusinessObject::getName);

        assertEquals(2, merged.size(), "不同名称应保留为独立项");
    }

    // ──────────────────────────────────────────────
    // 增量抽取：filterDocsForExtract / matchesAnyChangedPath
    // changedFilePaths 为相对路径，Document.filePath 为绝对路径，用 endsWith/contains 匹配。
    // ──────────────────────────────────────────────

    /** 构造仅设置 filePath 的 Document，用于增量筛选测试 */
    private static Document docWith(String filePath) {
        Document d = new Document();
        d.setFilePath(filePath);
        return d;
    }

    @Test
    void filterDocsForExtract_全量模式返回全部文档() {
        // 首次扫描（incremental=false）：忽略 changedFilePaths，全量返回
        List<Document> all = List.of(
                docWith("/abs/project/src/Foo.java"),
                docWith("/abs/project/docs/readme.md"));
        List<Document> result = DocExtractStep.filterDocsForExtract(all, false, Set.of("src/Foo.java"));

        assertEquals(2, result.size(), "全量模式应返回全部文档，忽略 changedFilePaths");
    }

    @Test
    void filterDocsForExtract_增量模式仅返回变更文档() {
        // 增量模式：仅返回 filePath 命中 changedFilePaths（相对路径）的文档
        List<Document> all = List.of(
                docWith("/abs/project/src/Foo.java"),      // 命中 src/Foo.java
                docWith("/abs/project/docs/readme.md"),    // 命中 docs/readme.md
                docWith("/abs/project/src/Bar.java"));     // 未变更
        Set<String> changed = Set.of("src/Foo.java", "docs/readme.md");

        List<Document> result = DocExtractStep.filterDocsForExtract(all, true, changed);

        assertEquals(2, result.size(), "增量模式应仅返回命中变更路径的文档");
        Set<String> hitPaths = result.stream().map(Document::getFilePath).collect(Collectors.toSet());
        assertTrue(hitPaths.contains("/abs/project/src/Foo.java"), "命中的变更文档应保留");
        assertTrue(hitPaths.contains("/abs/project/docs/readme.md"), "命中的变更文档应保留");
        assertFalse(hitPaths.contains("/abs/project/src/Bar.java"), "未变更文档应被过滤");
    }

    @Test
    void filterDocsForExtract_增量模式空集合返回全部不崩溃() {
        // changedFilePaths 为空集合时相当于全量，不应崩溃也不应过滤
        List<Document> all = List.of(
                docWith("/abs/project/src/Foo.java"),
                docWith("/abs/project/src/Bar.java"));

        List<Document> result = DocExtractStep.filterDocsForExtract(all, true, Set.of());

        assertEquals(2, result.size(), "changedFilePaths 为空时应返回全部文档（相当于全量）");
    }

    @Test
    void filterDocsForExtract_增量模式null返回全部不崩溃() {
        // changedFilePaths 为 null 时（防御），相当于全量
        List<Document> all = List.of(docWith("/abs/project/src/Foo.java"));

        List<Document> result = DocExtractStep.filterDocsForExtract(all, true, null);

        assertEquals(1, result.size(), "changedFilePaths 为 null 时应返回全部文档");
    }

    @Test
    void filterDocsForExtract_空文档列表不崩溃() {
        // 全量文档为空时，增量过滤应返回空列表
        List<Document> result = DocExtractStep.filterDocsForExtract(List.of(), true, Set.of("src/Foo.java"));
        assertTrue(result.isEmpty(), "空文档列表过滤后应仍为空");
    }

    @Test
    void matchesAnyChangedPath_绝对路径与相对路径匹配() {
        // endsWith：相对路径作为绝对路径后缀
        assertTrue(DocExtractStep.matchesAnyChangedPath(
                        "/abs/project/src/main/Foo.java", Set.of("src/main/Foo.java")),
                "绝对路径 endsWith 相对路径应命中");
        // contains：仅文件名片段也能命中
        assertTrue(DocExtractStep.matchesAnyChangedPath(
                        "/abs/project/src/main/Foo.java", Set.of("Foo.java")),
                "绝对路径 contains 文件名应命中");
        // 未命中
        assertFalse(DocExtractStep.matchesAnyChangedPath(
                        "/abs/project/src/main/Bar.java", Set.of("src/main/Foo.java")),
                "不匹配的路径不应命中");
        // 空白项应被跳过
        assertFalse(DocExtractStep.matchesAnyChangedPath(
                        "/abs/project/src/main/Foo.java", Set.of("")),
                "空白变更路径应被跳过，不应误命中");
    }

    // ──────────────────────────────────────────────
    // Task 4：向量语义去重（semanticDeduplicateFeatures / cosineSimilarity）
    // ──────────────────────────────────────────────

    @Test
    void cosineSimilarity_正交为零相同为一长度不等为零() {
        // 相同向量 cosine=1.0
        assertEquals(1.0, DocExtractStep.cosineSimilarity(new float[]{1f, 0f}, new float[]{1f, 0f}), 0.001,
                "相同向量余弦相似度应为 1.0");
        // 正交向量 cosine=0.0
        assertEquals(0.0, DocExtractStep.cosineSimilarity(new float[]{1f, 0f}, new float[]{0f, 1f}), 0.001,
                "正交向量余弦相似度应为 0.0");
        // null 安全
        assertEquals(0.0, DocExtractStep.cosineSimilarity(null, new float[]{1f}), 0.001,
                "null 向量应返回 0.0");
        // 长度不等返回 0.0
        assertEquals(0.0, DocExtractStep.cosineSimilarity(new float[]{1f}, new float[]{1f, 2f}), 0.001,
                "长度不等的向量应返回 0.0");
    }

    @Test
    void semanticDeduplicateFeatures_语义相同Feature被合并() {
        // "入金查询"与"查询入金"归一化不同但语义相同，精确+子串去重无法合并，
        // 向量去重应将 cosine > 0.90 的合并。
        DocExtractStep step = createStep();
        EmbeddingModel mockModel = org.mockito.Mockito.mock(EmbeddingModel.class);
        ReflectionTestUtils.setField(step, "embeddingModel", mockModel);

        List<String> features = List.of("入金查询", "查询入金", "出金流程", "登录功能");
        // 入金查询 / 查询入金 → 相同向量（cosine=1.0 > 0.90 → 合并）
        // 出金流程 / 登录功能 → 正交向量（cosine=0.0 → 保留）
        when(mockModel.embed(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(
                new float[]{1f, 0f, 0f},
                new float[]{1f, 0f, 0f},
                new float[]{0f, 1f, 0f},
                new float[]{0f, 0f, 1f}
        ));

        List<String> result = step.semanticDeduplicateFeatures(features);

        assertEquals(3, result.size(), "语义相同的 Feature 应被合并：入金查询+查询入金 → 1 条");
        assertTrue(result.contains("入金查询"), "应保留首个出现的入金查询");
        assertFalse(result.contains("查询入金"), "查询入金应被合并移除");
        assertTrue(result.contains("出金流程"), "语义不同的出金流程应保留");
        assertTrue(result.contains("登录功能"), "语义不同的登录功能应保留");
    }

    @Test
    void semanticDeduplicateFeatures_embeddingModel为null时降级返回原列表() {
        // embeddingModel 不可用时（null），应降级返回原列表，不抛异常
        DocExtractStep step = createStep(); // embeddingModel 未注入，为 null
        List<String> features = List.of("入金查询", "查询入金", "出金流程");

        List<String> result = step.semanticDeduplicateFeatures(features);

        assertEquals(features, result, "embeddingModel 为 null 时应降级返回原列表");
    }

    @Test
    void semanticDeduplicateFeatures_embed抛异常时降级返回原列表() {
        // embeddingModel.embed 抛异常时（如 Ollama 不可用），应降级返回原列表
        DocExtractStep step = createStep();
        EmbeddingModel mockModel = org.mockito.Mockito.mock(EmbeddingModel.class);
        ReflectionTestUtils.setField(step, "embeddingModel", mockModel);
        when(mockModel.embed(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new RuntimeException("Ollama unavailable"));

        List<String> features = List.of("入金查询", "查询入金", "出金流程");
        List<String> result = step.semanticDeduplicateFeatures(features);

        assertEquals(features, result, "embed 异常时应降级返回原列表");
    }

    // ──────────────────────────────────────────────
    // Task 6：大文档分段 2 路受限并行（extractFromChunks）
    // ──────────────────────────────────────────────

    @Test
    void extractFromChunks_多chunk两路并行后合并结果() throws Exception {
        // 验证 2 路受限并行：多 chunk 时至少 2 个 chunk 同时执行（而非串行）
        DocExtractStep step = createStep();
        ExecutorService testExecutor = Executors.newFixedThreadPool(4);
        try {
            when(support.getDocExtractExecutor()).thenReturn(testExecutor);

            // 用 CountDownLatch 验证并发：2 个 chunk 同时进入 cachedExtract 即证明 2 路并行
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            when(support.cachedExtract(eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any()))
                    .thenAnswer(inv -> {
                        started.countDown();
                        // 阻塞等待释放信号；串行模式下第二个 chunk 不会在第一个完成前到达此处
                        release.await(5, TimeUnit.SECONDS);
                        DocUnderstandingAgent.BusinessFactExtraction e = new DocUnderstandingAgent.BusinessFactExtraction();
                        e.setFeatures(List.of("chunk_feature"));
                        return e;
                    });

            // 20000 字符触发分段（NORMAL_DOC_CHUNK_SIZE=4000，产生约 6 个 chunk）
            String content = generateContent(20000);
            Document doc = new Document();
            doc.setFilePath("/test/doc.md");
            doc.setId("doc-1");

            // 在单独线程跑 extractFromChunks（它内部 join 等待 chunk futures）
            Future<DocUnderstandingAgent.BusinessFactExtraction> fut =
                    testExecutor.submit(() -> invokeExtractFromChunks(step, "p1", doc, content));

            // 等待 2 个 chunk 并发启动——串行模式下只会到 1，2 路并行才会到 0
            assertTrue(started.await(5, TimeUnit.SECONDS),
                    "应至少有 2 个 chunk 并发执行（2 路受限并行），串行则超时");
            release.countDown(); // 释放所有阻塞的 chunk

            DocUnderstandingAgent.BusinessFactExtraction result = fut.get(10, TimeUnit.SECONDS);
            assertNotNull(result, "extractFromChunks 不应返回 null");
            assertNotNull(result.getFeatures(), "合并后 features 不应为 null");
            assertFalse(result.getFeatures().isEmpty(), "合并后应包含 chunk 抽取的 feature");
        } finally {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void extractFromChunks_OOM时chunk返回null被过滤() throws Exception {
        // 验证内存不足（OOM）时 chunk 返回 null 被过滤，mergeExtractions 处理空列表返回非 null 空对象
        DocExtractStep step = createStep();
        ExecutorService testExecutor = Executors.newFixedThreadPool(4);
        try {
            when(support.getDocExtractExecutor()).thenReturn(testExecutor);
            // 模拟所有 chunk 抽取时 OOM（内存不足的极端情况）
            when(support.cachedExtract(eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any()))
                    .thenThrow(new OutOfMemoryError("simulated OOM"));

            String content = generateContent(20000);
            Document doc = new Document();
            doc.setFilePath("/test/doc.md");
            doc.setId("doc-1");

            DocUnderstandingAgent.BusinessFactExtraction result =
                    invokeExtractFromChunks(step, "p1", doc, content);

            assertNotNull(result, "OOM 时也不应返回 null（mergeExtractions 处理空列表）");
            assertTrue(result.getFeatures() == null || result.getFeatures().isEmpty(),
                    "所有 chunk OOM 时 features 应为空（null 被过滤）");
        } finally {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void extractFromChunks_分块失败时显式返回不完整覆盖状态() throws Exception {
        DocExtractStep step = createStep();
        ExecutorService testExecutor = Executors.newFixedThreadPool(4);
        try {
            when(support.getDocExtractExecutor()).thenReturn(testExecutor);
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            when(support.cachedExtract(eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any()))
                    .thenAnswer(inv -> {
                        if (calls.incrementAndGet() == 1) {
                            DocUnderstandingAgent.BusinessFactExtraction extraction =
                                    new DocUnderstandingAgent.BusinessFactExtraction();
                            extraction.setFeatures(List.of("only-first-chunk"));
                            return extraction;
                        }
                        throw new RuntimeException("simulated chunk failure");
                    });

            Document doc = new Document();
            doc.setFilePath("/test/doc.md");
            doc.setId("doc-1");
            DocExtractStep.ChunkExtractionResult result = invokeExtractFromChunksWithCoverage(
                    step, "p1", doc, generateContent(20_000));

            assertFalse(result.isComplete(), "任一分块失败时不得把文档标记为完整抽取");
            assertTrue(result.successfulChunkCount() > 0, "应保留可追溯的成功分块结果");
            assertTrue(result.successfulChunkCount() < result.totalChunkCount(), "应报告缺失的分块数");
            assertTrue(result.extraction().getFeatures().contains("only-first-chunk"));
        } finally {
            testExecutor.shutdownNow();
        }
    }

    // ──────────────────────────────────────────────
    // P0-2 补充：分级 chunk size 验证
    // ──────────────────────────────────────────────

    @Test
    void chunkSizeConstants_三级分级值正确() throws Exception {
        // 验证三级 chunk size 常量值：>50KB→2500, >20KB→1800, 其他→4000
        assertEquals(2500, getPrivateIntConstant("LARGE_DOC_CHUNK_SIZE"),
                "LARGE_DOC_CHUNK_SIZE (>50KB) 应为 2500");
        assertEquals(1800, getPrivateIntConstant("MEDIUM_DOC_CHUNK_SIZE"),
                "MEDIUM_DOC_CHUNK_SIZE (>20KB) 应为 1800");
        assertEquals(4000, getPrivateIntConstant("NORMAL_DOC_CHUNK_SIZE"),
                "NORMAL_DOC_CHUNK_SIZE 应为 4000");
        assertEquals(400, getPrivateIntConstant("DOC_CHUNK_OVERLAP"),
                "DOC_CHUNK_OVERLAP 应为 400");
        assertEquals(16000, getPrivateIntConstant("DOC_CHUNK_THRESHOLD"),
                "DOC_CHUNK_THRESHOLD 应为 16000");
    }

    @Test
    void extractFromChunks_普通文档使用4000chunkSize() throws Exception {
        // 普通文档（<20KB）应使用 NORMAL_DOC_CHUNK_SIZE=4000
        DocExtractStep step = createStep();
        ExecutorService testExecutor = Executors.newFixedThreadPool(4);
        try {
            when(support.getDocExtractExecutor()).thenReturn(testExecutor);
            when(support.cachedExtract(eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any()))
                    .thenAnswer(inv -> {
                        DocUnderstandingAgent.BusinessFactExtraction e = new DocUnderstandingAgent.BusinessFactExtraction();
                        e.setFeatures(List.of("f1"));
                        return e;
                    });

            // 10000 字符 < 20000 → 普通文档，使用 4000 chunk size
            String content = generateContent(10000);
            Document doc = new Document();
            doc.setFilePath("/test/normal.md");
            doc.setId("doc-normal");

            DocUnderstandingAgent.BusinessFactExtraction result =
                    invokeExtractFromChunks(step, "p1", doc, content);

            assertNotNull(result);
            // 10000 / (4000-400) ≈ 3 chunks，验证确实分了多段
            // 由于 cachedExtract 被调用次数等于 chunk 数，这里通过 verify 验证
            verify(support, org.mockito.Mockito.atLeast(2)).cachedExtract(
                    eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any());
        } finally {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void extractFromChunks_中文档使用1800chunkSize() throws Exception {
        // 中文档（20KB~50KB）应使用 MEDIUM_DOC_CHUNK_SIZE=1800
        DocExtractStep step = createStep();
        ExecutorService testExecutor = Executors.newFixedThreadPool(4);
        try {
            when(support.getDocExtractExecutor()).thenReturn(testExecutor);
            when(support.cachedExtract(eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any()))
                    .thenAnswer(inv -> {
                        DocUnderstandingAgent.BusinessFactExtraction e = new DocUnderstandingAgent.BusinessFactExtraction();
                        e.setFeatures(List.of("f1"));
                        return e;
                    });

            // 30000 字符（20000 < 30000 < 50000）→ 中文档，使用 1800 chunk size
            String content = generateContent(30000);
            Document doc = new Document();
            doc.setFilePath("/test/medium.md");
            doc.setId("doc-medium");

            DocUnderstandingAgent.BusinessFactExtraction result =
                    invokeExtractFromChunks(step, "p1", doc, content);

            assertNotNull(result);
            // 30000 / (1800-400) ≈ 22 chunks，chunk 数应明显多于 4000 的情况
            verify(support, org.mockito.Mockito.atLeast(10)).cachedExtract(
                    eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any());
        } finally {
            testExecutor.shutdownNow();
        }
    }

    @Test
    void extractFromChunks_大文档使用2500chunkSize() throws Exception {
        // 大文档（>50KB）应使用 LARGE_DOC_CHUNK_SIZE=2500
        DocExtractStep step = createStep();
        ExecutorService testExecutor = Executors.newFixedThreadPool(4);
        try {
            when(support.getDocExtractExecutor()).thenReturn(testExecutor);
            when(support.cachedExtract(eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any()))
                    .thenAnswer(inv -> {
                        DocUnderstandingAgent.BusinessFactExtraction e = new DocUnderstandingAgent.BusinessFactExtraction();
                        e.setFeatures(List.of("f1"));
                        return e;
                    });

            // 60000 字符 > 50000 → 大文档，使用 2500 chunk size
            String content = generateContent(60000);
            Document doc = new Document();
            doc.setFilePath("/test/large.md");
            doc.setId("doc-large");

            DocUnderstandingAgent.BusinessFactExtraction result =
                    invokeExtractFromChunks(step, "p1", doc, content);

            assertNotNull(result);
            // 60000 / (2500-400) ≈ 29 chunks
            verify(support, org.mockito.Mockito.atLeast(15)).cachedExtract(
                    eq("doc-chunk"), anyString(), any(),
                    eq(DocUnderstandingAgent.BusinessFactExtraction.class), any());
        } finally {
            testExecutor.shutdownNow();
        }
    }

    // ──────────────────────────────────────────────
    // splitContent 边界条件：防止无限循环
    // ──────────────────────────────────────────────

    @Test
    void splitContent_end等于文本长度时立即break() {
        // 验证 end >= text.length() 时立即 break，不会无限循环
        // 构造一个刚好等于 chunkSize 的内容，end 会等于 text.length()，应只产生 1 块
        String content = generateContent(500);
        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, 500, 100);

        assertEquals(1, chunks.size(), "内容长度等于 chunkSize 时应只产生 1 块，防止无限循环");
        assertEquals(0, chunks.get(0).charStart());
        assertEquals(500, chunks.get(0).charEnd());
    }

    @Test
    void splitContent_overlap大于chunkSize时不崩溃() {
        // overlap > size 时可能导致 start 不前进甚至为负，验证不会无限循环
        String content = generateContent(1000);
        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, 200, 300);

        // 应有合理数量的 chunk，不能无限产生
        assertTrue(chunks.size() > 0, "应产生至少 1 个 chunk");
        assertTrue(chunks.size() < 100, "overlap > size 时也不能产生无限多 chunk，实际: " + chunks.size());
        // 验证第一块正确
        assertEquals(0, chunks.get(0).charStart());
        assertTrue(chunks.get(0).charEnd() > 0);
    }

    @Test
    void splitContent_size为0返回空() {
        String content = generateContent(1000);
        assertTrue(DocExtractStep.splitContent(content, 0, 100).isEmpty());
    }

    @Test
    void splitContent_size为负数返回空() {
        String content = generateContent(1000);
        assertTrue(DocExtractStep.splitContent(content, -1, 100).isEmpty());
    }

    @Test
    void splitContent_overlap为0时仍然正确分段() {
        String content = generateContent(1000);
        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, 300, 0);

        assertTrue(chunks.size() > 1);
        // 无重叠时，每块的 charStart 应该是前一块的 charEnd
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(chunks.get(i - 1).charEnd(), chunks.get(i).charStart(),
                    "无重叠时第 " + i + " 块的 start 应等于前一块的 end");
        }
    }

    @Test
    void splitContent_全是换行符时正确处理() {
        // 极端情况：内容全是换行符
        String content = "\n".repeat(500);
        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, 100, 20);

        assertFalse(chunks.isEmpty());
        // 验证最后一块到达末尾
        assertEquals(content.length(), chunks.get(chunks.size() - 1).charEnd());
    }

    @Test
    void splitContent_单行超长文本正确处理() {
        // 极端情况：单行无换行的超长文本
        String content = "a".repeat(5000);
        List<DocExtractStep.DocChunk> chunks = DocExtractStep.splitContent(content, 1000, 200);

        assertTrue(chunks.size() > 1);
        assertEquals(content.length(), chunks.get(chunks.size() - 1).charEnd());
        // 验证每块不超过 chunkSize
        for (DocExtractStep.DocChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= 1000,
                    "每块长度不应超过 chunkSize，实际: " + chunk.content().length());
        }
    }

    // ──────────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────────

    /** 通过反射读取 DocExtractStep 的私有静态 int 常量 */
    private int getPrivateIntConstant(String fieldName) throws Exception {
        java.lang.reflect.Field f = DocExtractStep.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.getInt(null);
    }
}
