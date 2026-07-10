package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DbSchemaAnalysisAgent;
import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.extractors.adapter.ExtractionAdapterRegistry;
import io.github.legacygraph.integration.graphify.GraphifyImportService;
import io.github.legacygraph.integration.graphify.GraphifyRunner;
import io.github.legacygraph.repository.*;
import io.github.legacygraph.service.scan.BlastRadiusAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectScanner affected 标记清理集成测试（Task 8）。
 *
 * <p>验证 {@link ProjectScanner#runScanBody} 在扫描开始前调用
 * {@link BlastRadiusAnalyzer#clearAffectedMarkers} 清除上一版本残留的 affected 标记，
 * 且清除失败时不阻塞扫描主流程。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProjectScannerTest {

    @TempDir
    Path tempDir;

    @Mock private ScanVersionRepository scanVersionRepository;
    @Mock private ScanTaskRepository scanTaskRepository;
    @Mock private FactRepository factRepository;
    @Mock private DbConnectionRepository dbConnectionRepository;
    @Mock private CodeRepoRepository codeRepoRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private GraphBuilder graphBuilder;
    @Mock private FrontendGraphBuilder frontendGraphBuilder;
    @Mock private Neo4jGraphDao neo4jGraphDao;
    @Mock private AiScanOrchestrator aiScanOrchestrator;
    @Mock private DbSchemaAnalysisAgent dbSchemaAnalysisAgent;
    @Mock private ExtractionAdapterRegistry adapterRegistry;
    @Mock private GraphifyRunner graphifyRunner;
    @Mock private GraphifyImportService graphifyImportService;
    @Mock private BlastRadiusAnalyzer blastRadiusAnalyzer;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectScanner scanner;

    private static final String PROJECT_ID = "project-aff-001";
    private static final String VERSION_ID = "version-aff-001";

    @BeforeEach
    void setUp() {
        scanner = new ProjectScanner(
                scanVersionRepository,
                scanTaskRepository,
                factRepository,
                dbConnectionRepository,
                codeRepoRepository,
                documentRepository,
                graphBuilder,
                frontendGraphBuilder,
                neo4jGraphDao,
                objectMapper,
                aiScanOrchestrator,
                dbSchemaAnalysisAgent,
                adapterRegistry,
                null,  // scanTaskRecorder → 走 fallback 测试路径
                null,  // adapterExecutionService
                graphifyRunner,
                graphifyImportService
        );

        // blastRadiusAnalyzer 通过 @Autowired(required=false) 字段注入，无 setter，用反射注入 mock
        setField(scanner, "blastRadiusAnalyzer", blastRadiusAnalyzer);

        // 公共 lenient mock：让 runScanBody 能跑完（空项目目录 + 无 DB 连接 → 全量扫描发现 0 资产）
        lenient().when(scanTaskRepository.insert(any(ScanTask.class))).thenReturn(1);
        lenient().when(scanTaskRepository.updateById(any(ScanTask.class))).thenReturn(1);
        lenient().when(scanVersionRepository.updateById(any(ScanVersion.class))).thenReturn(1);
        lenient().when(dbConnectionRepository.selectList(any())).thenReturn(List.of());
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(List.of());
        lenient().when(documentRepository.selectList(any())).thenReturn(List.of());
        lenient().when(adapterRegistry.getAllAdapters()).thenReturn(List.of());
        lenient().when(neo4jGraphDao.countNodes(anyString(), anyString(), isNull())).thenReturn(0L);
        lenient().when(neo4jGraphDao.countEdges(anyString(), anyString(), isNull())).thenReturn(0L);

        // factRepository.lambdaQuery() 链式调用
        @SuppressWarnings("unchecked")
        var factChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        lenient().when(factRepository.lambdaQuery()).thenReturn(factChain);
        lenient().when(factChain.eq(any(), any())).thenReturn(factChain);
        lenient().when(factChain.count()).thenReturn(0L);

        // scanTaskRepository.lambdaQuery() 链式调用
        @SuppressWarnings("unchecked")
        var taskChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        lenient().when(scanTaskRepository.lambdaQuery()).thenReturn(taskChain);
        lenient().when(taskChain.eq(any(), any())).thenReturn(taskChain);
        lenient().when(taskChain.list()).thenReturn(List.of());
    }

    /** 通过反射注入字段（blastRadiusAnalyzer 无 setter） */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 通过反射调用私有 runScanBody 方法 */
    private void invokeRunScanBody(ScanVersion version) throws Exception {
        Method runScanBody = ProjectScanner.class.getDeclaredMethod(
                "runScanBody", String.class, String.class, String.class, ScanVersion.class);
        runScanBody.setAccessible(true);
        runScanBody.invoke(scanner, PROJECT_ID, VERSION_ID, tempDir.toString(), version);
    }

    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> classToMock) {
        return org.mockito.Mockito.mock(classToMock);
    }

    /**
     * 验证 runScanBody 入口调用 clearAffectedMarkers。
     * 扫描开始前应清除当前 versionId 下上一轮残留的 affected 标记。
     */
    @Test
    void runScanBody_shouldClearAffectedMarkersAtEntry() throws Exception {
        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        version.setProjectId(PROJECT_ID);
        version.setScanStatus("RUNNING");

        when(blastRadiusAnalyzer.clearAffectedMarkers(PROJECT_ID, VERSION_ID)).thenReturn(5);

        invokeRunScanBody(version);

        // 核心断言：runScanBody 入口调用了 clearAffectedMarkers(projectId, versionId)
        verify(blastRadiusAnalyzer).clearAffectedMarkers(PROJECT_ID, VERSION_ID);
    }

    /**
     * 验证 clearAffectedMarkers 抛异常时不阻塞扫描。
     * 异常应被 try-catch 捕获并 log.warn，扫描继续执行直到完成。
     */
    @Test
    void runScanBody_clearAffectedMarkersFailure_shouldNotBlockScan() throws Exception {
        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        version.setProjectId(PROJECT_ID);
        version.setScanStatus("RUNNING");

        when(blastRadiusAnalyzer.clearAffectedMarkers(PROJECT_ID, VERSION_ID))
                .thenThrow(new RuntimeException("Neo4j connection lost"));

        invokeRunScanBody(version);

        // 清除失败不应阻塞扫描：扫描应正常完成（versionId @Value 未注入 → aiEnableDefault=false → 置 SUCCESS）
        assertEquals("SUCCESS", version.getScanStatus(),
                "清除 affected 标记失败时不应阻塞扫描");
        verify(blastRadiusAnalyzer).clearAffectedMarkers(PROJECT_ID, VERSION_ID);
    }
}
