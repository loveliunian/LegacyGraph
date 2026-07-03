package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.legacygraph.service.scan.SourceService;

/**
 * 数据源服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class SourceServiceTest {

    @Mock
    private CodeRepoRepository codeRepoRepository;

    @Mock
    private DbConnectionRepository dbConnectionRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocChunkRepository docChunkRepository;

    @InjectMocks
    private SourceService sourceService;

    // ==================== Git 操作测试 ====================

    @Test
    void testTestGitConnection_InvalidUrl() {
        Map<String, Object> result = sourceService.testGitConnection("invalid-url");
        assertNotNull(result);
        assertFalse((Boolean) result.get("success"));
        assertNotNull(result.get("message"));
    }

    @Test
    void testResolveRepoPath_ReturnsCorrectPath() {
        String path = sourceService.resolveRepoPath("proj-1", "my-repo");
        assertNotNull(path);
        assertTrue(path.contains("proj-1"));
        assertTrue(path.contains("my-repo"));
        assertTrue(path.contains(".legacygraph"));
    }

    // ==================== Document 操作测试 ====================

    @Test
    void testParseDocument_NotFound() {
        when(documentRepository.selectById("doc-nonexistent")).thenReturn(null);

        Map<String, Object> result = sourceService.parseDocument("proj-1", "doc-nonexistent");
        assertNotNull(result);
        assertFalse((Boolean) result.get("success"));
        assertEquals("文档不存在", result.get("message"));
        verify(documentRepository, never()).updateById(any(Document.class));
    }

    @Test
    void testParseDocument_Success() throws Exception {
        // 创建临时测试文件
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("test-doc", ".md");
        java.nio.file.Files.writeString(tempFile, "# 测试文档\n\n## 第一章\n\n这是测试内容。\n\n## 第二章\n\n更多测试内容。");

        Document doc = new Document();
        doc.setId("doc-1");
        doc.setProjectId("proj-1");
        doc.setVersionId("v1");
        doc.setDocName("test-doc.md");
        doc.setParseStatus("UPLOADED");
        doc.setFilePath(tempFile.toString());

        when(documentRepository.selectById("doc-1")).thenReturn(doc);
        when(documentRepository.updateById(any(Document.class))).thenReturn(1);

        Map<String, Object> result = sourceService.parseDocument("proj-1", "doc-1");
        assertNotNull(result);
        assertTrue((Boolean) result.get("success"), "解析应该成功");
        assertTrue(((Integer) result.get("factCount")) > 0, "应该至少生成一个文档片段");
        // 验证至少调用了两次 updateById（标记 PARSING 和 PARSED）
        verify(documentRepository, atLeast(2)).updateById(any(Document.class));

        // 清理临时文件
        java.nio.file.Files.deleteIfExists(tempFile);
    }

    // ==================== DB 连接测试 ====================

    @Test
    void testTestDbConnection_InvalidCredentials() {
        DbConnection conn = new DbConnection();
        conn.setDbType("POSTGRESQL");
        conn.setHost("invalid-host");
        conn.setPort(5432);
        conn.setDatabaseName("testdb");
        conn.setUsername("user");
        conn.setPassword("wrong");

        Map<String, Object> result = sourceService.testDbConnection(conn);
        assertNotNull(result);
        assertFalse((Boolean) result.get("success"));
        assertNotNull(result.get("message"));
    }
}
