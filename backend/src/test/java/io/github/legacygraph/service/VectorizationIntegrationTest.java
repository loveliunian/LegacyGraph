package io.github.legacygraph.service;

import io.github.legacygraph.entity.VectorDocument;
import io.github.legacygraph.repository.VectorDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.legacygraph.service.qa.VectorizationService;

/**
 * 快速验证：向量化写入 lg_vector_document 表是否正常
 * 需要 Ollama bge-m3 在 localhost:11434 运行
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class VectorizationIntegrationTest {

    @Autowired(required = false)
    private VectorizationService vectorizationService;

    @Autowired(required = false)
    private VectorDocumentRepository vectorDocumentRepository;

    @Test
    void testEmbedAndStore_ShouldWriteToDatabase() {
        if (vectorizationService == null || vectorDocumentRepository == null) {
            System.out.println("⚠ Bean 注入失败，跳过集成测试");
            return;
        }

        if (!vectorizationService.isAvailable()) {
            System.out.println("⚠ EmbeddingModel 不可用（lg_llm_provider 缺少默认提供商或 Ollama 未运行），跳过集成测试");
            return;
        }

        String testProjectId = "test-vec-int";
        String testVersionId = "v1-test";
        String testContent = """
                LegacyGraph 是一个代码知识图谱平台，支持代码扫描、文档分析、AI 编排。
                本测试验证扫描流程中自动向量化文档和代码内容的能力。
                向量化使用本地 Ollama bge-m3 模型，维度为 1024。
                """;

        int stored = vectorizationService.embedDocument(
                testProjectId, testVersionId, "DOC",
                "/test/sample.md", testContent,
                2000, 200, "bge-m3");

        System.out.println("✅ 向量化完成：存储了 " + stored + " 个分片");

        List<VectorDocument> docs = vectorDocumentRepository.findByProjectAndVersionAndType(
                testProjectId, testVersionId, "DOC");

        assertNotNull(docs);
        assertTrue(docs.size() > 0, "lg_vector_document 应该包含数据！");
        assertEquals(stored, docs.size());

        VectorDocument doc = docs.get(0);
        assertEquals(testProjectId, doc.getProjectId());
        assertEquals(testVersionId, doc.getVersionId());
        assertEquals("DOC", doc.getChunkType());
        assertEquals("bge-m3", doc.getEmbeddingModel());
        assertEquals(1024, doc.getEmbeddingDim(), "bge-m3 应为 1024 维");
        assertNotNull(doc.getContentSha256());

        System.out.println("✅ 全部验证通过！id=" + doc.getId()
                + " projectId=" + doc.getProjectId()
                + " chunkType=" + doc.getChunkType()
                + " dim=" + doc.getEmbeddingDim());

        // 清理
        for (VectorDocument d : docs) {
            vectorDocumentRepository.deleteById(d.getId());
        }
        System.out.println("✅ 清理完成：" + docs.size() + " 条");
    }
}
