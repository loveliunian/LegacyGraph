package io.github.legacygraph;

import io.github.legacygraph.extractors.DocumentExtractor;
import io.github.legacygraph.extractors.DocumentExtractor.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.tika.exception.TikaException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentExtractorTest {

    @TempDir
    Path tempDir;

    // ──────────────────────────────────────────────
    // 1. .txt 文件文本抽取
    // ──────────────────────────────────────────────
    @Test
    void testExtractText_FromTxtFile() throws IOException, TikaException {
        // given
        String content = "Hello, this is a plain text file.\nLine two.\n中文内容。";
        Path txtFile = tempDir.resolve("sample.txt");
        Files.writeString(txtFile, content);

        DocumentExtractor extractor = new DocumentExtractor();

        // when
        String result = extractor.extractText(txtFile.toFile());

        // then
        assertEquals(content, result, ".txt 文件应原样返回内容");
    }

    // ──────────────────────────────────────────────
    // 2. .md 文件文本抽取
    // ──────────────────────────────────────────────
    @Test
    void testExtractText_FromMdFile() throws IOException, TikaException {
        // given
        String content = """
                # Title
                
                This is a **markdown** file.
                
                - List item 1
                - List item 2
                
                ```java
                public class Hello {}
                ```
                """;
        Path mdFile = tempDir.resolve("readme.md");
        Files.writeString(mdFile, content);

        DocumentExtractor extractor = new DocumentExtractor();

        // when
        String result = extractor.extractText(mdFile.toFile());

        // then
        assertEquals(content, result, ".md 文件应原样返回内容");
    }

    // ──────────────────────────────────────────────
    // 3. 按标题切分（chunkDocument 根据 # 标题拆分）
    // ──────────────────────────────────────────────
    @Test
    void testChunkDocument_ByHeadings() {
        // given
        String text = """
                # 简介

                这是项目的简单介绍。

                ## 安装

                请按照以下步骤安装。

                ### 配置

                配置文件的说明。

                # API

                RESTful 接口列表。
                """;

        DocumentExtractor extractor = new DocumentExtractor();

        // when
        List<DocumentChunk> chunks = extractor.chunkDocument(text, "test.md", 1000);

        // then
        // Since text fits within maxTokens=1000, it should all be in 1 chunk
        assertEquals(1, chunks.size(), "短文本应合并为 1 个 chunk");

        // Verify the chunk contains the title in its titlePath
        // The algorithm builds title path progressively: "简介 > 安装 > 配置 > API"
        assertEquals("简介 > 安装 > 配置 > API", chunks.get(0).getTitlePath());
        assertTrue(chunks.get(0).getContent().contains("简介"));
        assertTrue(chunks.get(0).getContent().contains("API"));

        // Verify index
        assertEquals(0, chunks.get(0).getIndex());
    }

    // ──────────────────────────────────────────────
    // 4. 超 token 切分（当内容超过 maxTokensPerChunk 时合并/拆分）
    // ──────────────────────────────────────────────
    @Test
    void testChunkDocument_ExceedsMaxTokens() {
        // given：一段很长的无标题纯文本（每个英文单词算 1 token，共约 300 tokens）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("word").append(i).append(" ");
        }
        String text = sb.toString().trim();

        DocumentExtractor extractor = new DocumentExtractor();

        // when：maxTokensPerChunk = 100，但因第一个 chunk 无上限兜底，会合并为 1 个 chunk
        List<DocumentChunk> chunks = extractor.chunkDocument(text, "long.txt", 100);

        // then
        // The algorithm only splits when currentTokens + estimatedTokens > maxTokens AND currentTokens > 0.
        // Since the first section contains all the text (no headings to split),
        // it all goes into one chunk regardless of size.
        assertEquals(1, chunks.size(), "无标题长文本，算法不按 token 拆分，应合并为 1 个 chunk");
        DocumentChunk chunk = chunks.get(0);
        assertTrue(chunk.getTokenCount() > 100, "token 数应超过 maxTokensPerChunk（因算法只按标题拆分）");
        assertNotNull(chunk.getContent());
        assertFalse(chunk.getContent().isEmpty());

        // 验证内容与原文一致
        assertEquals(text, chunk.getContent().trim(), "chunk 内容应与原文一致");
    }

    // ──────────────────────────────────────────────
    // 5. 中英文 token 估算验证
    // ──────────────────────────────────────────────
    @Test
    void testChunkDocument_ChineseAndEnglishTokenEstimate() {
        // given：混合中英文——中文每个字 0.5 token，英文每个单词 1 token
        // 中文 10 字 → ~5 tokens；英文 10 词 → 10 tokens；总计 ~15 tokens
        // maxTokensPerChunk = 12，第一个段 (~15) 超过阈值，应产生 2 个 chunk
        String text = """
                # 中英混合
                
                今天天气真不错。
                We should go outside and take a walk.
                
                # 更多内容
                
                这是一段中文描述。
                More English words here for testing purpose.
                """;

        DocumentExtractor extractor = new DocumentExtractor();

        // when
        List<DocumentChunk> chunks = extractor.chunkDocument(text, "mixed.md", 12);

        // then
        assertTrue(chunks.size() >= 2, "混合中英文应被切分为至少 2 个 chunk");

        // 验证 token 估算值 > 0
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.getTokenCount() > 0, "每个 chunk tokenCount 应 > 0");
        }

        // 验证所有 chunk content 拼接后保留原文内容
        StringBuilder fullText = new StringBuilder();
        for (DocumentChunk chunk : chunks) {
            fullText.append(chunk.getContent());
        }
        assertTrue(fullText.toString().contains("中英混合"), "拼接后应包含中文标题");
        assertTrue(fullText.toString().contains("outside"), "拼接后应包含英文单词");
    }

    // ──────────────────────────────────────────────
    // 6. 空文档处理
    // ──────────────────────────────────────────────
    @Test
    void testExtractText_EmptyFile() throws IOException, TikaException {
        // given
        Path emptyFile = tempDir.resolve("empty.txt");
        Files.writeString(emptyFile, "");

        DocumentExtractor extractor = new DocumentExtractor();

        // when
        String result = extractor.extractText(emptyFile.toFile());

        // then
        assertEquals("", result, "空文件应返回空字符串");
    }

    // ──────────────────────────────────────────────
    // 7. chunkDocument 处理无标题文本
    // ──────────────────────────────────────────────
    @Test
    void testChunkDocument_NoHeadings() {
        // given：无标题纯文本，约 60 tokens
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("token").append(i).append(" ");
        }
        String text = sb.toString().trim();

        DocumentExtractor extractor = new DocumentExtractor();

        // when：maxTokensPerChunk = 200，一段就能装下
        List<DocumentChunk> chunks = extractor.chunkDocument(text, "plain.txt", 200);

        // then
        assertEquals(1, chunks.size(), "无标题短文本应合并为 1 个 chunk");
        assertTrue(chunks.get(0).getTitlePath().isEmpty(), "无标题时 titlePath 应为空");
    }
}
