package io.github.legacygraph.service.document;

import io.github.legacygraph.dto.DocumentChunk;
import io.github.legacygraph.entity.DocumentElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StructureAwareChunkService} 单元测试 — 验证结构感知切块规则（spec 5.2–5.3）。
 */
class StructureAwareChunkServiceTest {

    private final StructureAwareChunkService service = new StructureAwareChunkService(2500);

    // ==================== 基础边界 ====================

    @Test
    void emptyOrNull_returnsEmpty() {
        assertTrue(service.chunk(null).isEmpty());
        assertTrue(service.chunk(List.of()).isEmpty());
    }

    @Test
    void singleNarrative_singleChunk() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "正文内容", List.of("章节A"));
        List<DocumentChunk> chunks = service.chunk(List.of(text));

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).getChunkIndex());
        assertTrue(chunks.get(0).getContent().startsWith("[章节A]"));
        assertTrue(chunks.get(0).getContent().contains("正文内容"));
    }

    @Test
    void noHeadingPath_contentWithoutPrefix() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "纯文本", List.of());
        List<DocumentChunk> chunks = service.chunk(List.of(text));

        assertEquals(1, chunks.size());
        assertEquals("纯文本", chunks.get(0).getContent());
        assertTrue(chunks.get(0).getHeadingPath().isEmpty());
    }

    // ==================== 5.2 一级标题不跨合并 ====================

    @Test
    void levelOneTitle_createsBoundary() {
        // 两个一级标题下的段落不应合并
        DocumentElement title1 = element(DocumentElement.Type.TITLE, "章节A", List.of("章节A"));
        DocumentElement text1 = element(DocumentElement.Type.NARRATIVE_TEXT, "段落1", List.of("章节A"));
        DocumentElement title2 = element(DocumentElement.Type.TITLE, "章节B", List.of("章节B"));
        DocumentElement text2 = element(DocumentElement.Type.NARRATIVE_TEXT, "段落2", List.of("章节B"));

        List<DocumentChunk> chunks = service.chunk(List.of(title1, text1, title2, text2));

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("段落1"));
        assertFalse(chunks.get(0).getContent().contains("段落2"));
        assertTrue(chunks.get(1).getContent().contains("段落2"));
        assertEquals(List.of("章节A"), chunks.get(0).getHeadingPath());
        assertEquals(List.of("章节B"), chunks.get(1).getHeadingPath());
    }

    @Test
    void levelOneTitle_emptySectionNoChunk() {
        // 空的一级标题（无后续 NARRATIVE_TEXT）不应产生空块
        DocumentElement title1 = element(DocumentElement.Type.TITLE, "章节A", List.of("章节A"));
        DocumentElement text1 = element(DocumentElement.Type.NARRATIVE_TEXT, "正文", List.of("章节A"));
        DocumentElement title2 = element(DocumentElement.Type.TITLE, "空章节", List.of("空章节"));
        // title2 后无内容

        List<DocumentChunk> chunks = service.chunk(List.of(title1, text1, title2));

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("正文"));
    }

    // ==================== 5.2 TABLE 单独成块 ====================

    @Test
    void table_standaloneChunk() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "正文段落", List.of("章节"));
        DocumentElement table = element(DocumentElement.Type.TABLE, "| 字段 | 类型 |", List.of("章节"));
        DocumentElement text2 = element(DocumentElement.Type.NARRATIVE_TEXT, "后续段落", List.of("章节"));

        List<DocumentChunk> chunks = service.chunk(List.of(text, table, text2));

        // 应产生 3 个块：正文、表格、后续
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("正文段落"));
        assertTrue(chunks.get(1).getContent().contains("| 字段 | 类型 |"));
        assertTrue(chunks.get(2).getContent().contains("后续段落"));
    }

    @Test
    void table_betweenNarrative_flushesBuffer() {
        // 连续的同章节段落中间插入 TABLE，TABLE 前后应分别成块
        DocumentElement t1 = element(DocumentElement.Type.NARRATIVE_TEXT, "段1", List.of("S"));
        DocumentElement t2 = element(DocumentElement.Type.NARRATIVE_TEXT, "段2", List.of("S"));
        DocumentElement table = element(DocumentElement.Type.TABLE, "| 表 |", List.of("S"));
        DocumentElement t3 = element(DocumentElement.Type.NARRATIVE_TEXT, "段3", List.of("S"));

        List<DocumentChunk> chunks = service.chunk(List.of(t1, t2, table, t3));

        assertEquals(3, chunks.size());
        // 前两段应合并为一个块
        assertTrue(chunks.get(0).getContent().contains("段1"));
        assertTrue(chunks.get(0).getContent().contains("段2"));
        // TABLE 单独成块
        assertTrue(chunks.get(1).getContent().contains("| 表 |"));
        assertFalse(chunks.get(1).getContent().contains("段1"));
        // 段3 单独成块
        assertTrue(chunks.get(2).getContent().contains("段3"));
    }

    // ==================== 5.2 CODE_BLOCK 单独成块 ====================

    @Test
    void codeBlock_standaloneChunk() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "正文", List.of("章节"));
        DocumentElement code = element(DocumentElement.Type.CODE_BLOCK, "int x = 1;", List.of("章节"));
        DocumentElement text2 = element(DocumentElement.Type.NARRATIVE_TEXT, "后续", List.of("章节"));

        List<DocumentChunk> chunks = service.chunk(List.of(text, code, text2));

        assertEquals(3, chunks.size());
        assertTrue(chunks.get(1).getContent().contains("int x = 1;"));
        assertFalse(chunks.get(1).getContent().contains("正文"));
    }

    // ==================== 5.2 NARRATIVE_TEXT 合并 ====================

    @Test
    void narrativeText_sameHeadingMerged() {
        DocumentElement t1 = element(DocumentElement.Type.NARRATIVE_TEXT, "第一段。", List.of("A", "B"));
        DocumentElement t2 = element(DocumentElement.Type.NARRATIVE_TEXT, "第二段。", List.of("A", "B"));
        DocumentElement t3 = element(DocumentElement.Type.NARRATIVE_TEXT, "第三段。", List.of("A", "B"));

        List<DocumentChunk> chunks = service.chunk(List.of(t1, t2, t3));

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("第一段。"));
        assertTrue(chunks.get(0).getContent().contains("第二段。"));
        assertTrue(chunks.get(0).getContent().contains("第三段。"));
    }

    @Test
    void narrativeText_differentHeadingNotMerged() {
        DocumentElement t1 = element(DocumentElement.Type.NARRATIVE_TEXT, "段1", List.of("A", "B"));
        DocumentElement t2 = element(DocumentElement.Type.NARRATIVE_TEXT, "段2", List.of("A", "C"));

        List<DocumentChunk> chunks = service.chunk(List.of(t1, t2));

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("段1"));
        assertTrue(chunks.get(1).getContent().contains("段2"));
        assertEquals(List.of("A", "B"), chunks.get(0).getHeadingPath());
        assertEquals(List.of("A", "C"), chunks.get(1).getHeadingPath());
    }

    @Test
    void narrativeText_exceedsMaxChars_flushesAndStartsNew() {
        // 两段文本合并后超过 maxChars，应在 maxChars 附近切分
        String longPara = "这是一段很长的文本。".repeat(200); // ~2000 chars
        DocumentElement t1 = element(DocumentElement.Type.NARRATIVE_TEXT, longPara, List.of("S"));
        DocumentElement t2 = element(DocumentElement.Type.NARRATIVE_TEXT, "后续短文本。", List.of("S"));

        List<DocumentChunk> chunks = service.chunk(List.of(t1, t2), 500);

        assertTrue(chunks.size() > 1, "超长文本应被切分为多块");
        // 每块的正文（去掉 headingPath 前缀后）不应远超 maxChars
        for (DocumentChunk chunk : chunks) {
            // content = [S]\n文本，检查文本部分
            String content = chunk.getContent();
            int newlineIdx = content.indexOf('\n');
            int textLen = newlineIdx >= 0 ? content.length() - newlineIdx - 1 : content.length();
            assertTrue(textLen <= 600, "每块文本长度 " + textLen + " 应大致不超过 maxChars=500（含分隔符容差）");
        }
    }

    // ==================== 5.2 超长段落按句子边界切分 ====================

    @Test
    void splitBySentences_chineseSentenceBoundary() {
        // 中文按句号/问号/叹号切分
        String text = "这是第一句。这是第二句。这是第三句。这是第四句。这是第五句。";
        List<String> pieces = StructureAwareChunkService.splitBySentences(text, 15);

        assertTrue(pieces.size() > 1, "超长文本应被切分");
        // 每片应包含完整句子（以句号结尾或为最后一片）
        for (String piece : pieces) {
            assertTrue(piece.length() <= 15 + 10, "每片长度应大致不超过 maxChars");
        }
    }

    @Test
    void splitBySentences_englishSentenceBoundary() {
        String text = "First sentence. Second sentence. Third sentence. Fourth sentence. Fifth sentence.";
        List<String> pieces = StructureAwareChunkService.splitBySentences(text, 30);

        assertTrue(pieces.size() > 1);
        for (String piece : pieces) {
            assertTrue(piece.length() <= 40, "每片长度应不超过 maxChars+容差");
        }
    }

    @Test
    void splitBySentences_questionAndExclamation() {
        String text = "问题1？回答！问题2？回答2！问题3？";
        List<String> pieces = StructureAwareChunkService.splitBySentences(text, 8);

        assertTrue(pieces.size() > 1);
    }

    @Test
    void splitBySentences_singleSentenceExceedsMaxChars_hardCut() {
        // 单句超过 maxChars 时应硬切
        String text = "a".repeat(100);
        List<String> pieces = StructureAwareChunkService.splitBySentences(text, 30);

        assertTrue(pieces.size() >= 3, "100 字符硬切为 30 字符应产生至少 4 块");
        for (String piece : pieces) {
            assertTrue(piece.length() <= 30, "硬切后每块不应超过 maxChars");
        }
    }

    @Test
    void splitBySentences_shortTextReturnsSinglePiece() {
        List<String> pieces = StructureAwareChunkService.splitBySentences("短文本。", 100);
        assertEquals(1, pieces.size());
    }

    @Test
    void splitBySentences_nullOrEmptyReturnsEmpty() {
        assertTrue(StructureAwareChunkService.splitBySentences(null, 100).isEmpty());
        assertTrue(StructureAwareChunkService.splitBySentences("", 100).isEmpty());
    }

    // ==================== 5.3 headingPath 前缀 ====================

    @Test
    void headingPathPrefix_format() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "正文",
                List.of("一级", "二级", "三级"));
        List<DocumentChunk> chunks = service.chunk(List.of(text));

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getContent().startsWith("[一级 > 二级 > 三级]"));
        assertTrue(chunks.get(0).getContent().contains("正文"));
    }

    @Test
    void headingPathPrefix_singleLevel() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "正文", List.of("单级"));
        List<DocumentChunk> chunks = service.chunk(List.of(text));

        assertTrue(chunks.get(0).getContent().startsWith("[单级]"));
    }

    @Test
    void headingPathPrefix_emptyNoPrefix() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "正文", List.of());
        List<DocumentChunk> chunks = service.chunk(List.of(text));

        assertFalse(chunks.get(0).getContent().startsWith("["));
        assertEquals("正文", chunks.get(0).getContent());
    }

    @Test
    void headingPathPrefix_nullNoPrefix() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "正文", null);
        List<DocumentChunk> chunks = service.chunk(List.of(text));

        assertEquals("正文", chunks.get(0).getContent());
    }

    // ==================== 5.3 sourceLocation ====================

    @Test
    void sourceLocation_takesFirstElementOfChunk() {
        DocumentElement t1 = element(DocumentElement.Type.NARRATIVE_TEXT, "段1", List.of("S"));
        t1.setSourceLocation("file.md#S-line1");
        DocumentElement t2 = element(DocumentElement.Type.NARRATIVE_TEXT, "段2", List.of("S"));
        t2.setSourceLocation("file.md#S-line2");

        List<DocumentChunk> chunks = service.chunk(List.of(t1, t2));

        assertEquals(1, chunks.size());
        assertEquals("file.md#S-line1", chunks.get(0).getSourceLocation(),
                "sourceLocation 应取该块第一个元素的");
    }

    @Test
    void sourceLocation_tableStandalone() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "段", List.of("S"));
        text.setSourceLocation("file.md#S-text");
        DocumentElement table = element(DocumentElement.Type.TABLE, "| t |", List.of("S"));
        table.setSourceLocation("file.md#S-table");

        List<DocumentChunk> chunks = service.chunk(List.of(text, table));

        assertEquals(2, chunks.size());
        assertEquals("file.md#S-text", chunks.get(0).getSourceLocation());
        assertEquals("file.md#S-table", chunks.get(1).getSourceLocation());
    }

    // ==================== chunkIndex 序号 ====================

    @Test
    void chunkIndex_sequentialFromZero() {
        DocumentElement t1 = element(DocumentElement.Type.TITLE, "A", List.of("A"));
        DocumentElement n1 = element(DocumentElement.Type.NARRATIVE_TEXT, "text1", List.of("A"));
        DocumentElement t2 = element(DocumentElement.Type.TITLE, "B", List.of("B"));
        DocumentElement n2 = element(DocumentElement.Type.NARRATIVE_TEXT, "text2", List.of("B"));

        List<DocumentChunk> chunks = service.chunk(List.of(t1, n1, t2, n2));

        assertEquals(2, chunks.size());
        assertEquals(0, chunks.get(0).getChunkIndex());
        assertEquals(1, chunks.get(1).getChunkIndex());
    }

    @Test
    void chunkIndex_tableAndCodeGetIncremented() {
        DocumentElement n = element(DocumentElement.Type.NARRATIVE_TEXT, "text", List.of("S"));
        DocumentElement t = element(DocumentElement.Type.TABLE, "| t |", List.of("S"));
        DocumentElement c = element(DocumentElement.Type.CODE_BLOCK, "code", List.of("S"));

        List<DocumentChunk> chunks = service.chunk(List.of(n, t, c));

        assertEquals(3, chunks.size());
        assertEquals(0, chunks.get(0).getChunkIndex());
        assertEquals(1, chunks.get(1).getChunkIndex());
        assertEquals(2, chunks.get(2).getChunkIndex());
    }

    // ==================== determineMaxChars（spec 5.5 分级 chunk size） ====================

    @Test
    void determineMaxChars_largeDoc() {
        assertEquals(2500, service.determineMaxChars(60_000));
        assertEquals(2500, service.determineMaxChars(50_001));
    }

    @Test
    void determineMaxChars_mediumDoc() {
        assertEquals(1800, service.determineMaxChars(30_000));
        assertEquals(1800, service.determineMaxChars(20_001));
    }

    @Test
    void determineMaxChars_smallDoc() {
        assertEquals(2500, service.determineMaxChars(10_000));
        assertEquals(2500, service.determineMaxChars(0));
    }

    @Test
    void determineMaxChars_boundary() {
        // 边界值：恰好 50000 和 20000（使用 > 严格大于，等于阈值时降一级）
        assertEquals(1800, service.determineMaxChars(50_000), "恰好 50KB 不 > 50KB，属中文档 → 1800");
        assertEquals(2500, service.determineMaxChars(20_000), "恰好 20KB 不 > 20KB，属小文档 → 2500");
    }

    @Test
    void totalTextSize_calculatesCorrectly() {
        DocumentElement e1 = element(DocumentElement.Type.NARRATIVE_TEXT, "12345", List.of());
        DocumentElement e2 = element(DocumentElement.Type.NARRATIVE_TEXT, "abc", List.of());
        DocumentElement e3 = element(DocumentElement.Type.TABLE, "|x|", List.of());

        assertEquals(11, service.totalTextSize(List.of(e1, e2, e3)));
        assertEquals(0, service.totalTextSize(null));
        assertEquals(0, service.totalTextSize(List.of()));
    }

    // ==================== 综合场景 ====================

    @Test
    void complexDocument_mixedElements() {
        // 模拟真实文档：标题 → 段落 → 代码 → 段落 → 表格 → 新章节 → 段落
        DocumentElement t1 = element(DocumentElement.Type.TITLE, "功能说明", List.of("功能说明"));
        DocumentElement n1 = element(DocumentElement.Type.NARRATIVE_TEXT, "本节描述功能。", List.of("功能说明"));
        DocumentElement n2 = element(DocumentElement.Type.NARRATIVE_TEXT, "功能包含多个子模块。", List.of("功能说明"));
        DocumentElement code = element(DocumentElement.Type.CODE_BLOCK, "public void run() {}", List.of("功能说明"));
        DocumentElement n3 = element(DocumentElement.Type.NARRATIVE_TEXT, "如上代码所示。", List.of("功能说明"));
        DocumentElement table = element(DocumentElement.Type.TABLE, "| 参数 | 值 |", List.of("功能说明"));
        DocumentElement t2 = element(DocumentElement.Type.TITLE, "接口说明", List.of("接口说明"));
        DocumentElement n4 = element(DocumentElement.Type.NARRATIVE_TEXT, "接口文档见下。", List.of("接口说明"));

        List<DocumentChunk> chunks = service.chunk(List.of(t1, n1, n2, code, n3, table, t2, n4));

        // 预期：
        // chunk 0: [功能说明] 本节描述功能。\n功能包含多个子模块。
        // chunk 1: [功能说明] public void run() {}
        // chunk 2: [功能说明] 如上代码所示。
        // chunk 3: [功能说明] | 参数 | 值 |
        // chunk 4: [接口说明] 接口文档见下。
        assertEquals(5, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("本节描述功能。"));
        assertTrue(chunks.get(0).getContent().contains("功能包含多个子模块。"));
        assertTrue(chunks.get(1).getContent().contains("public void run()"));
        assertTrue(chunks.get(2).getContent().contains("如上代码所示。"));
        assertTrue(chunks.get(3).getContent().contains("| 参数 | 值 |"));
        assertTrue(chunks.get(4).getContent().contains("接口文档见下。"));
        assertEquals(List.of("功能说明"), chunks.get(0).getHeadingPath());
        assertEquals(List.of("接口说明"), chunks.get(4).getHeadingPath());
    }

    @Test
    void maxCharsZero_fallsBackToDefault() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "短文本", List.of("S"));
        List<DocumentChunk> chunks = service.chunk(List.of(text), 0);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("短文本"));
    }

    @Test
    void negativeMaxChars_fallsBackToDefault() {
        DocumentElement text = element(DocumentElement.Type.NARRATIVE_TEXT, "短文本", List.of("S"));
        List<DocumentChunk> chunks = service.chunk(List.of(text), -1);

        assertEquals(1, chunks.size());
    }

    @Test
    void defaultMaxChars_fromConstructor() {
        StructureAwareChunkService customService = new StructureAwareChunkService(500);
        assertEquals(500, customService.determineMaxChars(100));
        // 大文档仍然使用 2500（LARGE_DOC_MAX_CHARS 不受构造参数影响）
        assertEquals(2500, customService.determineMaxChars(60_000));
    }

    @Test
    void defaultMaxChars_constructorZeroFallback() {
        StructureAwareChunkService customService = new StructureAwareChunkService(0);
        assertEquals(2500, customService.determineMaxChars(100));
    }

    // ==================== 辅助方法 ====================

    private DocumentElement element(DocumentElement.Type type, String text, List<String> headingPath) {
        return DocumentElement.builder()
                .id("el-" + System.nanoTime())
                .docId("doc-1")
                .type(type)
                .text(text)
                .headingPath(headingPath)
                .sourceLocation("file.md#" + (headingPath == null || headingPath.isEmpty() ? "" : String.join("-", headingPath)))
                .build();
    }
}
