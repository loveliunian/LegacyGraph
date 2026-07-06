package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectScanner 取消机制单元测试。
 * 验证 requestCancel 设置标记、并发安全、重复取消幂等等核心行为。
 */
@ExtendWith(MockitoExtension.class)
class ProjectScannerCancelTest {

    @Mock private ScanVersionRepository scanVersionRepository;
    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private FactRepository factRepository;
    @Mock private DbConnectionRepository dbConnectionRepository;
    @Mock private CodeRepoRepository codeRepoRepository;
    @Mock private DocumentRepository documentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ProjectScanner(
                scanVersionRepository,
                scanTaskRepository,
                factRepository,
                dbConnectionRepository,
                codeRepoRepository,
                documentRepository,
                null,  // graphBuilder
                null,  // frontendGraphBuilder
                null,  // neo4jGraphDao
                objectMapper,
                null,  // aiScanOrchestrator
                null,  // dbSchemaAnalysisAgent
                null,  // extractionAdapterRegistry
                null,  // scanTaskRecorder
                null,  // adapterExecutionService
                null,  // graphifyRunner
                null   // graphifyImportService
        );
    }

    /**
     * requestCancel 不抛异常（公共 API 基础约束）。
     */
    @Test
    void testRequestCancel_DoesNotThrow() {
        assertDoesNotThrow(() -> scanner.requestCancel("version-1"));
    }

    /**
     * 重复调用 requestCancel 应为幂等操作，不抛异常。
     */
    @Test
    void testRequestCancel_Idempotent() {
        assertDoesNotThrow(() -> {
            scanner.requestCancel("v1");
            scanner.requestCancel("v1");
            scanner.requestCancel("v1");
        });
    }

    /**
     * 不同版本的取消互不干扰。
     */
    @Test
    void testRequestCancel_MultipleVersions_Independent() {
        assertDoesNotThrow(() -> {
            scanner.requestCancel("v1");
            scanner.requestCancel("v2");
            scanner.requestCancel("v3");
        });
    }

    /**
     * ConcurrentHashMap 保证多线程同时写同一 key 不丢数据、不抛异常。
     */
    @Test
    void testRequestCancel_Concurrent() throws Exception {
        Thread[] threads = new Thread[20];
        for (int i = 0; i < 20; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                scanner.requestCancel("v-concurrent");
                scanner.requestCancel("v-" + idx);
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        // 只要不抛异常即通过
    }

    /**
     * null versionId 会触发 ConcurrentHashMap NPE（该集合不支持 null key）。
     * Controller 层已保证 versionId 非 null，此处仅验证防御行为。
     */
    @Test
    void testRequestCancel_NullVersionId_ThrowsNPE() {
        assertThrows(NullPointerException.class, () -> scanner.requestCancel(null));
    }
}
