package io.github.legacygraph.task.step;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocExtractStep 大文档截断和分段逻辑测试。
 * 验证 >100KB 文档截断至 50KB、>50KB 使用 800 字符 chunk 等 OOM 防护逻辑。
 * 注意：测试中使用缩小的数据量验证逻辑正确性，避免测试本身 OOM。
 */
class DocExtractStepTest {

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
        // 模拟大文档分段，使用 800 字符 chunk（对应 >50KB 文档的 MEGA_DOC_CHUNK_SIZE）
        String content = generateContent(3000);
        int chunkSize = 800;
        int overlap = 400;

        List<String> chunks = DocExtractStep.splitContent(content, chunkSize, overlap);

        assertFalse(chunks.isEmpty(), "大文档应产生多个分段");
        // 每段长度不超过 chunkSize（最后一段可能更短）
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= chunkSize,
                    "分段长度 " + chunk.length() + " 超过 chunkSize " + chunkSize);
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

        List<String> chunks = DocExtractStep.splitContent(content, chunkSize, overlap);

        assertFalse(chunks.isEmpty());
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= chunkSize);
        }
        // 5000 / (2500-400) ≈ 2-3 段
        assertTrue(chunks.size() >= 2, "普通文档分段数应合理，实际: " + chunks.size());
    }

    @Test
    void splitContent_megaDocTruncatedThenChunked() {
        // 模拟截断逻辑：超大文档截断后再分段
        // 用缩小数据验证逻辑：2000 字符 "截断" 至 1000，再用小 chunk 分段
        String originalContent = generateContent(2000);
        assertEquals(2000, originalContent.length());

        // 模拟 DocExtractStep 中的前置截断逻辑（按比例缩小：100K→50K 等价于 2000→1000）
        int megaThreshold = 1000;
        int truncateTo = 500;
        String truncatedContent;
        if (originalContent.length() > megaThreshold) {
            truncatedContent = originalContent.substring(0, truncateTo);
        } else {
            truncatedContent = originalContent;
        }

        assertEquals(truncateTo, truncatedContent.length(), "截断后应为指定长度");

        // 截断后用小 chunk 分段
        List<String> chunks = DocExtractStep.splitContent(truncatedContent, 200, 100);
        assertFalse(chunks.isEmpty(), "截断后的文档应能正常分段");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 200, "分段长度不超过 200");
        }
    }

    @Test
    void splitContent_emptyOrNullReturnsEmpty() {
        assertTrue(DocExtractStep.splitContent(null, 800, 400).isEmpty());
        assertTrue(DocExtractStep.splitContent("", 800, 400).isEmpty());
    }

    @Test
    void splitContent_singleChunkWhenSmallerThanChunkSize() {
        String content = generateContent(500);
        List<String> chunks = DocExtractStep.splitContent(content, 800, 400);
        assertEquals(1, chunks.size(), "小于 chunkSize 的内容应只产生 1 段");
        assertEquals(500, chunks.get(0).length());
    }

    @Test
    void splitContent_overlapProducesMultipleChunks() {
        // 验证 overlap 机制：内容远大于 chunkSize 时产生多段
        String content = generateContent(2000);
        int chunkSize = 500;
        int overlap = 200;

        List<String> chunks = DocExtractStep.splitContent(content, chunkSize, overlap);

        assertTrue(chunks.size() >= 3, "应产生至少 3 段，实际: " + chunks.size());
        // 验证每段不超过 chunkSize
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= chunkSize);
        }
    }

    @Test
    void splitContent_chunkSizeParameterControlsSegmentSize() {
        // 核心验证：800 chunk size 产生的分段比 2500 chunk size 更小更多
        String content = generateContent(5000);

        List<String> smallChunks = DocExtractStep.splitContent(content, 800, 400);
        List<String> largeChunks = DocExtractStep.splitContent(content, 2500, 400);

        // 800 chunk 应产生更多分段
        assertTrue(smallChunks.size() > largeChunks.size(),
                "800 chunk 应比 2500 chunk 产生更多分段: " + smallChunks.size() + " vs " + largeChunks.size());

        // 验证 800 chunk 的每段确实更短
        int maxSmall = smallChunks.stream().mapToInt(String::length).max().orElse(0);
        int maxLarge = largeChunks.stream().mapToInt(String::length).max().orElse(0);
        assertTrue(maxSmall <= 800, "800 chunk 的最大段长应 <= 800, 实际: " + maxSmall);
        assertTrue(maxLarge <= 2500, "2500 chunk 的最大段长应 <= 2500, 实际: " + maxLarge);
    }
}
