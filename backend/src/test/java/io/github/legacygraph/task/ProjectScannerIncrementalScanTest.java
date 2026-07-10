package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.dto.scan.ResolvedScanPlan;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.extractors.adapter.ExtractionAdapter;
import io.github.legacygraph.extractors.adapter.ExtractionAdapterRegistry;
import io.github.legacygraph.extractors.adapter.ExtractionResult;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.extractors.adapter.SourceAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 1 单元测试：验证 ADAPTER_SCAN 增量扫描在 0 变更时不退化为全量。
 *
 * <p>覆盖三个场景：
 * <ol>
 *   <li>增量模式 + 0 变更（changedPaths 为空集合）→ assetsToExtract 为空，不执行抽取</li>
 *   <li>非增量模式（changedPaths=null）→ 仍全量执行</li>
 *   <li>增量模式 + 部分变更 → 只处理变更文件</li>
 * </ol>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ProjectScannerIncrementalScanTest {

    @TempDir
    Path tempDir;

    @Mock private io.github.legacygraph.repository.ScanVersionRepository scanVersionRepository;
    @Mock private io.github.legacygraph.repository.ScanTaskRepository scanTaskRepository;
    @Mock private io.github.legacygraph.repository.FactRepository factRepository;
    @Mock private io.github.legacygraph.repository.DbConnectionRepository dbConnectionRepository;
    @Mock private io.github.legacygraph.repository.CodeRepoRepository codeRepoRepository;
    @Mock private io.github.legacygraph.repository.DocumentRepository documentRepository;
    @Mock private GraphBuilder graphBuilder;
    @Mock private io.github.legacygraph.builder.FrontendGraphBuilder frontendGraphBuilder;
    @Mock private io.github.legacygraph.dao.Neo4jGraphDao neo4jGraphDao;
    @Mock private AiScanOrchestrator aiScanOrchestrator;
    @Mock private io.github.legacygraph.agent.DbSchemaAnalysisAgent dbSchemaAnalysisAgent;
    @Mock private ExtractionAdapterRegistry adapterRegistry;
    @Mock private ExtractionAdapter adapter;
    @Mock private AssetDiscoveryService assetDiscoveryService;

    private static final String PROJECT_ID = "project-inc-001";
    private static final String VERSION_ID = "version-inc-001";

    private ProjectScanner scanner;
    private List<SourceAsset> assets;

    @BeforeEach
    void setUp() throws Exception {
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
                new ObjectMapper(),
                aiScanOrchestrator,
                dbSchemaAnalysisAgent,
                adapterRegistry,
                null,  // scanTaskRecorder → 走 fallback 测试路径
                null,  // adapterExecutionService → 走 fallback 顺序循环
                null,  // graphifyRunner
                null   // graphifyImportService
        );
        scanner.setScanPlanningServices(null, assetDiscoveryService);

        // 构造 3 个真实文件作为发现的资产
        Path f1 = tempDir.resolve("A.java");
        Path f2 = tempDir.resolve("B.java");
        Path f3 = tempDir.resolve("C.java");
        Files.writeString(f1, "class A {}");
        Files.writeString(f2, "class B {}");
        Files.writeString(f3, "class C {}");
        assets = List.of(
                SourceAsset.builder().file(f1).relativePath("A.java").fileType("java").build(),
                SourceAsset.builder().file(f2).relativePath("B.java").fileType("java").build(),
                SourceAsset.builder().file(f3).relativePath("C.java").fileType("java").build()
        );

        // 公共 lenient mock
        lenient().when(assetDiscoveryService.discoverAssets(any())).thenReturn(
                new AssetDiscoveryService.DiscoveryResult(assets.size(), assets));
        lenient().when(adapterRegistry.selectAdapters(any(), any())).thenReturn(List.of(adapter));
        lenient().when(adapter.extract(any(), any())).thenReturn(ExtractionResult.builder()
                .processedAssets(1).summary("ok").build());
        // 增量跳过判断返回 false，避免与被测逻辑混淆
        lenient().when(assetDiscoveryService.isIncrementalSkip(any(), anyString(), anyString())).thenReturn(false);
        // detectDeletions 返回 null，避免触发 neo4j 删除路径
        lenient().when(assetDiscoveryService.detectDeletions(anyString(), anyString(), any())).thenReturn(null);
    }

    /** 通过反射调用 private scanDiscoveredAssetsWithAdapters */
    private int invokeScanDiscovered(ResolvedScanPlan plan, Set<String> changedPaths) throws Exception {
        ScanContext context = ScanContext.builder()
                .projectId(PROJECT_ID)
                .versionId(VERSION_ID)
                .baseDir(tempDir.toString())
                .config(new java.util.concurrent.ConcurrentHashMap<>())
                .build();
        Method m = ProjectScanner.class.getDeclaredMethod(
                "scanDiscoveredAssetsWithAdapters",
                String.class, String.class, ScanContext.class,
                ResolvedScanPlan.class, ScanTask.class, Set.class);
        m.setAccessible(true);
        return (int) m.invoke(scanner, PROJECT_ID, VERSION_ID, context, plan, new ScanTask(), changedPaths);
    }

    private ResolvedScanPlan plan(boolean incremental) {
        return ResolvedScanPlan.builder()
                .projectId(PROJECT_ID)
                .versionId(VERSION_ID)
                .incremental(incremental)
                .maxFiles(100)
                .build();
    }

    /**
     * 场景 1：增量模式 + 0 变更（changedPaths 为空集合，非 null）。
     * 修复前：!changedPaths.isEmpty()=false → assetsToExtract=assets（全量 3 个），BUG。
     * 修复后：plan.isIncremental() && changedPaths!=null → assetsToExtract=List.of()，不执行抽取。
     */
    @Test
    void incrementalMode_zeroChangedPaths_skipsExtraction() throws Exception {
        int processed = invokeScanDiscovered(plan(true), new HashSet<>());

        assertEquals(0, processed, "增量模式 0 变更时应跳过抽取，processed=0");
        verify(adapter, never()).extract(any(), any());
    }

    /**
     * 场景 2：非增量模式（changedPaths=null）。
     * 不进入增量过滤分支，assetsToExtract=assets（全量），所有文件都被抽取。
     */
    @Test
    void nonIncrementalMode_nullChangedPaths_fullExtraction() throws Exception {
        int processed = invokeScanDiscovered(plan(false), null);

        assertEquals(assets.size(), processed, "非增量模式应全量抽取");
        verify(adapter, times(assets.size())).extract(any(), any());
    }

    /**
     * 场景 3：增量模式 + 部分变更（changedPaths 含 1 个文件）。
     * 只对变更文件执行抽取。
     */
    @Test
    void incrementalMode_partialChangedPaths_extractsOnlyChanged() throws Exception {
        Set<String> changed = new HashSet<>();
        changed.add("B.java");

        int processed = invokeScanDiscovered(plan(true), changed);

        assertEquals(1, processed, "增量模式仅处理变更文件");
        verify(adapter, times(1)).extract(any(), any());
    }
}
