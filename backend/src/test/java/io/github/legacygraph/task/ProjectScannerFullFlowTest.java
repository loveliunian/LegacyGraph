package io.github.legacygraph.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DbSchemaAnalysisAgent;
import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.extractors.adapter.ExtractionAdapter;
import io.github.legacygraph.extractors.adapter.ExtractionAdapterRegistry;
import io.github.legacygraph.extractors.adapter.ExtractionResult;
import io.github.legacygraph.integration.graphify.GraphifyImportService;
import io.github.legacygraph.integration.graphify.GraphifyRunResult;
import io.github.legacygraph.integration.graphify.GraphifyRunner;
import io.github.legacygraph.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProjectScanner 完整扫描流程端到端单元测试。
 *
 * 贴合实际场景：创建真实的项目目录结构（Java/XML/Vue/YAML/MD），
 * 验证每个扫描阶段（发现→抽取→图谱→AI编排→统计快照）的调用链。
 *
 * 覆盖的阶段：
 *   0a. DB_DISCOVERY  — 从 application.yml 自动发现数据库连接
 *   0b. PATH_DISCOVERY — 自动检测前后端子路径
 *   0c. DOC_DISCOVERY — 发现文档文件（.md）
 *   1.  ADAPTER_SCAN  — 适配器抽取（Java/XML/Vue）
 *   4.  DATABASE_SCAN — 数据库元数据扫描
 *   4b. GRAPHIFY_ANALYZE — Graphify 外部工具分析
 *   5.  GRAPH_BUILD   — 图谱构建标记
 *   6.  AI_ORCHESTRATION — AI 编排入队
 *   7.  Stats Snapshot  — 统计快照写入
 */
@ExtendWith(MockitoExtension.class)
class ProjectScannerFullFlowTest {

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
    @Mock private ExtractionAdapter javaAdapter;
    @Mock private ExtractionAdapter xmlAdapter;
    @Mock private ExtractionAdapter vueAdapter;
    @Mock private GraphifyRunner graphifyRunner;
    @Mock private GraphifyImportService graphifyImportService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectScanner scanner;

    private static final String PROJECT_ID = "project-test-001";
    private static final String VERSION_ID = "version-flow-001";

    @BeforeEach
    void setUp() throws Exception {
        // 构造真实项目目录结构
        createRealisticProjectStructure();

        // 构建 scanner（scanTaskRecorder=null → 走 fallback 测试路径）
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
                null,  // scanTaskRecorder → 走 fallback
                null,  // adapterExecutionService
                graphifyRunner,
                graphifyImportService
        );

        // 默认 mock 行为
        lenient().when(scanTaskRepository.insert(any(ScanTask.class))).thenAnswer(inv -> {
            ScanTask t = inv.getArgument(0);
            if (t.getId() == null) t.setId("task-" + t.getTaskType());
            return 1;
        });
        lenient().when(scanTaskRepository.updateById(any(ScanTask.class))).thenReturn(1);
        lenient().when(dbConnectionRepository.selectList(any())).thenReturn(List.of());
        lenient().when(neo4jGraphDao.countNodes(anyString(), anyString(), isNull())).thenReturn(42L);
        lenient().when(neo4jGraphDao.countEdges(anyString(), anyString(), isNull())).thenReturn(18L);
        lenient().when(factRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));

        // Adapter registry: Java → javaAdapter, XML → xmlAdapter, Vue/TS → vueAdapter
        lenient().when(adapterRegistry.selectAdapters(any(), any())).thenAnswer(inv -> {
            var asset = inv.getArgument(1);
            String fileType = asset.getClass().getMethod("getFileType").invoke(asset).toString();
            return switch (fileType.toLowerCase()) {
                case "java" -> java.util.List.of(javaAdapter);
                case "xml" -> java.util.List.of(xmlAdapter);
                case "vue", "ts" -> java.util.List.of(vueAdapter);
                default -> java.util.List.of();
            };
        });

        ExtractionResult successResult = ExtractionResult.builder()
                .processedAssets(1).nodeCount(3).edgeCount(2).summary("ok").build();
        lenient().when(javaAdapter.extract(any(), any())).thenReturn(successResult);
        lenient().when(xmlAdapter.extract(any(), any())).thenReturn(successResult);
        lenient().when(vueAdapter.extract(any(), any())).thenReturn(successResult);

        // Graphify: available and successful
        lenient().when(graphifyRunner.isAvailable()).thenReturn(true);
        GraphifyRunResult graphifyResult = GraphifyRunResult.builder()
                .success(true)
                .exitCode(0)
                .stdout("")
                .stderr("")
                .elapsedMs(100)
                .timedOut(false)
                .outputDir(tempDir.resolve("graphify-out"))
                .graphJsonPath(tempDir.resolve("graphify-out/graph.json"))
                .nodeCount(15)
                .edgeCount(8)
                .build();
        lenient().when(graphifyRunner.run(any())).thenReturn(graphifyResult);

        GraphifyImportService.ImportResult importResult = GraphifyImportService.ImportResult.builder()
                .success(true)
                .processedNodes(15)
                .processedEdges(8)
                .evidenceCount(10)
                .warnings(List.of())
                .build();
        lenient().when(graphifyImportService.importGraph(anyString(), anyString(), any()))
                .thenReturn(importResult);
    }

    /**
     * 创建贴近真实的全栈项目目录结构：
     * ├── pom.xml
     * ├── package.json
     * ├── src/main/java/com/example/
     * │   ├── UserController.java
     * │   ├── UserService.java
     * │   └── mapper/UserMapper.xml
     * ├── src/main/resources/
     * │   └── application.yml          ← 含 JDBC 配置，触发 DB 自动发现
     * ├── frontend/
     * │   ├── src/App.vue
     * │   └── src/views/UserList.vue
     * └── doc/
     *     ├── README.md
     *     └── architecture.md
     */
    private void createRealisticProjectStructure() throws Exception {
        // 后端标志
        Files.writeString(tempDir.resolve("pom.xml"),
                """
                <?xml version="1.0"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>legacy-app</artifactId>
                </project>
                """);

        // 前端标志
        Files.writeString(tempDir.resolve("package.json"),
                """
                {"name":"legacy-frontend","version":"1.0.0"}
                """);

        // Java 源文件
        Path javaDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaDir);
        Files.writeString(javaDir.resolve("UserController.java"),
                """
                package com.example;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/users")
                public class UserController {
                    @Autowired
                    private UserService userService;
                    @GetMapping
                    public List<User> list() { return userService.findAll(); }
                }
                """);
        Files.writeString(javaDir.resolve("UserService.java"),
                """
                package com.example;
                import org.springframework.stereotype.Service;
                @Service
                public class UserService {
                    public List<User> findAll() { return List.of(); }
                }
                """);

        // MyBatis XML
        Path mapperDir = javaDir.resolve("mapper");
        Files.createDirectories(mapperDir);
        Files.writeString(mapperDir.resolve("UserMapper.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.mapper.UserMapper">
                    <select id="selectAll" resultType="com.example.User">
                        SELECT * FROM sys_user
                    </select>
                </mapper>
                """);

        // 配置文件（含 JDBC URL，触发 DB 自动发现）
        Path resourceDir = tempDir.resolve("src/main/resources");
        Files.createDirectories(resourceDir);
        Files.writeString(resourceDir.resolve("application.yml"),
                """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/legacy_db
                    username: appuser
                    password: secret123
                  jpa:
                    hibernate:
                      ddl-auto: none
                """);

        // 前端文件
        Path frontendDir = tempDir.resolve("frontend/src");
        Files.createDirectories(frontendDir);
        Files.writeString(frontendDir.resolve("App.vue"),
                """
                <template><div id="app"><router-view/></div></template>
                <script setup lang="ts">
                import { useRoute } from 'vue-router'
                </script>
                """);
        Path viewsDir = frontendDir.resolve("views");
        Files.createDirectories(viewsDir);
        Files.writeString(viewsDir.resolve("UserList.vue"),
                """
                <template><div><UserTable/></div></template>
                <script setup lang="ts">
                import UserTable from '../components/UserTable.vue'
                </script>
                """);

        // 文档
        Path docDir = tempDir.resolve("doc");
        Files.createDirectories(docDir);
        Files.writeString(docDir.resolve("README.md"), "# Legacy App\nSystem documentation.");
        Files.writeString(docDir.resolve("architecture.md"), "# Architecture\nMicroservices design.");
    }

    @Test
    void fullScanFlow_shouldExecuteAllPhasesAndMarkSuccess() throws Exception {
        // ===== 准备 ScanVersion =====
        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        version.setProjectId(PROJECT_ID);
        version.setScanStatus("PENDING");
        version.setScanScope(null); // 全量扫描，不指定 scope

        // ===== Mock 关键依赖 =====
        lenient().when(scanVersionRepository.getById(VERSION_ID)).thenReturn(version);
        when(scanVersionRepository.updateById(any(ScanVersion.class))).thenReturn(1);

        // CodeRepo 存在（FULLSTACK 类型，触发子路径检测）
        CodeRepo repo = new CodeRepo();
        repo.setId("repo-001");
        repo.setProjectId(PROJECT_ID);
        repo.setRepoType("FULLSTACK");
        repo.setLocalPath(tempDir.toString());
        repo.setBackendSubPath(null);
        repo.setFrontendSubPath(null);
        when(codeRepoRepository.selectList(any())).thenReturn(List.of(repo));
        lenient().when(codeRepoRepository.updateById(any(CodeRepo.class))).thenReturn(1);

        // DB Discovery: 模拟从 application.yml 发现连接后写入
        lenient().when(dbConnectionRepository.selectList(any())).thenReturn(List.of());

        // Document 查询（文档发现阶段）
        lenient().when(documentRepository.selectList(any())).thenReturn(List.of());
        lenient().when(documentRepository.insert(any(Document.class))).thenReturn(1);

        // Fact 查询（统计快照）
        @SuppressWarnings("unchecked")
        var factQueryChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        when(factRepository.lambdaQuery()).thenReturn(factQueryChain);
        when(factQueryChain.eq(any(), any())).thenReturn(factQueryChain);
        when(factQueryChain.count()).thenReturn(5L);

        // ScanTask 查询（统计快照）
        List<ScanTask> createdTasks = new ArrayList<>();
        when(scanTaskRepository.insert(any(ScanTask.class))).thenAnswer(inv -> {
            ScanTask t = inv.getArgument(0);
            createdTasks.add(t);
            return 1;
        });
        when(scanTaskRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        // 简化：统计快照时返回已创建的任务列表
        var taskQueryChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        when(scanTaskRepository.lambdaQuery()).thenReturn(taskQueryChain);
        when(taskQueryChain.eq(any(), any())).thenReturn(taskQueryChain);
        when(taskQueryChain.list()).thenReturn(createdTasks);

        // ===== 通过反射调用 runScanBody（绕过 @Async） =====
        Method runScanBody = ProjectScanner.class.getDeclaredMethod(
                "runScanBody", String.class, String.class, String.class, ScanVersion.class);
        runScanBody.setAccessible(true);
        runScanBody.invoke(scanner, PROJECT_ID, VERSION_ID, tempDir.toString(), version);

        // ===== 验证 1: ScanVersion 最终状态为 SUCCESS =====
        assertEquals("SUCCESS", version.getScanStatus(),
                "扫描完成后状态应为 SUCCESS");
        assertNotNull(version.getFinishedAt(), "finishedAt 应被设置");

        // ===== 验证 2: 各阶段 ScanTask 均已创建 =====
        List<String> createdTaskTypes = createdTasks.stream()
                .map(ScanTask::getTaskType)
                .toList();

        assertTrue(createdTaskTypes.contains("DB_DISCOVERY"),
                "应创建 DB_DISCOVERY 任务");
        assertTrue(createdTaskTypes.contains("PATH_DISCOVERY"),
                "应创建 PATH_DISCOVERY 任务");
        assertTrue(createdTaskTypes.contains("DOC_DISCOVERY"),
                "应创建 DOC_DISCOVERY 任务");
        assertTrue(createdTaskTypes.contains("ADAPTER_SCAN"),
                "应创建 ADAPTER_SCAN 任务");
        assertFalse(createdTaskTypes.contains("GRAPHIFY_ANALYZE"),
                "默认扫描不应隐式触发 Graphify，Graphify 必须由 scanTypes 显式选择");
        assertTrue(createdTaskTypes.contains("GRAPH_BUILD"),
                "应创建 GRAPH_BUILD 任务");

        // ===== 验证 3: Adapter Registry 被调用（Java/XML/Vue 文件被处理） =====
        // adapterRegistry.selectAdapter 至少被调用（遍历了 Java、XML、Vue 文件）
        verify(adapterRegistry, atLeastOnce()).selectAdapters(any(), any());

        // ===== 验证 4: 默认不调用 Graphify Runner =====
        verify(graphifyRunner, never()).isAvailable();
        verify(graphifyRunner, never()).run(any(Path.class));
        verify(graphifyImportService, never()).importGraph(eq(PROJECT_ID), eq(VERSION_ID), any());

        // ===== 验证 6: Neo4j 图谱统计被调用 =====
        verify(neo4jGraphDao, atLeastOnce()).countNodes(PROJECT_ID, VERSION_ID, null);

        // ===== 验证 7: AI 编排被处理（默认未启用，调用 recordSkipped） =====
        verify(aiScanOrchestrator, atLeastOnce()).recordSkipped(PROJECT_ID, VERSION_ID);

        // ===== 验证 7: ScanVersion 统计快照被写入 =====
        assertNotNull(version.getNodeCount(), "nodeCount 快照应被设置");
        assertEquals(42L, version.getNodeCount());

        // ===== 验证 8: ScanVersion 被持久化 =====
        verify(scanVersionRepository).updateById(version);
    }

    @Test
    void fullScanFlow_withCancelRequested_shouldNotMarkSuccess() throws Exception {
        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        version.setProjectId(PROJECT_ID);
        version.setScanStatus("RUNNING");

        lenient().when(scanVersionRepository.getById(VERSION_ID)).thenReturn(version);
        lenient().when(scanVersionRepository.updateById(any(ScanVersion.class))).thenReturn(1);
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(List.of());
        lenient().when(dbConnectionRepository.selectList(any())).thenReturn(List.of());
        lenient().when(documentRepository.selectList(any())).thenReturn(List.of());
        lenient().when(factRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));
        lenient().when(scanTaskRepository.lambdaQuery()).thenReturn(
                mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class));

        // 在扫描启动前请求取消
        scanner.requestCancel(VERSION_ID);

        Method runScanBody = ProjectScanner.class.getDeclaredMethod(
                "runScanBody", String.class, String.class, String.class, ScanVersion.class);
        runScanBody.setAccessible(true);
        runScanBody.invoke(scanner, PROJECT_ID, VERSION_ID, tempDir.toString(), version);

        // 取消后不应标记 SUCCESS（保持 RUNNING 或被覆盖）
        assertNotEquals("SUCCESS", version.getScanStatus(),
                "取消后扫描状态不应为 SUCCESS");

        // Graphify 不应被调用（扫描在 DB_DISCOVERY 检查点后提前返回）
        verify(graphifyRunner, never()).run(any());
    }

    @Test
    void fullScanFlow_graphifyUnavailable_shouldStillSucceed() throws Exception {
        // Graphify 不可用时的完整流程
        lenient().when(graphifyRunner.isAvailable()).thenReturn(false);

        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        version.setProjectId(PROJECT_ID);
        version.setScanStatus("PENDING");

        lenient().when(scanVersionRepository.getById(VERSION_ID)).thenReturn(version);
        lenient().when(scanVersionRepository.updateById(any(ScanVersion.class))).thenReturn(1);
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(List.of());
        lenient().when(dbConnectionRepository.selectList(any())).thenReturn(List.of());
        lenient().when(documentRepository.selectList(any())).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        var factChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        lenient().when(factRepository.lambdaQuery()).thenReturn(factChain);
        lenient().when(factChain.eq(any(), any())).thenReturn(factChain);
        lenient().when(factChain.count()).thenReturn(0L);

        var taskChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        lenient().when(scanTaskRepository.lambdaQuery()).thenReturn(taskChain);
        lenient().when(taskChain.eq(any(), any())).thenReturn(taskChain);
        lenient().when(taskChain.list()).thenReturn(List.of());

        Method runScanBody = ProjectScanner.class.getDeclaredMethod(
                "runScanBody", String.class, String.class, String.class, ScanVersion.class);
        runScanBody.setAccessible(true);
        runScanBody.invoke(scanner, PROJECT_ID, VERSION_ID, tempDir.toString(), version);

        // 即使 Graphify 不可用，整体扫描仍应成功
        assertEquals("SUCCESS", version.getScanStatus());

        // Graphify run 不应被调用
        verify(graphifyRunner, never()).run(any());
        verify(graphifyImportService, never()).importGraph(anyString(), anyString(), any());
    }

    @Test
    void fullScanFlow_explicitGraphifyFailureCompletesGraphifyTask() throws Exception {
        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        version.setProjectId(PROJECT_ID);
        version.setScanStatus("PENDING");
        version.setScanScope("{\"scanTypes\":[\"CODE_SCAN\",\"GRAPHIFY_ANALYZE\"]}");

        lenient().when(scanVersionRepository.getById(VERSION_ID)).thenReturn(version);
        lenient().when(scanVersionRepository.updateById(any(ScanVersion.class))).thenReturn(1);
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(List.of());
        lenient().when(dbConnectionRepository.selectList(any())).thenReturn(List.of());
        lenient().when(documentRepository.selectList(any())).thenReturn(List.of());

        List<ScanTask> createdTasks = new ArrayList<>();
        when(scanTaskRepository.insert(any(ScanTask.class))).thenAnswer(inv -> {
            ScanTask t = inv.getArgument(0);
            createdTasks.add(t);
            return 1;
        });

        var factChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        lenient().when(factRepository.lambdaQuery()).thenReturn(factChain);
        lenient().when(factChain.eq(any(), any())).thenReturn(factChain);
        lenient().when(factChain.count()).thenReturn(0L);

        var taskChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        lenient().when(scanTaskRepository.lambdaQuery()).thenReturn(taskChain);
        lenient().when(taskChain.eq(any(), any())).thenReturn(taskChain);
        lenient().when(taskChain.list()).thenReturn(createdTasks);

        when(graphifyRunner.isAvailable()).thenReturn(true);
        when(graphifyRunner.run(any(Path.class)))
                .thenThrow(new GraphifyRunner.GraphifyRunException("graphify crashed"));

        Method runScanBody = ProjectScanner.class.getDeclaredMethod(
                "runScanBody", String.class, String.class, String.class, ScanVersion.class);
        runScanBody.setAccessible(true);
        runScanBody.invoke(scanner, PROJECT_ID, VERSION_ID, tempDir.toString(), version);

        ScanTask graphifyTask = createdTasks.stream()
                .filter(t -> "GRAPHIFY_ANALYZE".equals(t.getTaskType()))
                .findFirst()
                .orElseThrow();
        assertEquals("FAILED", graphifyTask.getTaskStatus());
        assertEquals("graphify crashed", graphifyTask.getErrorMessage());
        assertEquals("SUCCESS", version.getScanStatus(),
                "Graphify 是非阻塞阶段，失败不应拖垮原生扫描");
    }

    @Test
    void fullScanFlow_withScanScopeCodeOnly_shouldSkipDbAndGraphify() throws Exception {
        // 指定 scanScope 仅做 CODE_SCAN
        ScanVersion version = new ScanVersion();
        version.setId(VERSION_ID);
        version.setProjectId(PROJECT_ID);
        version.setScanStatus("PENDING");
        version.setScanScope("{\"scanTypes\":[\"CODE_SCAN\"]}");

        lenient().when(scanVersionRepository.getById(VERSION_ID)).thenReturn(version);
        lenient().when(scanVersionRepository.updateById(any(ScanVersion.class))).thenReturn(1);
        lenient().when(codeRepoRepository.selectList(any())).thenReturn(List.of());
        lenient().when(dbConnectionRepository.selectList(any())).thenReturn(List.of());
        lenient().when(documentRepository.selectList(any())).thenReturn(List.of());

        var factChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        lenient().when(factRepository.lambdaQuery()).thenReturn(factChain);
        lenient().when(factChain.eq(any(), any())).thenReturn(factChain);
        lenient().when(factChain.count()).thenReturn(0L);

        var taskChain = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class);
        lenient().when(scanTaskRepository.lambdaQuery()).thenReturn(taskChain);
        lenient().when(taskChain.eq(any(), any())).thenReturn(taskChain);
        lenient().when(taskChain.list()).thenReturn(List.of());

        Method runScanBody = ProjectScanner.class.getDeclaredMethod(
                "runScanBody", String.class, String.class, String.class, ScanVersion.class);
        runScanBody.setAccessible(true);
        runScanBody.invoke(scanner, PROJECT_ID, VERSION_ID, tempDir.toString(), version);

        assertEquals("SUCCESS", version.getScanStatus());

        // DB_SCAN 不在 scanTypes 中，不应扫描数据库
        // Graphify 不在 scanTypes 中，不应调用
        verify(graphifyRunner, never()).run(any());

        // 但 Adapter 扫描应正常执行
        verify(adapterRegistry, atLeastOnce()).selectAdapters(any(), any());
    }
}
