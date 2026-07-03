package io.github.legacygraph.task;

import io.github.legacygraph.agent.DbSchemaAnalysisAgent;
import io.github.legacygraph.builder.FrontendGraphBuilder;
import io.github.legacygraph.builder.GraphBuilder;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.dto.DbSchemaAnalysis;
import io.github.legacygraph.dto.scan.ResolvedDbScope;
import io.github.legacygraph.dto.scan.ResolvedDocScope;
import io.github.legacygraph.dto.scan.ResolvedRepoScope;
import io.github.legacygraph.dto.scan.ResolvedScanPlan;
import io.github.legacygraph.entity.CodeRepo;
import io.github.legacygraph.entity.DbConnection;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.extractors.adapter.ExtractionAdapter;
import io.github.legacygraph.extractors.adapter.ExtractionAdapterRegistry;
import io.github.legacygraph.extractors.adapter.ExtractionResult;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.extractors.adapter.SourceAsset;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.github.legacygraph.service.DatabaseMetadataScanService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 项目扫描器
 * 协调整个扫描过程，分发到各个抽取器，最后构建图谱。
 * 同时自动发现：数据库连接配置、前后端子路径、文档文件。
 */
@Slf4j
@Component
public class ProjectScanner {

    private final ScanVersionRepository scanVersionRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final FactRepository factRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final CodeRepoRepository codeRepoRepository;
    private final DocumentRepository documentRepository;
    private final GraphBuilder graphBuilder;
    private final FrontendGraphBuilder frontendGraphBuilder;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ObjectMapper objectMapper;
    private final AiScanOrchestrator aiScanOrchestrator;
    private final DbSchemaAnalysisAgent dbSchemaAnalysisAgent;
    private final ExtractionAdapterRegistry extractionAdapterRegistry;
    private final DatabaseMetadataScanService databaseMetadataScanService;
    private final ScanTaskRecorder scanTaskRecorder;
    private final AdapterExecutionService adapterExecutionService;
    private ScanScopeResolver scanScopeResolver;
    private AssetDiscoveryService assetDiscoveryService;

    /** 图谱/报告缓存失效器（可选）：重新扫描前清空旧图谱只读缓存，避免读到陈旧数据 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.service.GraphCacheInvalidator graphCacheInvalidator;

    /** 扫描后 AI 编排默认开关（legacy-graph.ai.*），scanScope 未显式指定时生效 */
    @org.springframework.beans.factory.annotation.Value("${legacy-graph.ai.enable-default:true}")
    private boolean aiEnableDefault;
    @org.springframework.beans.factory.annotation.Value("${legacy-graph.ai.auto-generate-test-case-default:false}")
    private boolean aiAutoGenerateTestCaseDefault;
    @org.springframework.beans.factory.annotation.Value("${legacy-graph.ai.min-confidence-default:0.6}")
    private double aiMinConfidenceDefault;

    /** 取消注册表：Controller 写入 signal，runScanBody 检查点读取。ConcurrentHashMap 保证多线程安全 */
    private final ConcurrentHashMap<String, Boolean> cancelledVersions = new ConcurrentHashMap<>();

    /** 请求取消指定版本的扫描（由 Controller 调用） */
    public void requestCancel(String versionId) {
        cancelledVersions.put(versionId, true);
        log.info("Cancel requested for versionId={}", versionId);
    }

    /** 检查版本是否已被取消（由 runScanBody 检查点调用） */
    private boolean isCancelled(String versionId) {
        return cancelledVersions.getOrDefault(versionId, false);
    }

    /** 清除取消标记（扫描启动时调用） */
    private void clearCancel(String versionId) {
        cancelledVersions.remove(versionId);
    }

    /** 后端项目标志文件 */
    private static final List<String> BACKEND_INDICATORS = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "src/main/java", "src/main/kotlin", "src/main/resources"
    );
    /** 前端项目标志文件 */
    private static final List<String> FRONTEND_INDICATORS = List.of(
            "package.json", "vite.config.ts", "vite.config.js",
            "src/App.vue", "src/main.ts", "src/main.js", "src/app"
    );
    /** 文档文件扩展名 */
    private static final Set<String> DOC_EXTENSIONS = Set.of(
            ".md", ".pdf", ".docx", ".txt", ".rst", ".adoc"
    );
    /** JDBC URL 匹配正则 */
    private static final Pattern JDBC_URL_PATTERN = Pattern.compile(
            "jdbc:(postgresql|mysql|mariadb|oracle|sqlserver|h2)://([^:/]+):?(\\d+)?/([^?\\s}]+)",
            Pattern.CASE_INSENSITIVE);
    private static final int PROGRESS_LOG_INTERVAL = 10;

    public ProjectScanner(ScanVersionRepository scanVersionRepository,
                         ScanTaskRepository scanTaskRepository,
                         FactRepository factRepository,
                         DbConnectionRepository dbConnectionRepository,
                         CodeRepoRepository codeRepoRepository,
                         DocumentRepository documentRepository,
                         GraphBuilder graphBuilder,
                         FrontendGraphBuilder frontendGraphBuilder,
                         Neo4jGraphDao neo4jGraphDao,
                         ObjectMapper objectMapper,
                         AiScanOrchestrator aiScanOrchestrator,
                         DbSchemaAnalysisAgent dbSchemaAnalysisAgent,
                         ExtractionAdapterRegistry extractionAdapterRegistry,
                         ScanTaskRecorder scanTaskRecorder,
                         AdapterExecutionService adapterExecutionService) {
        this(scanVersionRepository, scanTaskRepository, factRepository, dbConnectionRepository,
                codeRepoRepository, documentRepository, graphBuilder, frontendGraphBuilder,
                neo4jGraphDao, objectMapper, aiScanOrchestrator, dbSchemaAnalysisAgent,
                extractionAdapterRegistry, new DatabaseMetadataScanService(graphBuilder), scanTaskRecorder, adapterExecutionService);
    }

    @Autowired
    public ProjectScanner(ScanVersionRepository scanVersionRepository,
                         ScanTaskRepository scanTaskRepository,
                         FactRepository factRepository,
                         DbConnectionRepository dbConnectionRepository,
                         CodeRepoRepository codeRepoRepository,
                         DocumentRepository documentRepository,
                         GraphBuilder graphBuilder,
                         FrontendGraphBuilder frontendGraphBuilder,
                         Neo4jGraphDao neo4jGraphDao,
	                         ObjectMapper objectMapper,
	                         AiScanOrchestrator aiScanOrchestrator,
	                         DbSchemaAnalysisAgent dbSchemaAnalysisAgent,
	                         ExtractionAdapterRegistry extractionAdapterRegistry,
	                         DatabaseMetadataScanService databaseMetadataScanService,
	                         ScanTaskRecorder scanTaskRecorder,
	                         AdapterExecutionService adapterExecutionService) {
	                         this.scanVersionRepository = scanVersionRepository;
        this.scanTaskRepository = scanTaskRepository;
        this.factRepository = factRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.codeRepoRepository = codeRepoRepository;
        this.documentRepository = documentRepository;
        this.graphBuilder = graphBuilder;
        this.frontendGraphBuilder = frontendGraphBuilder;
        this.neo4jGraphDao = neo4jGraphDao;
        this.objectMapper = objectMapper;
        this.aiScanOrchestrator = aiScanOrchestrator;
        this.dbSchemaAnalysisAgent = dbSchemaAnalysisAgent;
        this.extractionAdapterRegistry = extractionAdapterRegistry;
        this.databaseMetadataScanService = databaseMetadataScanService;
        this.scanTaskRecorder = scanTaskRecorder;
        this.adapterExecutionService = adapterExecutionService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScanPlanningServices(ScanScopeResolver scanScopeResolver,
                                 AssetDiscoveryService assetDiscoveryService) {
        this.scanScopeResolver = scanScopeResolver;
        this.assetDiscoveryService = assetDiscoveryService;
    }

    /**
     * 异步启动完整扫描流程
     */
    @Async
    public void startFullScan(String projectId, String versionId, String baseDir) {
        log.info("Starting full scan: projectId={}, versionId={}, baseDir={}", projectId, versionId, baseDir);

        // 清除之前的取消标记
        clearCancel(versionId);

        // 重新扫描会重建图谱，先失效该版本的图谱/报告只读缓存、进度缓存与项目概览
        if (graphCacheInvalidator != null) {
            graphCacheInvalidator.invalidateVersion(versionId);
            graphCacheInvalidator.invalidateProjectOverview(projectId);
        }

        ScanVersion version = scanVersionRepository.getById(versionId);
        if (version != null) {
            version.setScanStatus("RUNNING");
            version.setStartedAt(LocalDateTime.now());
            scanVersionRepository.updateById(version);
        }

        runScanBody(projectId, versionId, baseDir, version);
    }

    /**
     * 扫描主体逻辑（同步）。由 {@link #startFullScan} / {@link #resumeFullScan} 两个 @Async 入口调用，
     * 避免同类内部直接调用 @Async 方法导致 Spring AOP 代理失效（B-H8：原 resumeFullScan 内部
     * 调用 startFullScan 时 @Async 不生效，实际同步执行）。
     */
    private void runScanBody(String projectId, String versionId, String baseDir, ScanVersion version) {
        try {
            ResolvedScanPlan resolvedPlan = resolveScanPlan(projectId, versionId, version);
            // 解析 scanScope JSON，提取 repoIds/dbIds/docIds/scanTypes 用于过滤扫描范围
            List<String> scopeRepoIds = null;
            List<String> scopeDbIds = null;
            List<String> scopeDocIds = null;
            List<String> scopeScanTypes = null;
            if (resolvedPlan != null) {
                scopeRepoIds = repoIdsFromPlan(resolvedPlan);
                scopeDbIds = dbIdsFromPlan(resolvedPlan);
                scopeDocIds = docIdsFromPlan(resolvedPlan);
                scopeScanTypes = scanTypesFromPlan(resolvedPlan);
            } else if (version != null && version.getScanScope() != null && !version.getScanScope().isBlank()) {
                try {
                    JsonNode scopeNode = objectMapper.readTree(version.getScanScope());
                    if (scopeNode.has("repoIds") && scopeNode.get("repoIds").isArray()) {
                        scopeRepoIds = new ArrayList<>();
                        for (JsonNode n : scopeNode.get("repoIds")) scopeRepoIds.add(n.asText());
                    }
                    if (scopeNode.has("dbIds") && scopeNode.get("dbIds").isArray()) {
                        scopeDbIds = new ArrayList<>();
                        for (JsonNode n : scopeNode.get("dbIds")) scopeDbIds.add(n.asText());
                    }
                    if (scopeNode.has("docIds") && scopeNode.get("docIds").isArray()) {
                        scopeDocIds = new ArrayList<>();
                        for (JsonNode n : scopeNode.get("docIds")) scopeDocIds.add(n.asText());
                    }
                    if (scopeNode.has("scanTypes") && scopeNode.get("scanTypes").isArray()) {
                        scopeScanTypes = new ArrayList<>();
                        for (JsonNode n : scopeNode.get("scanTypes")) scopeScanTypes.add(n.asText());
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse scanScope for filtering, will scan all: {}", e.getMessage());
                }
            }

            // 如果 baseDir 为空，从 scope 中指定的 repoIds 解析本地代码路径
            if (baseDir == null || baseDir.isBlank()) {
                LambdaQueryWrapper<CodeRepo> baseRepoQuery = new LambdaQueryWrapper<CodeRepo>()
                        .eq(CodeRepo::getProjectId, projectId);
                if (scopeRepoIds != null && !scopeRepoIds.isEmpty()) {
                    baseRepoQuery.in(CodeRepo::getId, scopeRepoIds);
                }
                List<CodeRepo> baseRepos = codeRepoRepository.selectList(baseRepoQuery);
                if (!baseRepos.isEmpty()) {
                    CodeRepo firstRepo = baseRepos.get(0);
                    baseDir = firstRepo.getLocalPath();
                    if (baseDir == null || baseDir.isBlank()) {
                        baseDir = System.getProperty("user.home") + "/.legacygraph/repos/" + projectId + "/" + firstRepo.getId();
                    }
                    log.info("Scan still running: projectId={}, versionId={}, detail=resolved baseDir from scope repos: {}",
                            projectId, versionId, baseDir);
                } else {
                    log.warn("No code repo found for project {} and baseDir is null — code scanning will be skipped", projectId);
                }
            }

            // 读取 CodeRepo 配置，按 scope 过滤，获取子路径和 include/exclude 规则
            LambdaQueryWrapper<CodeRepo> repoQuery = new LambdaQueryWrapper<CodeRepo>()
                    .eq(CodeRepo::getProjectId, projectId);
            if (scopeRepoIds != null && !scopeRepoIds.isEmpty()) {
                repoQuery.in(CodeRepo::getId, scopeRepoIds);
            }
            List<CodeRepo> repos = codeRepoRepository.selectList(repoQuery);
            String backendDir = baseDir;
            String frontendDir = baseDir;
            if (!repos.isEmpty() && baseDir != null) {
                CodeRepo repo = repos.get(0);
                if (repo.getBackendSubPath() != null && !repo.getBackendSubPath().isBlank()) {
                    backendDir = Paths.get(baseDir, repo.getBackendSubPath()).toString();
                    log.info("Using backend sub-path: {}", backendDir);
                }
                if (repo.getFrontendSubPath() != null && !repo.getFrontendSubPath().isBlank()) {
                    frontendDir = Paths.get(baseDir, repo.getFrontendSubPath()).toString();
                    log.info("Using frontend sub-path: {}", frontendDir);
                }
            }

            // === 0. 自动发现：数据库连接、子路径、文档 ===
            log.info("Scan still running: projectId={}, versionId={}, phase=DISCOVERY, detail=starting auto-discovery phase",
                    projectId, versionId);

            // 0a. 从代码中自动发现数据库连接配置
            ScanTask dbDiscoveryTask = createTask(projectId, versionId, "DB_DISCOVERY", "数据库连接自动发现");
            int dbCount = discoverDbConnections(projectId, baseDir, dbDiscoveryTask);
            completeTask(dbDiscoveryTask, "Discovered " + dbCount + " database connections", null);
            log.info("Auto-discovered {} database connections", dbCount);

            if (isCancelled(versionId)) return;

            // 0b. 自动检测前后端子路径，回填 CodeRepo
            ScanTask pathDiscoveryTask = createTask(projectId, versionId, "PATH_DISCOVERY", "前后端路径自动检测");
            int pathCount = discoverSubPaths(projectId, baseDir);
            completeTask(pathDiscoveryTask, "Updated " + pathCount + " repo sub-paths", null);
            log.info("Auto-detected sub-paths for {} repos", pathCount);

            if (isCancelled(versionId)) return;

            // 0c. 自动发现文档文件（仅在未指定 scanTypes 或包含 DOC_PARSE 时执行）
            boolean shouldScanDocs = scopeScanTypes == null || scopeScanTypes.isEmpty()
                    || scopeScanTypes.contains("DOC_PARSE");
            if (shouldScanDocs) {
                ScanTask docDiscoveryTask = createTask(projectId, versionId, "DOC_DISCOVERY", "文档自动发现");
                int docCount = discoverDocuments(projectId, versionId, baseDir, docDiscoveryTask);
                completeTask(docDiscoveryTask, "Discovered " + docCount + " documents", null);
                log.info("Auto-discovered {} documents", docCount);
            } else {
                log.info("Scan still running: projectId={}, versionId={}, phase=DOC_DISCOVERY, detail=skipped (DOC_PARSE not in scanTypes)",
                        projectId, versionId);
            }

            if (isCancelled(versionId)) return;

            // 0d. Adapter Registry 扫描：仅在 CODE_SCAN 已启用时执行代码结构抽取
            if (isCancelled(versionId)) return;
            boolean shouldScanCode = scopeScanTypes == null || scopeScanTypes.isEmpty()
                    || scopeScanTypes.contains("CODE_SCAN");
            int adapterCount = 0;
            if (shouldScanCode) {
                log.info("Scan still running: projectId={}, versionId={}, phase=ADAPTER_SCAN, detail=starting adapter registry scan",
                        projectId, versionId);
                ScanTask adapterTask = createTask(projectId, versionId, "ADAPTER_SCAN", "适配器抽取扫描");
                adapterCount = scanAssetsWithAdapters(projectId, versionId, baseDir, backendDir, frontendDir, adapterTask, resolvedPlan);
                completeTask(adapterTask, "Adapter processed " + adapterCount + " assets", null);
                log.info("Adapter registry scan processed {} assets", adapterCount);
                if (adapterCount > 0) {
                    log.info("Scan still running: projectId={}, versionId={}, phase=CODE_SCAN, detail=completed by Adapter Registry ({} assets)",
                            projectId, versionId, adapterCount);
                } else {
                    log.warn("Scan still running: projectId={}, versionId={}, phase=CODE_SCAN, detail=no supported source assets found by Adapter Registry",
                            projectId, versionId);
                }
            } else {
                log.info("Scan still running: projectId={}, versionId={}, phase=CODE_SCAN, detail=skipped (CODE_SCAN not in scanTypes)",
                        projectId, versionId);
            }

            // 4. 扫描所有已配置数据库的元数据（按 scope 过滤）
            // 仅在未指定 scanTypes 或包含 DB_SCAN 时执行
            if (isCancelled(versionId)) return;
            boolean shouldScanDb = scopeScanTypes == null || scopeScanTypes.isEmpty()
                    || scopeScanTypes.contains("DB_SCAN");
            if (shouldScanDb) {
            LambdaQueryWrapper<DbConnection> dbQuery = new LambdaQueryWrapper<DbConnection>()
                    .eq(DbConnection::getProjectId, projectId)
                    .eq(DbConnection::getStatus, "READY");
            if (scopeDbIds != null && !scopeDbIds.isEmpty()) {
                dbQuery.in(DbConnection::getId, scopeDbIds);
            }
            List<DbConnection> dbConnections = dbConnectionRepository.selectList(dbQuery);

            if (!dbConnections.isEmpty()) {
                log.info("Scan still running: projectId={}, versionId={}, phase=DATABASE_SCAN, detail=scanning {} databases",
                        projectId, versionId, dbConnections.size());
                ScanTask dbTask = createTask(projectId, versionId, "DATABASE_SCAN", "数据库元数据扫描");
                int totalTables = 0;
                int processedConnections = 0;
                logTaskProgress(dbTask, 0, dbConnections.size(), "database connections");
                for (DbConnection conn : dbConnections) {
                    if (isCancelled(versionId)) break;
                    try {
                        DataSource dataSource = createDataSource(conn);
                        totalTables += scanDatabaseMetadata(projectId, versionId, dataSource, conn);
                    } catch (Exception e) {
                        log.warn("Failed to scan database connection id={} host={}:{}/{} dbType={}: {}",
                                conn.getId(), conn.getHost(), conn.getPort(),
                                conn.getDatabaseName(), conn.getDbType(), e.getMessage());
                    } finally {
                        processedConnections++;
                        logTaskProgress(dbTask, processedConnections, dbConnections.size(), "database connections");
                    }
                }
                completeTask(dbTask, "Scanned " + dbConnections.size() + " databases, " + totalTables + " tables", null);
                log.info("Completed database metadata scan, {} databases, {} tables", dbConnections.size(), totalTables);

                // LLM 语义增强：异步执行，不阻塞扫描主流程
                if (totalTables > 0) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            String schemaText = graphBuilder.buildDbSchemaSummary(projectId, versionId);
                            if (schemaText != null && !schemaText.isBlank()) {
                                enrichDbGraphWithLLM(projectId, versionId, schemaText);
                            }
                        } catch (Exception e) {
                            log.warn("DB schema LLM enrichment failed (non-blocking): {}", e.getMessage());
                        }
                    });
                    log.info("DB schema LLM enrichment dispatched asynchronously: versionId={}", versionId);
                }
            } else {
                log.info("Scan still running: projectId={}, versionId={}, phase=DATABASE_SCAN, detail=no READY database connections found for project",
                        projectId, versionId);
            }
            } else {
                log.info("Scan still running: projectId={}, versionId={}, phase=DATABASE_SCAN, detail=skipped (DB_SCAN not in scanTypes)",
                        projectId, versionId);
            }

            // 5. 图谱已由各 Builder 在扫描过程中直写 Neo4j，无需额外同步步骤
            // 此子任务仅作为扫描阶段标记，不执行实质操作
            log.info("Scan still running: projectId={}, versionId={}, phase=GRAPH_BUILD, detail=graph built in Neo4j",
                    projectId, versionId);
            ScanTask buildTask = createTask(projectId, versionId, "GRAPH_BUILD", "图谱构建");
            completeTask(buildTask, "Graph built in Neo4j", null);

            // 6. 扫描后 AI 编排
            // DOC_PARSE 未显式指定 AI 开关时默认开启 AI；显式 enableAi=false 必须被尊重。
            if (isCancelled(versionId)) return;
            try {
                log.info("Scan still running: projectId={}, versionId={}, taskType=AI_ORCHESTRATION, detail=starting",
                        projectId, versionId);
                AiScanConfig aiConfig = resolveAiConfigForScan(
                        version != null ? version.getScanScope() : null,
                        scopeScanTypes,
                        objectMapper,
                        aiEnableDefault,
                        aiAutoGenerateTestCaseDefault,
                        aiMinConfidenceDefault);
                if (aiConfig.isEnableAi()) {
                    aiScanOrchestrator.enqueue(projectId, versionId, aiConfig);
                } else {
                    aiScanOrchestrator.recordSkipped(projectId, versionId);
                }
                log.info("Scan still running: projectId={}, versionId={}, taskType=AI_ORCHESTRATION, detail=completed",
                        projectId, versionId);
            } catch (Exception aiEx) {
                // AI 编排失败不应使整个扫描失败
                log.error("AI orchestration failed (scan still SUCCESS): versionId={}", versionId, aiEx);
            }

            if (version != null) {
                // 最终检查：如果在 AI 编排期间被取消，不要覆盖 DB 中的 CANCELLED
                if (!isCancelled(versionId)) {
                    version.setScanStatus("SUCCESS");
                }
                version.setFinishedAt(LocalDateTime.now());
                // 回写节点/边/事实/子任务统计快照，供列表接口零 IO 读取
                applyStatsSnapshot(version, projectId, versionId);
                scanVersionRepository.updateById(version);
            }

            // 扫描完成汇总：输出 Neo4j 节点数，快速判断图谱是否有数据
            long totalNodes = 0;
            try {
                totalNodes = neo4jGraphDao.countNodes(projectId, versionId, null);
            } catch (Exception ex) {
                log.warn("Failed to count Neo4j nodes for summary: {}", ex.getMessage());
            }
            log.info("Full scan completed successfully: versionId={}, neo4jNodes={}", versionId, totalNodes);

        } catch (Exception e) {
            log.error("Scan failed: versionId={}", versionId, e);
            // ⚠️ B-H9 扫描无自动重试：失败仅置 FAILED，需手动调用 resumeFullScan 续扫。
            //   建议：引入状态机 + 自动重试（Spring Retry @Retryable 或 Quartz 调度）对瞬时失败自动续扫。
            if (version != null) {
                version.setScanStatus("FAILED");
                version.setErrorMessage(e.getMessage());
                version.setFinishedAt(LocalDateTime.now());
                // 失败时也回写快照（可能是部分数据），避免列表接口继续实时查 Neo4j
                applyStatsSnapshot(version, projectId, versionId);
                scanVersionRepository.updateById(version);
            }
        }
    }

    static AiScanConfig resolveAiConfigForScan(String scanScope,
                                               List<String> scopeScanTypes,
                                               ObjectMapper objectMapper,
                                               boolean aiEnableDefault,
                                               boolean aiAutoGenerateTestCaseDefault,
                                               double aiMinConfidenceDefault) {
        boolean docParseEnabled = scopeScanTypes != null && scopeScanTypes.contains("DOC_PARSE");
        AiScanConfig aiDefaults = new AiScanConfig();
        aiDefaults.setEnableAi(aiEnableDefault || docParseEnabled);
        aiDefaults.setAutoGenerateTestCase(aiAutoGenerateTestCaseDefault);
        aiDefaults.setMinConfidence(aiMinConfidenceDefault);
        return AiScanConfig.fromScanScope(scanScope, objectMapper, aiDefaults);
    }

    private ResolvedScanPlan resolveScanPlan(String projectId, String versionId, ScanVersion version) {
        if (scanScopeResolver == null) {
            return null;
        }
        try {
            return scanScopeResolver.resolve(projectId, versionId, version != null ? version.getScanScope() : null);
        } catch (Exception e) {
            log.debug("Failed to resolve scan plan, fallback to legacy scanScope parsing: {}", e.getMessage());
            return null;
        }
    }

    private List<String> repoIdsFromPlan(ResolvedScanPlan plan) {
        if (plan.getRepos() == null) {
            return List.of();
        }
        return plan.getRepos().stream()
                .map(ResolvedRepoScope::getRepoId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private List<String> dbIdsFromPlan(ResolvedScanPlan plan) {
        if (plan.getDatabases() == null) {
            return List.of();
        }
        return plan.getDatabases().stream()
                .map(ResolvedDbScope::getConnectionId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private List<String> docIdsFromPlan(ResolvedScanPlan plan) {
        if (plan.getDocuments() == null) {
            return List.of();
        }
        return plan.getDocuments().stream()
                .map(ResolvedDocScope::getDocId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private List<String> scanTypesFromPlan(ResolvedScanPlan plan) {
        if (plan.getScanTypes() == null) {
            return List.of();
        }
        return new ArrayList<>(plan.getScanTypes());
    }

    /**
     * 汇总本次扫描的节点/边/事实/子任务统计，写入 ScanVersion 冗余字段。
     * 每处失败均单独 try/catch：任何统计失败都不能影响主扫描落 SUCCESS/FAILED 状态。
     * 仅在扫描终态调用（成功/失败），列表接口对 RUNNING 状态版本仍走批量聚合兜底。
     */
    public void applyStatsSnapshot(ScanVersion version, String projectId, String versionId) {
        try {
            long nodeCount = 0L;
            try {
                nodeCount = neo4jGraphDao.countNodes(projectId, versionId, null);
            } catch (Exception ex) {
                log.warn("Snapshot: countNodes failed versionId={}: {}", versionId, ex.getMessage());
            }
            long edgeCount = 0L;
            try {
                edgeCount = neo4jGraphDao.countEdges(projectId, versionId, null);
            } catch (Exception ex) {
                log.warn("Snapshot: countEdges failed versionId={}: {}", versionId, ex.getMessage());
            }
            long factCount = 0L;
            try {
                factCount = factRepository.lambdaQuery()
                        .eq(Fact::getVersionId, versionId)
                        .count();
            } catch (Exception ex) {
                log.warn("Snapshot: countFacts failed versionId={}: {}", versionId, ex.getMessage());
            }

            int taskTotal = 0, taskSuccess = 0, taskFailed = 0;
            String stage = "-";
            try {
                List<ScanTask> tasks = scanTaskRepository.lambdaQuery()
                        .eq(ScanTask::getVersionId, versionId)
                        .list();
                taskTotal = tasks.size();
                for (ScanTask t : tasks) {
                    if ("SUCCESS".equals(t.getTaskStatus())) taskSuccess++;
                    else if ("FAILED".equals(t.getTaskStatus())) taskFailed++;
                }
                stage = tasks.stream()
                        .filter(t -> !"SUCCESS".equals(t.getTaskStatus()))
                        .findFirst()
                        .map(ScanTask::getTaskType)
                        .orElse(taskTotal > 0 ? "COMPLETED" : "-");
            } catch (Exception ex) {
                log.warn("Snapshot: countTasks failed versionId={}: {}", versionId, ex.getMessage());
            }

            version.setNodeCount(nodeCount);
            version.setEdgeCount(edgeCount);
            version.setFactCount(factCount);
            version.setTaskTotal(taskTotal);
            version.setTaskSuccess(taskSuccess);
            version.setTaskFailed(taskFailed);
            version.setCurrentStage(stage);
            version.setStatsUpdatedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("applyStatsSnapshot unexpected failure versionId={}: {}", versionId, e.getMessage());
        }
    }

    // ==================== 自动发现 ====================

    /**
     * 从代码中自动发现数据库连接配置
     * 扫描 application*.yml / application*.properties / application*.yaml，提取 datasource 配置
     * 如果同一 (host+port+database) 的连接已存在则更新，否则新建
     * @return 新创建或更新的数据库连接数量
     */
    private int discoverDbConnections(String projectId, String baseDir, ScanTask task) {
        if (baseDir == null) return 0;
        int count = 0;
        try {
            Path basePath = Paths.get(baseDir);
            if (!Files.exists(basePath)) return 0;

            // 查找配置文件
            log.info("Scan still running: projectId={}, phase=DB_DISCOVERY, detail=walking for config files", projectId);
            List<Path> configFiles = Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return (name.startsWith("application") || name.startsWith("application-"))
                                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
                    })
                    .limit(10)
                    .collect(Collectors.toList());

            Set<String> discoveredUrls = new HashSet<>(); // 本次扫描去重
            int total = configFiles.size();
            scanTaskRecorder.logProgress(task, 0, total, "config files", null);

            int idx = 0;
            for (Path configFile : configFiles) {
                try {
                    idx++;
                    scanTaskRecorder.logProgress(task, idx, total, "config files", configFile.getFileName().toString());
                    String content = Files.readString(configFile);
                    Map<String, String> dbConfig = extractDatasourceConfig(content, configFile.getFileName().toString());
                    if (dbConfig != null && !dbConfig.isEmpty()) {
                        String jdbcUrl = dbConfig.get("url");
                        if (jdbcUrl != null && discoveredUrls.add(jdbcUrl)) {
                            String dbType = dbConfig.getOrDefault("dbType", "POSTGRESQL").toUpperCase();
                            String schema = dbType.equals("MYSQL") || dbType.equals("MARIADB")
                                    ? "" : dbConfig.getOrDefault("schema", "public");
                            String host = dbConfig.getOrDefault("host", "localhost");
                            int port = Integer.parseInt(dbConfig.getOrDefault("port",
                                    dbType.equals("MYSQL") || dbType.equals("MARIADB") ? "3306" : "5432"));
                            String database = dbConfig.getOrDefault("database", "unknown");

                            // 查找是否已有相同 (host, port, database) 的连接
                            DbConnection existing = dbConnectionRepository.selectOne(
                                    new LambdaQueryWrapper<DbConnection>()
                                            .eq(DbConnection::getProjectId, projectId)
                                            .eq(DbConnection::getHost, host)
                                            .eq(DbConnection::getPort, port)
                                            .eq(DbConnection::getDatabaseName, database)
                            );

                            if (existing != null) {
                                // 更新已有连接
                                existing.setDbType(dbType);
                                existing.setSchemaName(schema);
                                existing.setUsername(dbConfig.getOrDefault("username", existing.getUsername()));
                                existing.setPassword(dbConfig.getOrDefault("password", existing.getPassword()));
                                existing.setConnectionName(autoDbName(dbConfig, configFile));
                                existing.setUpdatedAt(LocalDateTime.now());
                                dbConnectionRepository.updateById(existing);
                                log.info("Updated existing DB connection: {} (id={})", existing.getConnectionName(), existing.getId());
                            } else {
                                // 新建连接
                                DbConnection conn = new DbConnection();
                                conn.setProjectId(projectId);
                                conn.setConnectionName(autoDbName(dbConfig, configFile));
                                conn.setDbType(dbType);
                                conn.setHost(host);
                                conn.setPort(port);
                                conn.setDatabaseName(database);
                                conn.setSchemaName(schema);
                                conn.setUsername(dbConfig.getOrDefault("username", ""));
                                conn.setPassword(dbConfig.getOrDefault("password", ""));
                                conn.setStatus("READY");
                                conn.setCreatedBy("auto-discovery");
                                conn.setCreatedAt(LocalDateTime.now());
                                conn.setUpdatedAt(LocalDateTime.now());
                                dbConnectionRepository.insert(conn);
                                log.info("Auto-discovered DB connection: {} from {}", conn.getConnectionName(), configFile);
                            }
                            count++;
                        }
                    }
                } catch (Exception e) {
                    log.debug("Skipping config file {}: {}", configFile, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("DB connection auto-discovery failed: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 从配置文件内容中提取数据源配置
     */
    private Map<String, String> extractDatasourceConfig(String content, String fileName) {
        Map<String, String> result = new HashMap<>();

        if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            parseYamlDatasource(content, result);
        } else {
            parsePropertiesDatasource(content, result);
        }

        // 如果没找到 spring.datasource，尝试从 JDBC URL 正则匹配（跳过注释行）
        if (!result.containsKey("url")) {
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
                Matcher m = JDBC_URL_PATTERN.matcher(trimmed);
                if (m.find()) {
                    result.put("url", resolvePlaceholder(trimmed.substring(m.start()).trim()));
                    result.put("dbType", m.group(1));
                    result.put("host", m.group(2));
                    result.put("port", m.group(3) != null ? m.group(3) : defaultPort(m.group(1)));
                    result.put("database", m.group(4));
                    break; // 取第一个非注释的 URL
                }
            }
        }

        // 解析所有值中的 ${VAR:default} 占位符
        result.replaceAll((k, v) -> resolvePlaceholder(v));

        return result;
    }

    /**
     * 解析 ${ENV_VAR:default_value} 格式的占位符。
     * 优先用系统环境变量，否则取冒号后的默认值；无默认值则保留原字符串。
     */
    private String resolvePlaceholder(String value) {
        if (value == null) return null;
        // 匹配 ${ENV_VAR:default} 或 ${ENV_VAR}
        Matcher m = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}").matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String envVar = m.group(1);
            String defaultValue = m.group(2);
            String resolved = System.getenv(envVar);
            if (resolved == null) resolved = System.getProperty(envVar);
            if (resolved == null) resolved = defaultValue;
            if (resolved == null) resolved = m.group(0); // keep original
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void parseYamlDatasource(String content, Map<String, String> result) {
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(content);
            if (root == null) return;

            Object spring = root.get("spring");
            if (spring instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> springMap = (Map<String, Object>) spring;
                Object ds = springMap.get("datasource");
                if (ds instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dsMap = (Map<String, Object>) ds;
                    // 标准 spring.datasource.{url,username,password,driver-class-name}
                    if (dsMap.get("url") instanceof String url) extractUrlAndParse(result, url);
                    if (dsMap.get("username") instanceof String u) result.put("username", resolvePlaceholder(u));
                    if (dsMap.get("password") instanceof String p) result.put("password", resolvePlaceholder(p));
                    if (dsMap.get("driver-class-name") instanceof String d) result.putIfAbsent("dbType", driverToDbType(resolvePlaceholder(d)));
                    if (dsMap.get("driverClassName") instanceof String dc) result.putIfAbsent("dbType", driverToDbType(resolvePlaceholder(dc)));

                    // Druid 嵌套: spring.datasource.druid.master.{url,username,password}
                    Object druid = dsMap.get("druid");
                    if (druid instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> druidMap = (Map<String, Object>) druid;
                        Object master = druidMap.get("master");
                        if (master instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> masterMap = (Map<String, Object>) master;
                            if (masterMap.get("url") instanceof String mu) extractUrlAndParse(result, mu);
                            if (masterMap.get("username") instanceof String mu) result.putIfAbsent("username", mu);
                            if (masterMap.get("password") instanceof String mp) result.putIfAbsent("password", mp);
                            if (masterMap.get("driver-class-name") instanceof String md) result.putIfAbsent("dbType", driverToDbType(resolvePlaceholder(md)));
                        }
                    }

                    // Hikari 嵌套: spring.datasource.hikari.{url,username,password}
                    Object hikari = dsMap.get("hikari");
                    if (hikari instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> hikariMap = (Map<String, Object>) hikari;
                        if (hikariMap.get("url") instanceof String hu) extractUrlAndParse(result, hu);
                        if (hikariMap.get("username") instanceof String hu) result.putIfAbsent("username", hu);
                        if (hikariMap.get("password") instanceof String hp) result.putIfAbsent("password", hp);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("YAML parse failed: {}", e.getMessage());
        }
    }

    /** 从 URL 字符串中提取并解析 JDBC 信息（先解析占位符再提取字段，防止 ${VAR:...} 中的特殊字符污染解析结果） */
    private void extractUrlAndParse(Map<String, String> result, String url) {
        String resolved = resolvePlaceholder(url);
        result.put("url", resolved);
        parseJdbcUrl(resolved, result);
    }

    private void parsePropertiesDatasource(String content, Map<String, String> result) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith("spring.datasource.url")) {
                String url = extractPropertyValue(line);
                String resolved = resolvePlaceholder(url);
                result.put("url", resolved);
                parseJdbcUrl(resolved, result);
            } else if (line.startsWith("spring.datasource.username")) {
                result.put("username", resolvePlaceholder(extractPropertyValue(line)));
            } else if (line.startsWith("spring.datasource.password")) {
                result.put("password", resolvePlaceholder(extractPropertyValue(line)));
            } else if (line.startsWith("spring.datasource.driver-class-name")) {
                result.putIfAbsent("dbType", driverToDbType(resolvePlaceholder(extractPropertyValue(line))));
            }
        }
    }

    private String extractPropertyValue(String line) {
        int eq = line.indexOf('=');
        if (eq >= 0) return line.substring(eq + 1).trim();
        int colon = line.indexOf(':');
        if (colon >= 0) return line.substring(colon + 1).trim();
        return "";
    }

    private void parseJdbcUrl(String jdbcUrl, Map<String, String> result) {
        Matcher m = JDBC_URL_PATTERN.matcher(jdbcUrl);
        if (m.find()) {
            result.putIfAbsent("dbType", m.group(1));
            result.putIfAbsent("host", m.group(2));
            result.putIfAbsent("port", m.group(3) != null ? m.group(3) : defaultPort(m.group(1)));
            result.putIfAbsent("database", m.group(4));
        }
    }

    private String defaultPort(String dbType) {
        return switch (dbType.toLowerCase()) {
            case "postgresql" -> "5432";
            case "mysql", "mariadb" -> "3306";
            case "oracle" -> "1521";
            case "sqlserver" -> "1433";
            default -> "5432";
        };
    }

    private String driverToDbType(String driver) {
        if (driver == null) return "POSTGRESQL";
        return switch (driver.toLowerCase()) {
            case "org.postgresql.Driver", "org.postgresql.ds.PGSimpleDataSource" -> "POSTGRESQL";
            case "com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver" -> "MYSQL";
            case "org.mariadb.jdbc.Driver" -> "MARIADB";
            case "oracle.jdbc.OracleDriver" -> "ORACLE";
            case "com.microsoft.sqlserver.jdbc.SQLServerDriver" -> "SQL_SERVER";
            default -> "POSTGRESQL";
        };
    }

    private String autoDbName(Map<String, String> config, Path configFile) {
        String host = config.getOrDefault("host", "");
        String db = config.getOrDefault("database", "");
        if (!db.isEmpty()) return db + "@" + host;
        return "auto-" + configFile.getFileName().toString().replaceAll("\\..*$", "");
    }

    // ==================== 子路径检测 ====================

    /**
     * 自动检测前后端子路径，回填 CodeRepo 的 backendSubPath / frontendSubPath。
     * 仅在 repoType=FULLSTACK 且子路径为空时回填。
     * @return 更新的仓库数量
     */
    private int discoverSubPaths(String projectId, String baseDir) {
        if (baseDir == null) return 0;
        int count = 0;
        try {
            Path basePath = Paths.get(baseDir);
            if (!Files.exists(basePath)) return 0;

            log.info("Scan still running: projectId={}, phase=PATH_DISCOVERY, detail=detecting sub-paths", projectId);

            List<CodeRepo> repos = codeRepoRepository.selectList(
                    new LambdaQueryWrapper<CodeRepo>()
                            .eq(CodeRepo::getProjectId, projectId)
            );

            for (CodeRepo repo : repos) {
                boolean updated = false;

                // 全栈项目：检测子路径
                if ("FULLSTACK".equals(repo.getRepoType())) {
                    Path repoPath = repo.getLocalPath() != null && !repo.getLocalPath().isBlank()
                            ? Paths.get(repo.getLocalPath()) : basePath;

                    if (Files.exists(repoPath)) {
                        // 检测后端子路径
                        if (isBlank(repo.getBackendSubPath())) {
                            String backendPath = detectSubPath(repoPath, BACKEND_INDICATORS);
                            if (backendPath != null) {
                                repo.setBackendSubPath(backendPath);
                                updated = true;
                            }
                        }
                        // 检测前端子路径
                        if (isBlank(repo.getFrontendSubPath())) {
                            String frontendPath = detectSubPath(repoPath, FRONTEND_INDICATORS);
                            if (frontendPath != null) {
                                repo.setFrontendSubPath(frontendPath);
                                updated = true;
                            }
                        }
                    }
                }

                if (updated) {
                    repo.setUpdatedAt(LocalDateTime.now());
                    codeRepoRepository.updateById(repo);
                    count++;
                    log.info("Updated sub-paths for repo {}: backend={}, frontend={}",
                            repo.getRepoName(), repo.getBackendSubPath(), repo.getFrontendSubPath());
                }
            }
        } catch (Exception e) {
            log.warn("Sub-path auto-detection failed: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 在指定目录下检测子路径（包含标志文件的直接子目录名）
     */
    private String detectSubPath(Path rootPath, List<String> indicators) {
        try {
            // 先检查根目录本身
            for (String indicator : indicators) {
                if (Files.exists(rootPath.resolve(indicator))) {
                    return ".";
                }
            }
            // 检查一级子目录
            try (var dirs = Files.list(rootPath)) {
                for (Path dir : dirs.toList()) {
                    if (!Files.isDirectory(dir)) continue;
                    for (String indicator : indicators) {
                        if (Files.exists(dir.resolve(indicator))) {
                            return dir.getFileName().toString();
                        }
                    }
                }
            }
        } catch (IOException e) { log.debug("IO skipped: {}", e.getMessage()); }
        return null;
    }

    // ==================== 文档发现 ====================

    /**
     * 自动发现代码仓库中的文档文件，创建 Document 记录。
     * 写入 filePath（绝对路径）、versionId，使自动发现文档可解析。
     * @return 发现的文档数量
     */
    private int discoverDocuments(String projectId, String versionId, String baseDir, ScanTask task) {
        if (baseDir == null) return 0;
        int count = 0;
        try {
            Path basePath = Paths.get(baseDir);
            if (!Files.exists(basePath)) return 0;

            log.info("Scan still running: projectId={}, versionId={}, phase=DOC_DISCOVERY, detail=walking for document files",
                    projectId, versionId);

            // 排除 node_modules, .git, target, dist, build 等目录
            List<Path> docFiles = Files.walk(basePath)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        // 跳过二进制/构建目录中的文件
                        String pathStr = p.toString().toLowerCase();
                        if (pathStr.contains("/node_modules/") || pathStr.contains("/.git/")
                                || pathStr.contains("/target/") || pathStr.contains("/dist/")
                                || pathStr.contains("/build/") || pathStr.contains("/__pycache__/")
                                || pathStr.contains("/.idea/") || pathStr.contains("/.vscode/")) {
                            return false;
                        }
                        // 检查扩展名
                        for (String ext : DOC_EXTENSIONS) {
                            if (name.endsWith(ext)) return true;
                        }
                        return false;
                    })
                    .limit(50)
                    .collect(Collectors.toList());

            log.info("Scan still running: projectId={}, versionId={}, phase=DOC_DISCOVERY, detail=found {} document files",
                    projectId, versionId, docFiles.size());

            Path relativeRoot = basePath.toAbsolutePath();
            int total = docFiles.size();
            scanTaskRecorder.logProgress(task, 0, total, "document files", null);
            int idx = 0;
            for (Path docFile : docFiles) {
                if (isCancelled(versionId)) break;
                try {
                    idx++;
                    scanTaskRecorder.logProgress(task, idx, total, "document files", docFile.getFileName().toString());
                    String relativePath;
                    try {
                        relativePath = relativeRoot.relativize(docFile.toAbsolutePath()).toString();
                    } catch (IllegalArgumentException e) {
                        relativePath = docFile.getFileName().toString();
                    }

                    // 查找是否已有相同 (projectId, docName) 的文档
                    Document existing = documentRepository.selectOne(
                            new LambdaQueryWrapper<Document>()
                                    .eq(Document::getProjectId, projectId)
                                    .eq(Document::getDocName, relativePath)
                    );

                    if (existing != null) {
                        // 更新已有文档
                        existing.setVersionId(versionId);
                        existing.setFilePath(docFile.toAbsolutePath().toString());
                        existing.setDocType(detectDocType(docFile.getFileName().toString()));
                        existing.setFileType(detectFileType(docFile.getFileName().toString()));
                        try {
                            existing.setFileSize(Files.size(docFile));
                        } catch (IOException e) { log.debug("IO skipped: {}", e.getMessage()); }
                        existing.setParseStatus("DISCOVERED");
                        existing.setUpdatedAt(LocalDateTime.now());
                        documentRepository.updateById(existing);
                    } else {
                        Document doc = new Document();
                        doc.setProjectId(projectId);
                        doc.setVersionId(versionId);
                        doc.setDocName(relativePath);
                        doc.setFilePath(docFile.toAbsolutePath().toString());
                        doc.setDocType(detectDocType(docFile.getFileName().toString()));
                        doc.setFileType(detectFileType(docFile.getFileName().toString()));
                        try {
                            doc.setFileSize(Files.size(docFile));
                        } catch (IOException e) { log.debug("IO skipped: {}", e.getMessage()); }
                        doc.setParseStatus("DISCOVERED");
                        doc.setUploadedBy("auto-discovery");
                        doc.setCreatedAt(LocalDateTime.now());
                        doc.setUpdatedAt(LocalDateTime.now());
                        documentRepository.insert(doc);
                    }
                    count++;
                } catch (Exception e) {
                    log.debug("Skip document {}: {}", docFile, e.getMessage());
                }
            }
            log.info("Auto-discovered {} documents in {}", count, baseDir);
        } catch (Exception e) {
            log.warn("Document auto-discovery failed: {}", e.getMessage());
        }
        return count;
    }

    private String detectDocType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) return "MARKDOWN";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".txt")) return "TEXT";
        if (lower.endsWith(".rst")) return "RST";
        if (lower.endsWith(".adoc")) return "ASCIIDOC";
        return "GENERAL";
    }

    private String detectFileType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) return "MD";
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".txt")) return "TXT";
        return "OTHER";
    }

    // ==================== 数据源 ====================

    private DataSource createDataSource(DbConnection conn) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        String url = buildJdbcUrl(conn);
        dataSource.setUrl(url);
        dataSource.setUsername(conn.getUsername());
        dataSource.setPassword(conn.getPassword() != null ? conn.getPassword() : "");
        dataSource.setDriverClassName(getDriverClassName(conn.getDbType()));
        // 注意：连接超时已在 buildJdbcUrl 的 URL 参数中设置。
        // PostgreSQL 驱动使用秒为单位（connectTimeout=10, socketTimeout=30），
        // MySQL 驱动使用毫秒为单位（connectTimeout=10000, socketTimeout=30000）。
        // 不要在此处通过 connectionProperties 覆盖 URL 参数，避免单位混淆导致超时异常。
        return dataSource;
    }

    private String buildJdbcUrl(DbConnection conn) {
        String dbType = conn.getDbType();
        if (dbType == null) dbType = "postgresql";
        String host = conn.getHost() != null ? conn.getHost() : "localhost";
        int port = conn.getPort() != null ? conn.getPort()
                : ("mysql".equalsIgnoreCase(dbType) || "mariadb".equalsIgnoreCase(dbType) ? 3306 : 5432);
        String dbName = conn.getDatabaseName() != null ? conn.getDatabaseName() : "";
        return switch (dbType.toLowerCase()) {
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s?sslmode=disable&connectTimeout=10&socketTimeout=30",
                    host, port, dbName);
            case "mysql", "mariadb" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000&socketTimeout=30000",
                    host, port, dbName);
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    private String getDriverClassName(String dbType) {
        if (dbType == null) return "org.postgresql.Driver";
        return switch (dbType.toLowerCase()) {
            case "postgresql" -> "org.postgresql.Driver";
            case "mysql", "mariadb" -> "com.mysql.cj.jdbc.Driver";
            default -> "org.postgresql.Driver";
        };
    }

    // ==================== 代码扫描 ====================

    int scanAssetsWithAdapters(String projectId, String versionId,
                               String baseDir, String backendDir, String frontendDir) {
        return scanAssetsWithAdapters(projectId, versionId, baseDir, backendDir, frontendDir, null);
    }

    private int scanAssetsWithAdapters(String projectId, String versionId,
                                       String baseDir, String backendDir, String frontendDir, ScanTask task) {
        return scanAssetsWithAdapters(projectId, versionId, baseDir, backendDir, frontendDir, task, null);
    }

    private int scanAssetsWithAdapters(String projectId, String versionId,
                                       String baseDir, String backendDir, String frontendDir,
                                       ScanTask task, ResolvedScanPlan resolvedPlan) {
        if (baseDir == null || extractionAdapterRegistry == null) {
            return 0;
        }
        Path root = Paths.get(baseDir);
        if (!Files.exists(root)) {
            return 0;
        }
        ScanContext context = ScanContext.builder()
                .projectId(projectId)
                .versionId(versionId)
                .baseDir(baseDir)
                .backendDir(backendDir)
                .frontendDir(frontendDir)
                .config(Map.of())
                .build();

        if (assetDiscoveryService != null) {
            ResolvedScanPlan effectivePlan = resolvedPlan != null
                    ? resolvedPlan
                    : singleRepoPlan(projectId, versionId, baseDir, backendDir, frontendDir);
            return scanDiscoveredAssetsWithAdapters(projectId, versionId, context, effectivePlan, task);
        }

        try {
            // 排除巨型目录 + 仅收集有适配器能处理的文件类型
            List<Path> files = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String pathStr = p.toString();
                        return !pathStr.contains("/node_modules/") && !pathStr.contains("/.git/")
                                && !pathStr.contains("/target/") && !pathStr.contains("/dist/")
                                && !pathStr.contains("/build/") && !pathStr.contains("/__pycache__/")
                                && !pathStr.contains("/.idea/") && !pathStr.contains("/.vscode/");
                    })
                    .filter(this::isAdapterCandidate)
                    .collect(Collectors.toList());

            int total = files.size();
            if (total == 0) {
                logTaskProgress(task, 0, 0, "adapter candidate files");
                return 0;
            }
            logTaskProgress(task, 0, total, "adapter candidate files");

            // 并发处理：Semaphore 限流防止连接池耗尽（虚拟线程 I/O 阻塞时自动切换）
            // HikariCP max pool=20，这里限制并发数为 16 留 4 给其他扫描阶段
            int maxConcurrency = Math.max(1, Integer.getInteger("legacy-graph.scan.adapter-concurrency", 16));
            java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(maxConcurrency);
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            try {
                var visited = new java.util.concurrent.atomic.AtomicInteger(0);
                var processed = new java.util.concurrent.atomic.AtomicInteger(0);

                List<java.util.concurrent.Callable<Void>> tasks = files.stream()
                        .<java.util.concurrent.Callable<Void>>map(file -> () -> {
                            // 任务级取消检查：已取消的扫描跳过后续文件处理
                            if (isCancelled(versionId)) {
                                return null;
                            }
                            semaphore.acquire();
                            try {
                                SourceAsset asset = toSourceAsset(root, file);
                                var adapter = extractionAdapterRegistry.selectAdapter(context, asset);
                                if (adapter.isEmpty()) {
                                    return null;
                                }
                                try {
                                    ExtractionResult result = adapter.get().extract(context, asset);
                                    if (result != null && result.getProcessedAssets() > 0) {
                                        processed.addAndGet(result.getProcessedAssets());
                                    } else {
                                        processed.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    log.warn("Adapter {} failed for {}: {}",
                                            adapter.get().capability().getName(), asset.getRelativePath(), e.getMessage());
                                }
                            } catch (Exception e) {
                                log.debug("Skip file {}: {}", file, e.getMessage());
                            } finally {
                                semaphore.release();
                                int v = visited.incrementAndGet();
                                if (v % PROGRESS_LOG_INTERVAL == 0 || v == total) {
                                    logTaskProgress(task, v, total, "adapter candidate files");
                                }
                            }
                            return null;
                        })
                        .toList();

                executor.invokeAll(tasks);
                return processed.get();
            } finally {
                executor.shutdown();
            }
        } catch (Exception e) {
            log.warn("Adapter scan failed for {}: {}", baseDir, e.getMessage());
            return 0;
        }
    }

    private int scanDiscoveredAssetsWithAdapters(String projectId, String versionId,
                                                 ScanContext context,
                                                 ResolvedScanPlan plan,
                                                 ScanTask task) {
        List<SourceAsset> assets = assetDiscoveryService.discoverAssets(plan);

        // 使用并发 Adapter 执行器（替换原顺序 for 循环）
        // 若 adapterExecutionService 为 null（测试环境），回退到直调 adapter registry
        int processed;
        if (adapterExecutionService != null) {
            processed = adapterExecutionService.executeDiscoveredAssets(
                    context, assets, task,
                    () -> isCancelled(versionId),
                    plan.isIncremental(),
                    assetDiscoveryService);
        } else {
            // fallback: 顺序执行（测试兼容）
            processed = 0;
            int total = assets.size();
            int visited = 0;
            logTaskProgress(task, 0, total, "adapter candidate files");
            for (SourceAsset asset : assets) {
                if (isCancelled(versionId)) break;
                visited++;
                try {
                    if (plan.isIncremental() && assetDiscoveryService != null
                            && assetDiscoveryService.isIncrementalSkip(asset, projectId, versionId)) {
                        continue;
                    }
                    var adapter = extractionAdapterRegistry.selectAdapter(context, asset);
                    if (adapter.isPresent()) {
                        ExtractionResult result = adapter.get().extract(context, asset);
                        if (result != null && result.getProcessedAssets() > 0) {
                            processed += result.getProcessedAssets();
                        } else {
                            processed++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Adapter failed for discovered asset {}: {}", asset.getRelativePath(), e.getMessage());
                } finally {
                    if (visited % PROGRESS_LOG_INTERVAL == 0 || visited == total) {
                        logTaskProgress(task, visited, total, "adapter candidate files");
                    }
                }
            }
        }

        assetDiscoveryService.persistSnapshots(projectId, versionId, assets);
        Set<String> currentPaths = assets.stream()
                .map(SourceAsset::getRelativePath)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var deletions = assetDiscoveryService.detectDeletions(projectId, versionId, currentPaths);
        if (deletions != null && !deletions.isEmpty()) {
            log.info("Asset discovery detected {} deleted assets for versionId={}", deletions.size(), versionId);
        }
        return processed;
    }

    private ResolvedScanPlan singleRepoPlan(String projectId, String versionId,
                                            String baseDir, String backendDir, String frontendDir) {
        return ResolvedScanPlan.builder()
                .projectId(projectId)
                .versionId(versionId)
                .repos(List.of(ResolvedRepoScope.builder()
                        .baseDir(baseDir)
                        .backendDir(backendDir != null ? backendDir : baseDir)
                        .frontendDir(frontendDir != null ? frontendDir : baseDir)
                        .includePatterns(List.of())
                        .excludePatterns(List.of())
                        .build()))
                .databases(List.of())
                .documents(List.of())
                .scanTypes(Set.of("CODE_SCAN", "DOC_PARSE"))
                .maxFiles(Integer.MAX_VALUE)
                .maxDocs(Integer.MAX_VALUE)
                .maxDbTables(Integer.MAX_VALUE)
                .rawScope(Map.of())
                .build();
    }

    private boolean isAdapterCandidate(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        // 仅包含有适配器能处理的文件类型
        return name.endsWith(".java")
                || name.endsWith(".xml")
                || name.endsWith(".vue")
                || name.endsWith(".jsx")
                || name.endsWith(".tsx")
                || name.endsWith(".ts")
                || name.endsWith(".js")
                || name.endsWith(".md")
                || name.endsWith(".pdf")
                || name.endsWith(".docx")
                || name.endsWith(".txt")
                || name.endsWith(".rst")
                || name.endsWith(".adoc");
    }

    private SourceAsset toSourceAsset(Path root, Path file) {
        String relativePath = root.relativize(file).toString();
        String fileName = file.getFileName().toString();
        String fileType = "";
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            fileType = fileName.substring(dot + 1).toLowerCase();
        }
        long size = 0L;
        try {
            size = Files.size(file);
        } catch (IOException e) { log.debug("IO skipped: {}", e.getMessage()); }
        return SourceAsset.builder()
                .file(file)
                .relativePath(relativePath)
                .fileType(fileType)
                .language(detectLanguage(fileType))
                .framework(detectFramework(relativePath, fileType))
                .fileSize(size)
                .build();
    }

    private String detectLanguage(String fileType) {
        return switch (fileType) {
            case "java" -> "java";
            case "vue", "ts", "js" -> "javascript";
            case "xml" -> "xml";
            case "sql" -> "sql";
            case "md" -> "markdown";
            default -> fileType;
        };
    }

    private String detectFramework(String relativePath, String fileType) {
        String lower = relativePath.toLowerCase();
        if ("java".equals(fileType) && (lower.contains("controller") || lower.contains("service"))) {
            return "spring";
        }
        if ("xml".equals(fileType) && lower.contains("mapper")) {
            return "mybatis";
        }
        if ("vue".equals(fileType)) {
            return "vue";
        }
        return null;
    }

    public int scanDatabaseMetadata(String projectId, String versionId, DataSource dataSource, String schema, String dbType) {
        return databaseMetadataScanService.scan(projectId, versionId, dataSource, schema, dbType);
    }

    public int scanDatabaseMetadata(String projectId, String versionId, DataSource dataSource, DbConnection connection) {
        return databaseMetadataScanService.scan(projectId, versionId, dataSource, connection);
    }

    // ==================== 工具方法 ====================

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // 委托给 ScanTaskRecorder（消除与 AiScanOrchestrator 的重复实现）
    private ScanTask createTask(String projectId, String versionId, String taskType, String taskName) {
        if (scanTaskRecorder != null) {
            return scanTaskRecorder.createTask(projectId, versionId, taskType, taskName);
        }
        // fallback: 测试环境
        ScanTask task = new ScanTask();
        task.setId(UUID.randomUUID().toString());
        task.setProjectId(projectId);
        task.setVersionId(versionId);
        task.setTaskType(taskType);
        task.setTaskName(taskName);
        task.setTaskStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.insert(task);
        log.info("Scan task started: projectId={}, versionId={}, taskType={}, taskName={}, taskId={}",
                projectId, versionId, taskType, taskName, task.getId());
        return task;
    }

    private void completeTask(ScanTask task, String summary, String error) {
        if (scanTaskRecorder != null) {
            scanTaskRecorder.completeTask(task, summary, error);
            return;
        }
        // fallback: 测试环境
        try {
            task.setOutputSummary(objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            task.setOutputSummary("\"" + summary.replace("\"", "\\\"") + "\"");
        }
        task.setErrorMessage(error);
        task.setTaskStatus(error == null ? "SUCCESS" : "FAILED");
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.updateById(task);
        log.info("Scan task completed: projectId={}, versionId={}, taskType={}, taskName={}, taskId={}, status={}",
                task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(),
                task.getId(), task.getTaskStatus());
    }

    private void logTaskProgress(ScanTask task, int processed, int total, String unit) {
        if (scanTaskRecorder != null) {
            scanTaskRecorder.logProgress(task, processed, total, unit, null);
            return;
        }
        // fallback: 测试环境
        if (task == null) return;
        if (total <= 0) {
            log.info("Scan still running: projectId={}, versionId={}, taskType={}, taskName={}, detail=found 0 {}",
                    task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(), unit);
            return;
        }
        if (processed == 0 || processed == 1 || processed == total || processed % PROGRESS_LOG_INTERVAL == 0) {
            log.info("Scan still running: projectId={}, versionId={}, taskType={}, taskName={}, progress={}/{}, unit={}",
                    task.getProjectId(), task.getVersionId(), task.getTaskType(), task.getTaskName(),
                    processed, total, unit);
        }
        try {
            task.setTotalItems(total);
            task.setProcessedItems(processed);
            task.setUpdatedAt(LocalDateTime.now());
            scanTaskRepository.updateById(task);
        } catch (Exception e) {
            log.debug("Failed to update task progress for {}: {}", task.getTaskType(), e.getMessage());
        }
    }

    private void saveFact(String projectId, String versionId, String sourceType, String factType, String factKey, String factName,
            String sourcePath, Integer startLine, Integer endLine, Object data,
            BigDecimal confidence, String status) {
        try {
            Fact fact = new Fact();
            fact.setId(UUID.randomUUID().toString());
            fact.setProjectId(projectId);
            fact.setVersionId(versionId);
            fact.setFactType(factType);
            fact.setFactKey(factKey);
            fact.setFactName(factName);
            fact.setSourceType(sourceType);
            fact.setSourcePath(sourcePath);
            fact.setStartLine(startLine);
            fact.setEndLine(endLine);
            fact.setNormalizedData(objectMapper.writeValueAsString(data));
            fact.setConfidence(confidence != null ? confidence.doubleValue() : 0.0);
            fact.setStatus(status);
            fact.setCreatedAt(LocalDateTime.now());
            fact.setUpdatedAt(LocalDateTime.now());
            factRepository.upsert(fact);
        } catch (Exception e) {
            log.error("Failed to save fact", e);
        }
    }

    @Async
    public void resumeFullScan(String projectId, String versionId, String baseDir) {
        log.info("Resuming full scan: projectId={}, versionId={}, baseDir={}", projectId, versionId, baseDir);

        // 清除之前的取消标记
        clearCancel(versionId);

        // 续扫同样会重建图谱，失效该版本只读缓存与项目概览（与 startFullScan 保持一致）
        if (graphCacheInvalidator != null) {
            graphCacheInvalidator.invalidateVersion(versionId);
            graphCacheInvalidator.invalidateProjectOverview(projectId);
        }
        ScanVersion version = scanVersionRepository.getById(versionId);
        if (version != null) {
            version.setScanStatus("RUNNING");
            scanVersionRepository.updateById(version);
        }
        // 直接调用同步主体（runScanBody 内部已处理异常并置 FAILED），
        // 不再自调用 startFullScan 以免 @Async 代理失效（B-H8）。
        runScanBody(projectId, versionId, baseDir, version);
    }

    /**
     * 调用 LLM 对数据库 Schema 进行语义分析，并丰富图谱。
     */
    private void enrichDbGraphWithLLM(String projectId, String versionId, String schemaText) {
        log.info("Starting LLM DB schema analysis: projectId={}, versionId={}", projectId, versionId);
        try {
            DbSchemaAnalysis analysis = dbSchemaAnalysisAgent.analyze(projectId, schemaText);
            if (analysis != null) {
                graphBuilder.enrichDbGraphWithLLM(projectId, versionId, analysis);
                log.info("LLM DB schema enrichment completed: projectId={}", projectId);
            }
        } catch (Exception e) {
            log.warn("LLM DB schema analysis failed: {}", e.getMessage());
        }
    }
}
