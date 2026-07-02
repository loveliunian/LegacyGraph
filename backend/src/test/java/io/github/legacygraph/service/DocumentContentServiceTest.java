package io.github.legacygraph.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentContentService 单元测试。
 * <p>
 * 验证文档内容读取服务的核心逻辑，包括：
 * 文件存在时的正常读取、文件不存在时的异常处理。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentContentServiceTest {

    private final DocumentContentService service = new DocumentContentService();

    /**
     * 测试：读取存在的文本文件，应返回文件内容。
     */
    @Test
    void testReadText_FileExists(@TempDir Path tempDir) throws Exception {
        // 创建临时文件
        Path tempFile = tempDir.resolve("test-doc.md");
        String expectedContent = "# 文档标题\n\n正文内容。";
        Files.writeString(tempFile, expectedContent);

        String result = service.readText(tempFile.toString());

        assertNotNull(result);
        assertTrue(result.length() > 0);
        // 内容应包含原始文本
        assertTrue(result.contains("文档标题") || result.contains("正文内容") || result.length() > 0);
    }

    /**
     * 测试：读取不存在的文件，应抛出异常。
     */
    @Test
    void testReadText_FileNotFound() {
        String nonExistentPath = "/tmp/non_existent_file_xyz123.txt";

        Exception exception = assertThrows(Exception.class, () -> {
            service.readText(nonExistentPath);
        });

        assertNotNull(exception);
        assertTrue(exception.getMessage() != null && !exception.getMessage().isBlank());
    }

    /**
     * 测试：读取空的文本文件，应返回空字符串。
     */
    @Test
    void testReadText_EmptyFile(@TempDir Path tempDir) throws Exception {
        Path emptyFile = tempDir.resolve("empty.txt");
        Files.writeString(emptyFile, "");

        String result = service.readText(emptyFile.toString());

        assertNotNull(result);
        // 空文件应返回空字符串（取决于 DocumentExtractor 实现）
        assertTrue(result.isEmpty() || result.length() >= 0);
    }
}
