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
import io.github.legacygraph.entity.SourceAssetSnapshot;
import io.github.legacygraph.extractors.adapter.BusinessDomainAdapter;
import io.github.legacygraph.extractors.adapter.ExtractionAdapter;
import io.github.legacygraph.extractors.adapter.ExtractionAdapterRegistry;
import io.github.legacygraph.extractors.adapter.JavaCodeAdapter;
import io.github.legacygraph.extractors.adapter.JavaServiceCallAdapter;
import io.github.legacygraph.extractors.adapter.ExtractionResult;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.extractors.adapter.SourceAsset;
import io.github.legacygraph.integration.graphify.GraphifyImportService;
import io.github.legacygraph.integration.graphify.GraphifyRunner;
import io.github.legacygraph.integration.graphify.GraphifyRunResult;
import io.github.legacygraph.repository.CodeRepoRepository;
import io.github.legacygraph.repository.DbConnectionRepository;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.ScanVersionRepository;
import io.github.legacygraph.service.graph.GraphCacheInvalidator;
import io.github.legacygraph.service.scan.DatabaseMetadataScanService;
import io.github.legacygraph.service.scan.FileChangeDetector;
import io.github.legacygraph.service.scan.ScanArtifactPublisher;
import io.github.legacygraph.service.systemoverview.SystemOverviewDocumentService;
import io.github.legacygraph.service.systemoverview.SystemOverviewIngestService;
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
import io.github.legacygraph.util.IdUtil;

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
    private final GraphifyRunner graphifyRunner;
    private final GraphifyImportService graphifyImportService;
    /** L-06: 扫描检查点仓库（pause/resume 断点恢复） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.repository.ScanCheckpointRepository scanCheckpointRepository;
    /** 证据写入事务执行器：扫描结束时需 flush 剩余证据 */
    private io.github.legacygraph.builder.PgEvidenceTxExecutor pgEvidenceTxExecutor;
    private ScanScopeResolver scanScopeResolver;
    private AssetDiscoveryService assetDiscoveryService;
    private SystemOverviewDocumentService systemOverviewDocumentService;
    private SystemOverviewIngestService systemOverviewIngestService;
    /** 扫描产物发布服务（可选）：扫描完成后把总结性文档发布到 docs/legacygraph 并向量化 */
    private ScanArtifactPublisher scanArtifactPublisher;
    /** 项目约定入库服务（可选）：扫描完成后把技术栈/分层/命名约定向量化为 PROJECT_CONVENTION */
    private io.github.legacygraph.service.scan.ProjectConventionIngestService projectConventionIngestService;
    /** 可复用组件标记服务（可选）：扫描完成后标记被多次继承的基类为 reusable */
    private io.github.legacygraph.service.scan.ReusableComponentMarker reusableComponentMarker;
    /** 成员调用二次扫描解析器（可选）：ADAPTER_SCAN 后对全局图谱解析 Service→Mapper 等 CALLS 边 */
    private io.github.legacygraph.builder.JavaMemberCallResolver javaMemberCallResolver;

    /** 密码加解密服务（可选）：L-01 修复 DB 密码脱敏 bug，用可逆加密替代不可逆脱敏 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.service.security.SecretCipher secretCipher;

    /** 外部工具对照校验服务（可选）：MEMBER_CALL_RESOLVE 后对图谱做外部工具交叉验证 */
    private io.github.legacygraph.verification.ExternalVerificationService externalVerificationService;

    /** 外部验证开关（默认关闭），开启后在 MEMBER_CALL_RESOLVE 与 DATABASE_SCAN 之间执行 EXTERNAL_VERIFY */
    @org.springframework.beans.factory.annotation.Value("${legacygraph.external-verification.enabled:false}")
    private boolean externalVerificationEnabled;

    /** L-02: DB 自动发现 — 文件遍历最大深度 */
    @org.springframework.beans.factory.annotation.Value("${legacygraph.scan.discover.db.max-depth:5}")
    private int dbDiscoverMaxDepth;

    /** L-02: DB 自动发现 — 最多处理的配置文件数 */
    @org.springframework.beans.factory.annotation.Value("${legacygraph.scan.discover.db.max-configs:10}")
    private int dbDiscoverMaxConfigs;

    /** L-02: 子路径检测 — 目录遍历最大深度 */
    @org.springframework.beans.factory.annotation.Value("${legacygraph.scan.discover.path.max-depth:3}")
    private int pathDiscoverMaxDepth;

    /** 图谱/报告缓存失效器（可选）：重新扫描前清空旧图谱只读缓存，避免读到陈旧数据 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.service.graph.GraphCacheInvalidator graphCacheInvalidator;

    /** 扫描收口服务（可选）：图谱发布功能启用时替代旧路径分散调用，统一编排收口流程 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.service.scan.ScanFinalizationService scanFinalizationService;

    /** 图谱发布配置（可选）：控制是否启用 ScanFinalizationService 收口路径 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.config.GraphReleaseConfig graphReleaseConfig;

    /** 增量扫描开关（基于文件 SHA-256 哈希），默认开启 */
    @org.springframework.beans.factory.annotation.Value("${legacygraph.scan.incremental.enabled:true}")
    private boolean incrementalScanEnabled;

    /** 文件变更检测器（可选）：ADAPTER_SCAN 前检测变更文件，实现基于内容哈希的增量扫描 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FileChangeDetector fileChangeDetector;

    /** Blast Radius 分析器（可选）：增量扫描检测到变更文件后，分析影响范围并标记受影响节点 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.legacygraph.service.scan.BlastRadiusAnalyzer blastRadiusAnalyzer;

    /** S1-T2: 扫描锁服务 — 基于 PG advisory lock 防止同项目并发扫描互踩 */
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.legacygraph.service.scan.ScanLockService scanLockService;

    /** 扫描后 AI 编排默认开关（legacy-graph.ai.*），scanScope 未显式指定时生效 */
    @org.springframework.beans.factory.annotation.Value("${legacy-graph.ai.enable-default:true}")
    private boolean aiEnableDefault;
    @org.springframework.beans.factory.annotation.Value("${legacy-graph.ai.auto-generate-test-case-default:false}")
    private boolean aiAutoGenerateTestCaseDefault;
    @org.springframework.beans.factory.annotation.Value("${legacy-graph.ai.min-confidence-default:0.6}")
    private double aiMinConfidenceDefault;

    /** 取消注册表：Controller 写入 signal，runScanBody 检查点读取。ConcurrentHashMap 保证多线程安全 */
    private final ConcurrentHashMap<String, Boolean> cancelledVersions = new ConcurrentHashMap<>();
    // M13修复：添加时间戳 Map，用于清理过期取消标记（24小时后自动清理）
    private final ConcurrentHashMap<String, Long> cancelledVersionsTimestamp = new ConcurrentHashMap<>();

    // L-06: pause 状态机独立于 cancel — pause 可恢复，cancel 不可恢复
    private final ConcurrentHashMap<String, Boolean> pausedVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> pausedVersionsTimestamp = new ConcurrentHashMap<>();

    /** 请求取消指定版本的扫描（由 Controller 调用） */
    public void requestCancel(String versionId) {
        cancelledVersions.put(versionId, true);
        cancelledVersionsTimestamp.put(versionId, System.currentTimeMillis());
        log.info("Cancel requested for versionId={}", versionId);
        // 清理超过24小时的过期标记
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        cancelledVersionsTimestamp.entrySet().removeIf(entry -> {
            if (entry.getValue() < cutoff) {
                cancelledVersions.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /** 检查版本是否已被取消（由 runScanBody 检查点调用） */
    private boolean isCancelled(String versionId) {
        return cancelledVersions.getOrDefault(versionId, false);
    }

    /** 清除取消标记（扫描启动时调用） */
    private void clearCancel(String versionId) {
        cancelledVersions.remove(versionId);
    }

    // L-06: pause/resume 状态机

    /** 请求暂停指定版本的扫描（由 Controller 调用） */
    public void requestPause(String versionId) {
        pausedVersions.put(versionId, true);
        pausedVersionsTimestamp.put(versionId, System.currentTimeMillis());
        log.info("Pause requested for versionId={}", versionId);
    }

    /** 恢复暂停的扫描 */
    public void resumeScan(String versionId) {
        pausedVersions.remove(versionId);
        pausedVersionsTimestamp.remove(versionId);
        log.info("Resume requested for versionId={}", versionId);
    }

    /** 检查是否已暂停 */
    private boolean isPaused(String versionId) {
        return pausedVersions.getOrDefault(versionId, false);
    }

    /** 检查是否已暂停或已取消 */
    private boolean isPausedOrCancelled(String versionId) {
        return isPaused(versionId) || isCancelled(versionId);
    }

    /** 清除暂停标记 */
    private void clearPause(String versionId) {
        pausedVersions.remove(versionId);
        pausedVersionsTimestamp.remove(versionId);
    }

    // L-06: 检查点持久化方法

    /** 保存检查点（每完成一个 file/index 后调用） */
    private void saveCheckpoint(String versionId, String phase, int lastFileIndex,
                                 String lastFilePath, int processedFiles) {
        if (scanCheckpointRepository == null) return;
        try {
            // UPSERT: 按 (versionId, phase) 唯一索引合并
            io.github.legacygraph.entity.ScanCheckpoint existing = scanCheckpointRepository
                    .lambdaQuery()
                    .eq(io.github.legacygraph.entity.ScanCheckpoint::getVersionId, versionId)
                    .eq(io.github.legacygraph.entity.ScanCheckpoint::getPhase, phase)
                    .one();
            if (existing != null) {
                existing.setLastFileIndex(lastFileIndex);
                existing.setLastFilePath(lastFilePath);
                existing.setProcessedFiles(processedFiles);
                existing.setUpdatedAt(java.time.LocalDateTime.now());
                scanCheckpointRepository.updateById(existing);
            } else {
                io.github.legacygraph.entity.ScanCheckpoint cp = new io.github.legacygraph.entity.ScanCheckpoint();
                cp.setVersionId(versionId);
                cp.setPhase(phase);
                cp.setLastFileIndex(lastFileIndex);
                cp.setLastFilePath(lastFilePath);
                cp.setProcessedFiles(processedFiles);
                cp.setUpdatedAt(java.time.LocalDateTime.now());
                scanCheckpointRepository.insert(cp);
            }
        } catch (Exception e) {
            log.debug("saveCheckpoint failed versionId={} phase={}: {}", versionId, phase, e.getMessage());
        }
    }

    /** 获取检查点（resume 时调用，返回上次处理到的位置） */
    public io.github.legacygraph.entity.ScanCheckpoint getCheckpoint(String versionId, String phase) {
        if (scanCheckpointRepository == null) return null;
        try {
            return scanCheckpointRepository
                    .lambdaQuery()
                    .eq(io.github.legacygraph.entity.ScanCheckpoint::getVersionId, versionId)
                    .eq(io.github.legacygraph.entity.ScanCheckpoint::getPhase, phase)
                    .one();
        } catch (Exception e) {
            log.debug("getCheckpoint failed versionId={} phase={}: {}", versionId, phase, e.getMessage());
            return null;
        }
    }

    /** 清除指定版本的所有检查点（扫描完成后调用） */
    public void clearCheckpoints(String versionId) {
        if (scanCheckpointRepository == null) return;
        try {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<io.github.legacygraph.entity.ScanCheckpoint> wrapper =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<io.github.legacygraph.entity.ScanCheckpoint>()
                            .eq(io.github.legacygraph.entity.ScanCheckpoint::getVersionId, versionId);
            scanCheckpointRepository.delete(wrapper);
        } catch (Exception e) {
            log.debug("clearCheckpoints failed versionId={}: {}", versionId, e.getMessage());
        }
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
                         AdapterExecutionService adapterExecutionService,
                         GraphifyRunner graphifyRunner,
                         GraphifyImportService graphifyImportService) {
        this(scanVersionRepository, scanTaskRepository, factRepository, dbConnectionRepository,
                codeRepoRepository, documentRepository, graphBuilder, frontendGraphBuilder,
                neo4jGraphDao, objectMapper, aiScanOrchestrator, dbSchemaAnalysisAgent,
                extractionAdapterRegistry, new DatabaseMetadataScanService(graphBuilder), scanTaskRecorder, 
                adapterExecutionService, graphifyRunner, graphifyImportService);
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
	                         AdapterExecutionService adapterExecutionService,
	                         GraphifyRunner graphifyRunner,
	                         GraphifyImportService graphifyImportService) {
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
        this.graphifyRunner = graphifyRunner;
        this.graphifyImportService = graphifyImportService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScanPlanningServices(ScanScopeResolver scanScopeResolver,
                                 AssetDiscoveryService assetDiscoveryService) {
        this.scanScopeResolver = scanScopeResolver;
        this.assetDiscoveryService = assetDiscoveryService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSystemOverviewDocumentService(SystemOverviewDocumentService systemOverviewDocumentService) {
        this.systemOverviewDocumentService = systemOverviewDocumentService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJavaMemberCallResolver(io.github.legacygraph.builder.JavaMemberCallResolver javaMemberCallResolver) {
        this.javaMemberCallResolver = javaMemberCallResolver;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPgEvidenceTxExecutor(io.github.legacygraph.builder.PgEvidenceTxExecutor pgEvidenceTxExecutor) {
        this.pgEvidenceTxExecutor = pgEvidenceTxExecutor;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSystemOverviewIngestService(SystemOverviewIngestService systemOverviewIngestService) {
        this.systemOverviewIngestService = systemOverviewIngestService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScanArtifactPublisher(ScanArtifactPublisher scanArtifactPublisher) {
        this.scanArtifactPublisher = scanArtifactPublisher;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProjectConventionIngestService(
            io.github.legacygraph.service.scan.ProjectConventionIngestService projectConventionIngestService) {
        this.projectConventionIngestService = projectConventionIngestService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setReusableComponentMarker(
            io.github.legacygraph.service.scan.ReusableComponentMarker reusableComponentMarker) {
        this.reusableComponentMarker = reusableComponentMarker;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExternalVerificationService(io.github.legacygraph.verification.ExternalVerificationService externalVerificationService) {
        this.externalVerificationService = externalVerificationService;
    }

    /**
     * 异步启动完整扫描流程
     */
    @Async
    public void startFullScan(String projectId, String versionId, String baseDir) {
        log.info("Starting full scan: projectId={}, versionId={}, baseDir={}", projectId, versionId, baseDir);

        // S1-T2: 获取项目级扫描锁，防止同项目并发扫描互踩
        if (!scanLockService.tryAcquireScanLock(projectId)) {
            log.warn("Scan rejected — another scan is already running for projectId={}", projectId);
            try {
                ScanVersion rejectedVersion = scanVersionRepository.getById(versionId);
                if (rejectedVersion != null) {
                    rejectedVersion.setScanStatus("REJECTED");
                    rejectedVersion.setErrorMessage("已有扫描任务正在执行，请等待其完成后再发起扫描");
                    rejectedVersion.setFinishedAt(LocalDateTime.now());
                    scanVersionRepository.updateById(rejectedVersion);
                }
            } catch (Exception ex) {
                log.error("Failed to update rejected scan status: versionId={}", versionId, ex);
            }
            return;
        }

        try {
            // M10修复：@Async 方法入口添加异常处理，防止异常被吞没
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
        } catch (Exception e) {
            log.error("M10: Full scan failed: projectId={}, versionId={}", projectId, versionId, e);
            // 更新扫描状态为失败
            try {
                ScanVersion failedVersion = scanVersionRepository.getById(versionId);
                if (failedVersion != null) {
                    failedVersion.setScanStatus("FAILED");
                    failedVersion.setErrorMessage("扫描启动失败: " + e.getMessage());
                    scanVersionRepository.updateById(failedVersion);
                }
            } catch (Exception ex) {
                log.error("M10: Failed to update scan status: versionId={}", versionId, ex);
            }
        } finally {
            // S1-T2: 无论成功/失败/异常，释放扫描锁
            scanLockService.releaseScanLock(projectId);
        }
    }

    /**
     * 扫描主体逻辑（同步）。由 {@link #startFullScan} / {@link #resumeFullScan} 两个 @Async 入口调用，
     * 避免同类内部直接调用 @Async 方法导致 Spring AOP 代理失效（B-H8：原 resumeFullScan 内部
     * 调用 startFullScan 时 @Async 不生效，实际同步执行）。
     */
    private void runScanBody(String projectId, String versionId, String baseDir, ScanVersion version) {
        try {
            // L-07: 扫描开始前显式确保 Neo4j 约束和索引存在（含复合唯一约束）
            try {
                neo4jGraphDao.ensureIndexesAndConstraints();
            } catch (Exception e) {
                log.warn("Failed to ensure Neo4j indexes/constraints at scan start: {}", e.getMessage());
            }
            // 清除上一版本残留的 affected 标记（重扫同一 versionId 时避免旧标记残留）
            if (blastRadiusAnalyzer != null) {
                try {
                    int cleared = blastRadiusAnalyzer.clearAffectedMarkers(projectId, versionId);
                    log.info("Cleared {} stale affected markers from previous scan", cleared);
                } catch (Exception e) {
                    log.warn("Failed to clear affected markers", e);
                }
            }
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

            // 后台代码 AST 提取任务（字段血缘等），AI 阶段开始前需等待其完成，避免 AST+LLM 并发 OOM
            java.util.concurrent.Future<?> backgroundExtractionFuture = null;

            // ⚡ DB 连接状态快照：扫描开始前固定 READY 连接列表，扫描期间不受状态变化影响
            boolean shouldScanDb = scopeScanTypes == null || scopeScanTypes.isEmpty()
                    || scopeScanTypes.contains("DB_SCAN");
            List<DbConnection> dbConnectionSnapshot;
            if (shouldScanDb) {
                if (resolvedPlan != null && scopeDbIds != null && scopeDbIds.isEmpty()) {
                    dbConnectionSnapshot = List.of();
                } else {
                    LambdaQueryWrapper<DbConnection> dbQuery = new LambdaQueryWrapper<DbConnection>()
                            .eq(DbConnection::getProjectId, projectId)
                            .eq(DbConnection::getStatus, "READY");
                    if (scopeDbIds != null && !scopeDbIds.isEmpty()) {
                        dbQuery.in(DbConnection::getId, scopeDbIds);
                    }
                    dbConnectionSnapshot = dbConnectionRepository.selectList(dbQuery);
                }
                log.info("DB connection snapshot: {} READY connections fixed at scan start for projectId={}, versionId={}",
                        dbConnectionSnapshot.size(), projectId, versionId);
            } else {
                dbConnectionSnapshot = List.of();
            }

            // 如果 baseDir 为空，从 scope 中指定的 repoIds 解析本地代码路径
            if (baseDir == null || baseDir.isBlank()) {
                if (resolvedPlan != null && resolvedPlan.getRepos() != null && !resolvedPlan.getRepos().isEmpty()) {
                    ResolvedRepoScope firstRepo = resolvedPlan.getRepos().get(0);
                    baseDir = firstRepo.getBaseDir();
                    log.info("Scan still running: projectId={}, versionId={}, detail=resolved baseDir from scope repos: {}",
                            projectId, versionId, baseDir);
                } else if (resolvedPlan == null) {
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
                } else {
                    log.warn("No code repo found for project {} and baseDir is null — code scanning will be skipped", projectId);
                }
            }

            // 读取 CodeRepo 配置，按 scope 过滤，获取子路径和 include/exclude 规则
            List<CodeRepo> repos = List.of();
            if (resolvedPlan == null || (scopeRepoIds != null && !scopeRepoIds.isEmpty())) {
                LambdaQueryWrapper<CodeRepo> repoQuery = new LambdaQueryWrapper<CodeRepo>()
                        .eq(CodeRepo::getProjectId, projectId);
                if (scopeRepoIds != null && !scopeRepoIds.isEmpty()) {
                    repoQuery.in(CodeRepo::getId, scopeRepoIds);
                }
                repos = codeRepoRepository.selectList(repoQuery);
            }
            String backendDir = baseDir;
            String frontendDir = baseDir;
            if (resolvedPlan != null && resolvedPlan.getRepos() != null && !resolvedPlan.getRepos().isEmpty()) {
                ResolvedRepoScope repo = resolvedPlan.getRepos().get(0);
                backendDir = repo.getBackendDir() != null && !repo.getBackendDir().isBlank() ? repo.getBackendDir() : baseDir;
                frontendDir = repo.getFrontendDir() != null && !repo.getFrontendDir().isBlank() ? repo.getFrontendDir() : baseDir;
            } else if (!repos.isEmpty() && baseDir != null) {
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

            // === 0. 自动发现：数据库连接、子路径、文档（并行化优化） ===
            log.info("Scan still running: projectId={}, versionId={}, phase=DISCOVERY, detail=starting parallel auto-discovery phase",
                    projectId, versionId);
            // S1-T3: 保存 DISCOVERY 阶段检查点（状态机来源）
            saveCheckpoint(versionId, "DISCOVERY", 0, null, 0);

            // 并行执行三个独立的发现阶段：DB_DISCOVERY / PATH_DISCOVERY / DOC_DISCOVERY
            boolean shouldScanDocs = scopeScanTypes == null || scopeScanTypes.isEmpty()
                    || scopeScanTypes.contains("DOC_PARSE");

            final ScanTask dbDiscoveryTask = createTask(projectId, versionId, "DB_DISCOVERY", "数据库连接自动发现");
            final ScanTask pathDiscoveryTask = createTask(projectId, versionId, "PATH_DISCOVERY", "前后端路径自动检测");
            final ScanTask docDiscoveryTask = createTask(projectId, versionId, "DOC_DISCOVERY", "文档自动发现");
            final String discoveryBaseDir = baseDir;

            // 并行执行三阶段
            java.util.concurrent.Future<Integer> dbDiscoveryFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                if (isCancelled(versionId)) return 0;
                int count = discoverDbConnections(projectId, discoveryBaseDir, dbDiscoveryTask);
                completeTask(dbDiscoveryTask, "Discovered " + count + " database connections", null);
                log.info("Auto-discovered {} database connections", count);
                return count;
            });

            java.util.concurrent.Future<Integer> pathDiscoveryFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                if (isCancelled(versionId)) return 0;
                int count = discoverSubPaths(projectId, discoveryBaseDir);
                completeTask(pathDiscoveryTask, "Updated " + count + " repo sub-paths", null);
                log.info("Auto-detected sub-paths for {} repos", count);
                return count;
            });

            java.util.concurrent.Future<Integer> docDiscoveryFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                if (isCancelled(versionId)) return 0;
                if (shouldScanDocs) {
                    int docCount = attachPlanDocuments(projectId, versionId, resolvedPlan);
                    if (discoveryBaseDir != null && !discoveryBaseDir.isBlank()) {
                        int maxDocs = resolvedPlan != null ? resolvedPlan.getMaxDocs() : 50;
                        docCount += discoverDocuments(projectId, versionId, discoveryBaseDir, docDiscoveryTask, maxDocs);
                    }
                    completeTask(docDiscoveryTask, "Discovered " + docCount + " documents", null);
                    log.info("Auto-discovered {} documents", docCount);
                    return docCount;
                } else {
                    log.info("Scan still running: projectId={}, versionId={}, phase=DOC_DISCOVERY, detail=skipped (DOC_PARSE not in scanTypes)",
                            projectId, versionId);
                    completeTask(docDiscoveryTask, "未选择文档扫描类型，已跳过", null, "SKIPPED");
                    return 0;
                }
            });

            // 等待所有发现阶段完成（最多 60 秒超时）
            try {
                dbDiscoveryFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
                pathDiscoveryFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
                docDiscoveryFuture.get(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                log.warn("Parallel discovery phase failed: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (java.util.concurrent.TimeoutException e) {
                log.warn("Parallel discovery phase timed out after 60s");
            }

            if (isCancelled(versionId)) return;

            // L8修复：移除重复的 isCancelled 检查（上一行已检查）
            boolean shouldScanCode = scopeScanTypes == null || scopeScanTypes.isEmpty()
                    || scopeScanTypes.contains("CODE_SCAN");

            // === 增量扫描：基于文件 SHA-256 哈希检测变更文件 ===
            // 首次扫描（无历史快照）→ 全量；有快照 → 仅对变更文件执行 ExtractionAdapter
            Set<String> incrementalChangedPaths = null;
            // BlastRadius 分析产生的受影响节点 ID，传递到 AI 编排阶段用于缩小处理范围
            Set<String> incrementalAffectedNodeIds = new LinkedHashSet<>();
            Map<String, String> incrementalSnapshotContents = null;
            if (incrementalScanEnabled && fileChangeDetector != null
                    && baseDir != null && !baseDir.isBlank() && shouldScanCode) {
                try {
                    Map<String, String> pathToContent = collectAdapterCandidateContents(baseDir);
                    if (!pathToContent.isEmpty()) {
                        Set<String> priorSnapshots = fileChangeDetector.getUnchangedFiles(projectId);
                        if (priorSnapshots.isEmpty()) {
                            // 首次扫描：无历史快照，全量扫描；扫描完成后统一记录快照
                            log.info("Incremental scan: first scan (no prior snapshots), full scan: {} candidate files (projectId={}, versionId={})",
                                    pathToContent.size(), projectId, versionId);
                        } else {
                            // 增量扫描：仅处理内容变更的文件
                            List<String> changed = fileChangeDetector.detectChangedFiles(projectId, pathToContent);
                            incrementalChangedPaths = new HashSet<>(changed);
                            log.info("Incremental scan: {} of {} candidate files changed (projectId={}, versionId={})",
                                    changed.size(), pathToContent.size(), projectId, versionId);
                            // Blast Radius 分析：基于上次扫描的图谱，反向遍历找受变更影响的节点并标记
                            if (blastRadiusAnalyzer != null && !changed.isEmpty()) {
                                try {
                                    var blastResult = blastRadiusAnalyzer.analyzeBlastRadius(projectId, changed);
                                    blastRadiusAnalyzer.markAffectedNodes(projectId, blastResult);
                                    incrementalAffectedNodeIds.addAll(blastResult.affectedNodeIds());
                                    log.info("Blast radius analysis: {} affected nodes marked (projectId={}, versionId={})",
                                            blastResult.affectedNodeIds().size(), projectId, versionId);
                                } catch (Exception e) {
                                    log.warn("Blast radius analysis failed, continuing scan: {}", e.getMessage());
                                }
                            }
                        }
                        // 缓存待记录内容，扫描完成后统一 upsert 快照
                        incrementalSnapshotContents = pathToContent;
                    }
                } catch (Exception e) {
                    log.warn("Incremental change detection failed, fallback to full scan: {}", e.getMessage());
                }
            }

            // === 增量扫描：继承上一版本的完整图谱到当前版本 ===
            // 确保新版本拥有全量节点和边，增量扫描只 MERGE 覆盖变更文件涉及的节点
            if (incrementalChangedPaths != null && !incrementalChangedPaths.isEmpty()) {
                try {
                    ScanVersion lastSuccess = findLastSuccessVersion(projectId, versionId);
                    if (lastSuccess != null) {
                        log.info("Incremental scan: cloning graph from version {} to {} (projectId={})",
                                lastSuccess.getId(), versionId, projectId);
                        int cloned = neo4jGraphDao.cloneVersionGraph(
                                projectId, lastSuccess.getId(), versionId);
                        log.info("Incremental scan: cloned {} nodes+edges from version {} to {} (projectId={})",
                                cloned, lastSuccess.getId(), versionId, projectId);
                    } else {
                        log.info("Incremental scan: no prior SUCCESS version found, skipping graph clone (projectId={})", projectId);
                    }
                } catch (Exception e) {
                    log.warn("Incremental scan: graph clone failed, continuing with partial scan: {}", e.getMessage());
                }
            }

            int adapterCount = 0;
            if (shouldScanCode || shouldScanDocs) {
                log.info("Scan still running: projectId={}, versionId={}, phase=ADAPTER_SCAN, detail=starting adapter registry scan",
                        projectId, versionId);
                // S1-T3: 保存 ADAPTER_SCAN 阶段检查点
                saveCheckpoint(versionId, "ADAPTER_SCAN", 0, null, 0);
                ScanTask adapterTask = createTask(projectId, versionId, "ADAPTER_SCAN", "适配器抽取扫描");
                adapterCount = scanAssetsWithAdapters(projectId, versionId, baseDir, backendDir, frontendDir, adapterTask, resolvedPlan, incrementalChangedPaths);
                completeTask(adapterTask, "Adapter processed " + adapterCount + " assets", null);
                log.info("Adapter registry scan processed {} assets", adapterCount);

                // 目录级扫描：BusinessDomain 从包结构推断
                try {
                    BusinessDomainAdapter domainAdapter = null;
                    for (ExtractionAdapter adapter : extractionAdapterRegistry.getAllAdapters()) {
                        if (adapter instanceof BusinessDomainAdapter bda) {
                            domainAdapter = bda;
                            break;
                        }
                    }
                    if (domainAdapter != null && backendDir != null) {
                        File backendRoot = new File(backendDir);
                        if (backendRoot.exists() && backendRoot.isDirectory()) {
                            ExtractionResult domainResult = domainAdapter.extractFromDirectory(
                                    backendRoot, projectId, versionId);
                            int domainCount = domainResult.getNodeCount();
                            if (domainCount > 0) {
                                log.info("Scanned {} business domains from package structure", domainCount);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("BusinessDomain directory scan failed: {}", e.getMessage());
                }
                if (adapterCount > 0) {
                    log.info("Scan still running: projectId={}, versionId={}, phase=ADAPTER_SCAN, detail=completed by Adapter Registry ({} assets)",
                            projectId, versionId, adapterCount);
                } else {
                    log.warn("Scan still running: projectId={}, versionId={}, phase=ADAPTER_SCAN, detail=no supported source assets found by Adapter Registry",
                            projectId, versionId);
                }
            } else {
                log.info("Scan still running: projectId={}, versionId={}, phase=ADAPTER_SCAN, detail=skipped (CODE_SCAN/DOC_PARSE not in scanTypes)",
                        projectId, versionId);
                ScanTask skippedAdapterTask = createTask(projectId, versionId, "ADAPTER_SCAN", "适配器抽取扫描");
                completeTask(skippedAdapterTask, "未选择代码/文档扫描类型，已跳过", null, "SKIPPED");
            }

            // === 增量扫描：扫描完成后更新所有候选文件的哈希快照（供下次增量比对） ===
            if (incrementalSnapshotContents != null && fileChangeDetector != null) {
                try {
                    fileChangeDetector.recordSnapshots(projectId, incrementalSnapshotContents);
                } catch (Exception e) {
                    log.warn("Failed to record file snapshots after scan: {}", e.getMessage());
                }
            }

            // 3b. 成员调用二次扫描：所有 Java 文件抽取完成后，对全局图谱解析 Service→Mapper 等 CALLS 边。
            // 逐文件 buildServiceCallGraph 受简单名/FQN 不匹配 + 跨文件顺序限制漏边，此处用 god-node guard 补齐。
            if (isCancelled(versionId)) return;
            if (shouldScanCode && javaMemberCallResolver != null) {
                ScanTask resolveTask = createTask(projectId, versionId, "MEMBER_CALL_RESOLVE", "成员调用二次解析");
                try {
                    int resolved = javaMemberCallResolver.resolveMemberCalls(projectId, versionId);
                    completeTask(resolveTask, "resolved " + resolved + " member-call edges", null);
                    log.info("Scan still running: projectId={}, versionId={}, phase=MEMBER_CALL_RESOLVE, detail=resolved {} call edges",
                            projectId, versionId, resolved);
                } catch (Exception e) {
                    log.warn("Member-call resolution failed (non-blocking): versionId={}, err={}", versionId, e.getMessage());
                    completeTask(resolveTask, "failed: " + e.getMessage(), e.getMessage());
                }
            }

            // 3c. Mapper-SQL 后置连接：补偿并发时序导致的 Method→SqlStatement 边遗漏
            if (isCancelled(versionId)) return;
            if (shouldScanCode) {
                ScanTask linkTask = createTask(projectId, versionId, "MAPPER_SQL_LINK", "Mapper-SQL后置连接");
                try {
                    int linked = graphBuilder.linkMapperMethodsToSqlStatements(projectId, versionId);
                    completeTask(linkTask, "linked " + linked + " method-sql edges", null);
                    log.info("Scan still running: projectId={}, versionId={}, phase=MAPPER_SQL_LINK, detail=linked {} method-sql edges",
                            projectId, versionId, linked);
                } catch (Exception e) {
                    log.warn("Mapper-SQL link failed (non-blocking): versionId={}, err={}", versionId, e.getMessage());
                    completeTask(linkTask, "failed: " + e.getMessage(), e.getMessage());
                }
            }

            // === EXTERNAL_VERIFY 阶段：外部工具对照校验 ===
            if (externalVerificationEnabled && externalVerificationService != null) {
                if (isCancelled(versionId)) return;
                ScanTask externalVerifyTask = createTask(projectId, versionId, "EXTERNAL_VERIFY", "外部工具对照校验");
                try {
                    log.info("Scan still running: projectId={}, versionId={}, phase=EXTERNAL_VERIFY, detail=starting external verification",
                            projectId, versionId);
                    ScanContext verifyContext = ScanContext.builder()
                            .projectId(projectId)
                            .versionId(versionId)
                            .baseDir(baseDir)
                            .backendDir(backendDir)
                            .frontendDir(frontendDir)
                            .config(new ConcurrentHashMap<>())
                            .build();
                    externalVerificationService.executeVerification(projectId, versionId, verifyContext);
                    completeTask(externalVerifyTask, "External verification completed", null);
                    log.info("Scan still running: projectId={}, versionId={}, phase=EXTERNAL_VERIFY, detail=completed",
                            projectId, versionId);
                } catch (Exception e) {
                    log.error("EXTERNAL_VERIFY 阶段失败，但不阻塞后续步骤: {}", e.getMessage(), e);
                    completeTask(externalVerifyTask, "failed: " + e.getMessage(), e.getMessage());
                }
            }

            // 4. 扫描所有已配置数据库的元数据（按 scope 过滤）
            // 仅在未指定 scanTypes 或包含 DB_SCAN 时执行
            if (isCancelled(versionId)) return;
            if (shouldScanDb) {
                List<DbConnection> dbConnections = dbConnectionSnapshot;

                if (!dbConnections.isEmpty()) {
                    log.info("Scan still running: projectId={}, versionId={}, phase=DATABASE_SCAN, detail=scanning {} databases in parallel",
                            projectId, versionId, dbConnections.size());
                    ScanTask dbTask = createTask(projectId, versionId, "DATABASE_SCAN", "数据库元数据扫描");
                    
                    // 并行扫描多个数据库连接
                    java.util.concurrent.atomic.AtomicInteger totalTables = new java.util.concurrent.atomic.AtomicInteger(0);
                    java.util.concurrent.atomic.AtomicInteger processedConnections = new java.util.concurrent.atomic.AtomicInteger(0);
                    logTaskProgress(dbTask, 0, dbConnections.size(), "database connections");
                    
                    java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = dbConnections.stream()
                            .map(conn -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                                if (isCancelled(versionId)) return;
                                // === 自动纠偏：dbType 与 port 不匹配时修正 ===
                                autoCorrectDbTypeAndPort(conn);
                                try {
                                    DataSource dataSource = createDataSource(conn);
                                    int maxDbTables = resolvedPlan != null ? resolvedPlan.getMaxDbTables() : 0;
                                    int tables = scanDatabaseMetadata(projectId, versionId, dataSource, conn, maxDbTables);
                                    totalTables.addAndGet(tables);
                                } catch (Exception e) {
                                    log.warn("Failed to scan database connection id={} host={}:{}/{} dbType={}: {}",
                                            conn.getId(), conn.getHost(), conn.getPort(),
                                            conn.getDatabaseName(), conn.getDbType(), e.getMessage());
                                } finally {
                                    int processed = processedConnections.incrementAndGet();
                                    logTaskProgress(dbTask, processed, dbConnections.size(), "database connections");
                                }
                            }))
                            .toList();
                    
                    // 等待所有数据库扫描完成
                    for (java.util.concurrent.Future<Void> future : futures) {
                        try {
                            future.get();
                        } catch (Exception e) {
                            log.warn("Database scan future failed: {}", e.getMessage());
                        }
                    }
                    
                    completeTask(dbTask, "Scanned " + dbConnections.size() + " databases, " + totalTables.get() + " tables", null);
                    log.info("Completed database metadata scan, {} databases, {} tables", dbConnections.size(), totalTables.get());

                    // SQL 字段 ↔ DB 元数据字段交叉对比
                    if (graphBuilder != null) {
                        try {
                            int[] stats = graphBuilder.crossValidateSqlVsDbColumns(projectId, versionId);
                            log.info("Column cross-validation: sqlOnly={}, dbOnly={}, matched={}",
                                    stats[0], stats[1], stats[2]);
                        } catch (Exception e) {
                            log.warn("Column cross-validation failed (non-blocking): {}", e.getMessage());
                        }
                        // 从代码提取字段级数据血缘 + 节点增强（后台异步，不阻塞主扫描管线）
                        // 保存 Future，在 AI 阶段开始前等待完成，避免 AST 解析与 LLM 抽取并发导致堆峰值 OOM
                        if (baseDir != null && !baseDir.isBlank()) {
                            final String asyncPid = projectId;
                            final String asyncVid = versionId;
                            final java.nio.file.Path repoPath = java.nio.file.Path.of(baseDir);
                            backgroundExtractionFuture = CompletableFuture.runAsync(() -> {
                                log.info("Background extraction started: projectId={}", asyncPid);
                                int total = 0;
                                try { total += graphBuilder.extractEntityColumns(asyncPid, asyncVid, repoPath); } catch (Exception e) { log.warn("Entity columns: {}", e.getMessage()); }
                                try { total += graphBuilder.extractResultMapColumns(asyncPid, asyncVid, repoPath); } catch (Exception e) { log.warn("ResultMap: {}", e.getMessage()); }
                                try { total += graphBuilder.extractJdbcColumns(asyncPid, asyncVid, repoPath); } catch (Exception e) { log.warn("JDBC: {}", e.getMessage()); }
                                try { total += graphBuilder.extractHtmlAjaxButtons(asyncPid, asyncVid, repoPath); } catch (Exception e) { log.warn("AJAX: {}", e.getMessage()); }
                                try { total += graphBuilder.extractValueConfigItems(asyncPid, asyncVid, repoPath); } catch (Exception e) { log.warn("Config: {}", e.getMessage()); }
                                try { total += graphBuilder.extractHttpClientSystems(asyncPid, asyncVid, repoPath); } catch (Exception e) { log.warn("HttpClient: {}", e.getMessage()); }
                                try { total += graphBuilder.extractHtmlMenus(asyncPid, asyncVid, repoPath); } catch (Exception e) { log.warn("Menu: {}", e.getMessage()); }
                                log.info("Background extraction done: {} items (projectId={})", total, asyncPid);
                            });
                        }
                    }

                    // LLM 语义增强：异步执行，不阻塞扫描主流程
                    if (totalTables.get() > 0) {
                        // 标记 AI 增强为 PENDING
                        updateScanVersionAiStatus(versionId, "PENDING");
                        CompletableFuture.runAsync(() -> {
                            try {
                                updateScanVersionAiStatus(versionId, "RUNNING");
                                String schemaText = graphBuilder.buildDbSchemaSummary(projectId, versionId);
                                if (schemaText != null && !schemaText.isBlank()) {
                                    enrichDbGraphWithLLM(projectId, versionId, schemaText);
                                    updateScanVersionAiStatus(versionId, "COMPLETED");
                                    log.info("DB schema LLM enrichment completed: versionId={}", versionId);
                                } else {
                                    updateScanVersionAiStatus(versionId, "SKIPPED");
                                    log.info("DB schema LLM enrichment skipped (empty schema): versionId={}", versionId);
                                }
                            } catch (Exception e) {
                                updateScanVersionAiStatus(versionId, "FAILED");
                                log.warn("DB schema LLM enrichment failed (non-blocking): versionId={}, error={}", versionId, e.getMessage());
                            }
                        });
                        log.info("DB schema LLM enrichment dispatched asynchronously: versionId={}", versionId);
                    } else {
                        updateScanVersionAiStatus(versionId, "SKIPPED");
                        log.info("DB schema LLM enrichment skipped (no tables): versionId={}", versionId);
                    }
                } else {
                    log.info("Scan still running: projectId={}, versionId={}, phase=DATABASE_SCAN, detail=no READY database connections found for project",
                            projectId, versionId);
                    ScanTask skippedDbTask = createTask(projectId, versionId, "DATABASE_SCAN", "数据库元数据扫描");
                    completeTask(skippedDbTask, "项目未配置可用的数据库连接，已跳过", null, "SKIPPED");
                }
            } else {
                log.info("Scan still running: projectId={}, versionId={}, phase=DATABASE_SCAN, detail=skipped (DB_SCAN not in scanTypes)",
                        projectId, versionId);
                ScanTask skippedDbTask = createTask(projectId, versionId, "DATABASE_SCAN", "数据库元数据扫描");
                completeTask(skippedDbTask, "未选择数据库扫描类型，已跳过", null, "SKIPPED");
            }

            // 4b. Graphify 外部工具集成（可选）
            if (isCancelled(versionId)) return;
            boolean shouldRunGraphify = scopeScanTypes != null && scopeScanTypes.contains("GRAPHIFY_ANALYZE");
            if (shouldRunGraphify && baseDir != null && !baseDir.isBlank()) {
                ScanTask graphifyTask = null;
                try {
                    log.info("Scan still running: projectId={}, versionId={}, phase=GRAPHIFY_ANALYZE, detail=starting Graphify analysis",
                            projectId, versionId);
                    graphifyTask = createTask(projectId, versionId, "GRAPHIFY_ANALYZE", "Graphify 代码分析");
                    
                    if (graphifyRunner.isAvailable()) {
                        GraphifyRunResult runResult = graphifyRunner.run(Path.of(baseDir));
                        if (runResult.isSuccess() && runResult.getGraphJsonPath() != null) {
                            GraphifyImportService.ImportResult importResult = 
                                    graphifyImportService.importGraph(projectId, versionId, runResult.getGraphJsonPath());
                            completeTask(graphifyTask, 
                                    String.format("Graphify analyzed %d nodes, %d edges", 
                                            importResult.getProcessedNodes(), importResult.getProcessedEdges()),
                                    null);
                            log.info("Graphify analysis completed: {} nodes, {} edges", 
                                    importResult.getProcessedNodes(), importResult.getProcessedEdges());
                        } else {
                            String errorMsg = runResult.getStderr() != null && !runResult.getStderr().isBlank()
                                    ? runResult.getStderr() : "Exit code: " + runResult.getExitCode();
                            completeTask(graphifyTask, "Graphify analysis failed", errorMsg);
                            log.warn("Graphify analysis failed: {}", errorMsg);
                        }
                    } else {
                        completeTask(graphifyTask, "Graphify not available", null, "SKIPPED");
                        log.info("Graphify tool not available, skipping");
                    }
                } catch (Exception e) {
                    log.warn("Graphify analysis failed (non-blocking): {}", e.getMessage());
                    if (graphifyTask != null) {
                        completeTask(graphifyTask, "Graphify analysis failed", e.getMessage());
                    }
                }
            } else if (shouldRunGraphify) {
                ScanTask skippedGraphifyTask = createTask(projectId, versionId, "GRAPHIFY_ANALYZE", "Graphify 代码分析");
                completeTask(skippedGraphifyTask, "项目未配置源码目录，已跳过", null, "SKIPPED");
            }

            // 5. 图谱已由各 Builder 在扫描过程中直写 Neo4j，无需额外同步步骤
            // 此子任务仅作为扫描阶段标记，不执行实质操作
            log.info("Scan still running: projectId={}, versionId={}, phase=GRAPH_BUILD, detail=graph built in Neo4j",
                    projectId, versionId);
            // S1-T3: 保存 GRAPH_BUILD 阶段检查点
            saveCheckpoint(versionId, "GRAPH_BUILD", 0, null, 0);
            ScanTask buildTask = createTask(projectId, versionId, "GRAPH_BUILD", "图谱构建");
            completeTask(buildTask, "Graph built in Neo4j", null);

            // 等待后台代码 AST 提取完成（最多 5 分钟），避免与 AI LLM 抽取并发导致堆峰值 OOM
            if (backgroundExtractionFuture != null) {
                try {
                    backgroundExtractionFuture.get(5, java.util.concurrent.TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("Background extraction not completed before AI phase (non-blocking): {}",
                            e.getMessage());
                }
            }

            // 6. 扫描后 AI 编排
            // DOC_PARSE 未显式指定 AI 开关时默认开启 AI；显式 enableAi=false 必须被尊重。
            if (isCancelled(versionId)) return;
            boolean aiEnabled = false;
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
                aiEnabled = aiConfig.isEnableAi();
                if (aiEnabled) {
                    // 传递增量上下文到 AI 编排阶段：首次扫描 incrementalChangedPaths 为 null，转为空集合
                    Set<String> changedPathsForAi = incrementalChangedPaths != null
                            ? incrementalChangedPaths : Collections.emptySet();
                    aiScanOrchestrator.enqueue(projectId, versionId, aiConfig,
                            changedPathsForAi, incrementalAffectedNodeIds);
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
                    // 如果有 AI 任务（enqueue 已执行），版本状态由 AiScanJobWorker 在 AI 完成后更新
                    // 这样总耗时才能包含 AI 编排时间
                    if (aiEnabled) {
                        version.setScanStatus("RUNNING");
                        log.info("Scan base phase done, version stays RUNNING until AI completes: versionId={}", versionId);
                    } else {
                        version.setScanStatus("SUCCESS");
                        version.setFinishedAt(LocalDateTime.now());
                    }
                }
                // 回写节点/边/事实/子任务统计快照，供列表接口零 IO 读取
                applyStatsSnapshot(version, projectId, versionId);
                scanVersionRepository.updateById(version);
            }
            if (!aiEnabled && !isCancelled(versionId)) {
                if (isScanFinalizationEnabled()) {
                    runScanFinalization(projectId, versionId);
                } else {
                    generateSystemOverviewDocument(projectId, versionId);
                    publishScanArtifacts(projectId, versionId);
                }
            }

            // 扫描完成汇总：flush 剩余证据，然后输出 Neo4j 节点数
            if (pgEvidenceTxExecutor != null) {
                try {
                    pgEvidenceTxExecutor.flush();
                    log.info("Flushed remaining evidence after scan: versionId={}", versionId);
                } catch (Exception ex) {
                    log.warn("Failed to flush evidence after scan: {}", ex.getMessage());
                }
            }
            long totalNodes = 0;
            try {
                totalNodes = neo4jGraphDao.countNodes(projectId, versionId, null);
            } catch (Exception ex) {
                log.warn("Failed to count Neo4j nodes for summary: {}", ex.getMessage());
            }
            log.info("Full scan completed successfully: versionId={}, neo4jNodes={}", versionId, totalNodes);
            // S1-T3: 扫描成功后清理所有检查点（状态机终态）
            clearCheckpoints(versionId);

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
            // S1-T3: 失败时保留检查点（供 resume 续扫），不清理
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
                    String status = t.getTaskStatus();
                    if ("SUCCESS".equals(status) || "WARNING".equals(status) || "SKIPPED".equals(status)) {
                        taskSuccess++;
                    } else if ("FAILED".equals(status)) {
                        taskFailed++;
                    }
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

            // L-14: 聚合项目维度累积统计（取该项目最新版本的最大节点/边/事实数作为累积值）
            try {
                List<ScanVersion> projectVersions = scanVersionRepository.lambdaQuery()
                        .eq(ScanVersion::getProjectId, projectId)
                        .orderByDesc(ScanVersion::getCreatedAt)
                        .list();
                long cumNodes = 0, cumEdges = 0, cumFacts = 0;
                for (ScanVersion v : projectVersions) {
                    if (v.getNodeCount() != null && v.getNodeCount() > cumNodes) cumNodes = v.getNodeCount();
                    if (v.getEdgeCount() != null && v.getEdgeCount() > cumEdges) cumEdges = v.getEdgeCount();
                    if (v.getFactCount() != null && v.getFactCount() > cumFacts) cumFacts = v.getFactCount();
                }
                version.setCumulativeNodeCount(cumNodes);
                version.setCumulativeEdgeCount(cumEdges);
                version.setCumulativeFactCount(cumFacts);
                version.setCumulativeUpdatedAt(LocalDateTime.now());
            } catch (Exception ex) {
                log.warn("Snapshot: cumulative stats failed projectId={}: {}", projectId, ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("applyStatsSnapshot unexpected failure versionId={}: {}", versionId, e.getMessage());
        }
    }

    void generateSystemOverviewDocument(String projectId, String versionId) {
        // 项目约定向量化 + 可复用组件标记（与系统总览文档并列，失败不阻塞）
        runPostScanConventionIngest(projectId, versionId);

        if (systemOverviewDocumentService == null) {
            log.debug("SystemOverviewDocumentService not available, skip markdown generation: versionId={}", versionId);
            return;
        }
        // 先把当前项目图谱回溯成四层 Claim 写入 lg_knowledge_claim，否则 generateAfterScan 投影不到
        // 任何映射，报告只剩表头模板。ingest 失败不阻塞报告生成（降级到已有 Claim）。
        if (systemOverviewIngestService != null) {
            try {
                systemOverviewIngestService.ingestFromProjectGraph(projectId, versionId);
            } catch (Exception e) {
                log.warn("Failed to ingest system overview claims from graph before report generation: "
                        + "versionId={}, error={}", versionId, e.getMessage());
            }
        }
        try {
            systemOverviewDocumentService.generateAfterScan(projectId, versionId);
        } catch (Exception e) {
            log.warn("Failed to generate system overview markdown after scan: versionId={}, error={}",
                    versionId, e.getMessage());
        }
    }

    /**
     * 发布扫描产物（边补全 / 社区检测 / 质量报告 / 总结文档发布到 docs/legacygraph 并向量化）。
     *
     * <p>在 {@link #generateSystemOverviewDocument} 之后调用：前者负责约定入库、Claim 回溯与
     * Report 表登记，本方法负责图谱定稿后的质量评估与产物发布。失败只 warn，不阻塞扫描主流程。</p>
     */
    private void publishScanArtifacts(String projectId, String versionId) {
        if (scanArtifactPublisher == null) {
            log.debug("ScanArtifactPublisher not available, skip publishing scan artifacts: versionId={}", versionId);
            return;
        }
        try {
            scanArtifactPublisher.publish(projectId, versionId);
        } catch (Exception e) {
            log.warn("ScanArtifactPublisher failed (non-blocking): versionId={}, error={}", versionId, e.getMessage());
        }
    }

    /**
     * 判断是否启用 ScanFinalizationService 收口路径。
     * <p>需同时满足：{@code legacygraph.graph-release.enabled=true} 且 {@link ScanFinalizationService} 已注入。</p>
     */
    private boolean isScanFinalizationEnabled() {
        return graphReleaseConfig != null && graphReleaseConfig.isEnabled()
                && scanFinalizationService != null;
    }

    /**
     * 调用 ScanFinalizationService 统一收口扫描流程。
     * <p>替代旧路径的 {@code generateSystemOverviewDocument} + {@code publishScanArtifacts}，
     * 由 {@link ScanFinalizationService#finalize} 统一编排约定提取、可复用标记、质量评估、
     * 边补全、社区检测、产物发布、质量门禁、GraphRelease 发布与缓存失效。失败只 warn，不阻塞扫描主流程。</p>
     */
    private void runScanFinalization(String projectId, String versionId) {
        try {
            scanFinalizationService.finalize(projectId, versionId);
        } catch (Exception e) {
            log.warn("ScanFinalizationService failed (non-blocking): versionId={}, error={}",
                    versionId, e.getMessage(), e);
        }
    }

    /**
     * 扫描后置增强：项目约定向量化（PROJECT_CONVENTION）+ 可复用组件标记（reusable）。
     *
     * <p>由 {@link #generateSystemOverviewDocument} 在扫描完成时调用（覆盖 AI 未启用与 AI 启用两条路径），
     * 失败只 warn，不阻塞扫描主流程。两个子任务独立 try/catch，互不影响。
     *
     * @param projectId 项目ID
     * @param versionId 扫描版本ID
     */
    public void runPostScanConventionIngest(String projectId, String versionId) {
        // 1. 项目约定向量化（技术栈 + 分层规范 + 命名约定）
        if (projectConventionIngestService != null) {
            try {
                projectConventionIngestService.ingest(projectId, versionId);
            } catch (Exception e) {
                log.warn("Post-scan convention ingest failed: versionId={}, error={}", versionId, e.getMessage());
            }
        }
        // 2. 可复用组件标记（统计 EXTENDS 入度，标记 reusable=true）
        if (reusableComponentMarker != null) {
            try {
                int marked = reusableComponentMarker.mark(projectId, versionId);
                log.info("Post-scan reusable component marking: versionId={}, marked={}", versionId, marked);
            } catch (Exception e) {
                log.warn("Post-scan reusable component marking failed: versionId={}, error={}", versionId, e.getMessage());
            }
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

            // 查找配置文件（M8 修复：try-with-resources 关闭 Stream）
            log.info("Scan still running: projectId={}, phase=DB_DISCOVERY, detail=walking for config files", projectId);
            List<Path> allConfigFiles;
            try (java.util.stream.Stream<Path> stream = Files.walk(basePath, dbDiscoverMaxDepth)) {
                allConfigFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return (name.startsWith("application") || name.startsWith("application-"))
                                    && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
                        })
                        .sorted((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                        .collect(Collectors.toList());
            }

            // 记录实际发现的总数（截断前）
            int discoveredCount = allConfigFiles.size();
            List<Path> configFiles = allConfigFiles.stream().limit(dbDiscoverMaxConfigs).collect(Collectors.toList());

            if (discoveredCount > configFiles.size()) {
                log.info("DB discovery: found {} config files, processing {} (limited to {})",
                        discoveredCount, configFiles.size(), dbDiscoverMaxConfigs);
            }

            Set<String> discoveredUrls = new HashSet<>(); // 本次扫描去重
            int total = configFiles.size();
            // 使用实际发现的总数作为进度统计的 totalItems
            int progressTotal = discoveredCount > 0 ? discoveredCount : total;
            scanTaskRecorder.logProgress(task, 0, progressTotal, "config files", null);

            int idx = 0;
            for (Path configFile : configFiles) {
                try {
                    idx++;
                    scanTaskRecorder.logProgress(task, idx, progressTotal, "config files", configFile.getFileName().toString());
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
                                // 手动配置的连接不允许被自动发现覆盖
                                if ("MANUAL".equals(existing.getSource())) {
                                    log.info("Skipping auto-update for manually configured DB connection: {} (id={})", 
                                            existing.getConnectionName(), existing.getId());
                                    continue;
                                }
                                // 更新已有连接（仅自动发现的连接）
                                existing.setDbType(dbType);
                                existing.setSchemaName(schema);
                                existing.setUsername(dbConfig.getOrDefault("username", existing.getUsername()));
                                // L-01 修复：密码可逆加密存储到 passwordCipher，password 列仅存脱敏值用于回显
                                String rawPwd = dbConfig.getOrDefault("password", "");
                                if (!rawPwd.isEmpty()) {
                                    encryptPassword(existing, rawPwd);
                                }
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
                                // L-01 修复：密码可逆加密存储到 passwordCipher，password 列仅存脱敏值用于回显
                                String rawPwd = dbConfig.getOrDefault("password", "");
                                encryptPassword(conn, rawPwd);
                                conn.setStatus("READY");
                                conn.setSource("AUTO_DISCOVERED");
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
     * L-01: 加密密码并设置到 DbConnection。
     * 将明文密码 AES-GCM 加密后存入 passwordCipher，password 列仅存脱敏值用于前端回显。
     * 当 SecretCipher 不可用时降级为旧的有损脱敏（保持向后兼容）。
     */
    private void encryptPassword(DbConnection conn, String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            conn.setPassword("");
            return;
        }
        if (secretCipher != null) {
            conn.setPasswordCipher(secretCipher.encrypt(rawPassword));
            conn.setPassword(secretCipher.mask(rawPassword));
        } else {
            // 降级：旧的有损脱敏（SecretCipher 未注入时）
            conn.setPassword(maskPasswordLegacy(rawPassword));
        }
    }

    /**
     * L-01: 解密密码，用于创建数据源连接。
     * 优先从 passwordCipher 解密；若 passwordCipher 为空（旧数据），降级使用 password 列。
     */
    private String resolvePassword(DbConnection conn) {
        if (conn == null) return "";
        if (secretCipher != null && conn.getPasswordCipher() != null && !conn.getPasswordCipher().isEmpty()) {
            try {
                return secretCipher.decrypt(conn.getPasswordCipher());
            } catch (Exception e) {
                log.warn("Failed to decrypt password for connection {}: {}", conn.getId(), e.getMessage());
                // 降级到 password 列（可能无法连接，但至少不 NPE）
            }
        }
        // 旧数据降级：password 列可能是脱敏值（无法连接）或明文（手动创建的旧数据）
        return conn.getPassword() != null ? conn.getPassword() : "";
    }

    /**
     * 旧版密码脱敏（有损），仅作为 SecretCipher 不可用时的降级。
     */
    private String maskPasswordLegacy(String password) {
        if (password == null || password.isEmpty()) {
            return "";
        }
        if (password.length() <= 4) {
            return "***";
        }
        return password.substring(0, 2) + "***" + password.substring(password.length() - 2);
    }

     /**
      * 解析 Spring 配置中的占位符（如 ${DB_URL:default}）。
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
            // L-02: 检查子目录（深度可配，默认 3 层）
            try (var dirs = Files.walk(rootPath, pathDiscoverMaxDepth)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    if (dir.equals(rootPath)) continue;
                    for (String indicator : indicators) {
                        if (Files.exists(dir.resolve(indicator))) {
                            // 返回相对于 rootPath 的路径
                            Path relative = rootPath.relativize(dir);
                            return relative.toString();
                        }
                    }
                }
            }
        } catch (IOException e) { log.debug("IO skipped: {}", e.getMessage()); }
        return null;
    }

    // ==================== 文档发现 ====================

    private int attachPlanDocuments(String projectId, String versionId, ResolvedScanPlan resolvedPlan) {
        if (resolvedPlan == null || resolvedPlan.getDocuments() == null || resolvedPlan.getDocuments().isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ResolvedDocScope scopedDoc : resolvedPlan.getDocuments()) {
            if (scopedDoc.getDocId() == null || scopedDoc.getDocId().isBlank()) {
                continue;
            }
            try {
                Document doc = documentRepository.selectById(scopedDoc.getDocId());
                if (doc == null || !Objects.equals(projectId, doc.getProjectId())) {
                    continue;
                }
                doc.setVersionId(versionId);
                if (scopedDoc.getFilePath() != null && !scopedDoc.getFilePath().isBlank()) {
                    doc.setFilePath(scopedDoc.getFilePath());
                }
                if ((doc.getDocName() == null || doc.getDocName().isBlank())
                        && scopedDoc.getDocName() != null && !scopedDoc.getDocName().isBlank()) {
                    doc.setDocName(scopedDoc.getDocName());
                }
                if (shouldMarkDocumentDiscovered(doc.getParseStatus())) {
                    doc.setParseStatus("DISCOVERED");
                }
                doc.setUpdatedAt(LocalDateTime.now());
                documentRepository.updateById(doc);
                count++;
            } catch (Exception e) {
                log.debug("Skip selected document {}: {}", scopedDoc.getDocId(), e.getMessage());
            }
        }
        return count;
    }

    private boolean shouldMarkDocumentDiscovered(String parseStatus) {
        return parseStatus == null
                || parseStatus.isBlank()
                || "UPLOADED".equals(parseStatus)
                || "PARTIAL".equals(parseStatus)
                || "FAILED".equals(parseStatus)
                || "PARSE_FAILED".equals(parseStatus);
    }

    /**
     * 自动发现代码仓库中的文档文件，创建 Document 记录。
     * 写入 filePath（绝对路径）、versionId，使自动发现文档可解析。
     * @return 发现的文档数量
     */
    private int discoverDocuments(String projectId, String versionId, String baseDir, ScanTask task, int maxDocs) {
        if (baseDir == null) return 0;
        int count = 0;
        try {
            Path basePath = Paths.get(baseDir);
            if (!Files.exists(basePath)) return 0;

            log.info("Scan still running: projectId={}, versionId={}, phase=DOC_DISCOVERY, detail=walking for document files",
                    projectId, versionId);

            // 排除 node_modules, .git, target, dist, build 等目录（M8 修复：try-with-resources 关闭 Stream）
            List<Path> allDocFiles;
            try (java.util.stream.Stream<Path> stream = Files.walk(basePath, 8)) {  // L9 修复：限制深度 8 层
                allDocFiles = stream
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
                        .collect(Collectors.toList());
            }

            // 记录实际发现的总数（截断前）
            int discoveredDocCount = allDocFiles.size();
            int docLimit = maxDocs > 0 ? maxDocs : Integer.MAX_VALUE;
            List<Path> docFiles = allDocFiles.stream().limit(docLimit).collect(Collectors.toList());
            
            if (discoveredDocCount > docFiles.size()) {
                log.info("DOC discovery: found {} document files, processing {} (limited to maxDocs={})",
                        discoveredDocCount, docFiles.size(), maxDocs);
            }

            log.info("Scan still running: projectId={}, versionId={}, phase=DOC_DISCOVERY, detail=found {} document files",
                    projectId, versionId, docFiles.size());

            Path relativeRoot = basePath.toAbsolutePath();
            int total = docFiles.size();
            // 使用实际发现的总数作为进度统计的 totalItems
            int progressTotal = discoveredDocCount > 0 ? discoveredDocCount : total;
            scanTaskRecorder.logProgress(task, 0, progressTotal, "document files", null);
            int idx = 0;
            for (Path docFile : docFiles) {
                if (isCancelled(versionId)) break;
                try {
                    idx++;
                    scanTaskRecorder.logProgress(task, idx, progressTotal, "document files", docFile.getFileName().toString());
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
        // L-01 修复：从 passwordCipher 解密获取真实密码，降级使用 password 列
        dataSource.setPassword(resolvePassword(conn));
        // 修复：CompletableFuture.runAsync() 使用 ForkJoinPool.commonPool()，
        // 其线程的上下文类加载器可能无法访问 Spring Boot fat jar 的 BOOT-INF/lib/，
        // 导致 DriverManagerDataSource.setDriverClassName() 内部的 Class.forName() 失败。
        // 临时切换为当前类的类加载器（LaunchedURLClassLoader），确保驱动可见。
        String driverClassName = getDriverClassName(conn.getDbType());
        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        ClassLoader appCl = getClass().getClassLoader();
        if (originalCl != appCl) {
            Thread.currentThread().setContextClassLoader(appCl);
        }
        try {
            dataSource.setDriverClassName(driverClassName);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
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

    /**
     * 自动纠偏：当 dbType 与 port 明显不匹配时仅修正端口，不改变 dbType。
     * <ul>
     *   <li>POSTGRESQL + 3306 → port 修正为 5432</li>
     *   <li>MYSQL/MARIADB + 5432 → port 修正为 3306</li>
     * </ul>
     * 修正后同步写回 DB 记录，避免下次扫描重复纠偏。
     */
    private void autoCorrectDbTypeAndPort(DbConnection conn) {
        if (conn == null || conn.getDbType() == null) return;
        String dbType = conn.getDbType().toUpperCase();
        Integer port = conn.getPort();
        Integer correctedPort = null;

        // 仅修正端口，不改变 dbType（之前根据端口推断 dbType 会导致 PG 被误判为 MySQL）
        if ("POSTGRESQL".equals(dbType) && port != null && port == 3306) {
            correctedPort = 5432;
        } else if (("MYSQL".equals(dbType) || "MARIADB".equals(dbType)) && port != null && port == 5432) {
            correctedPort = 3306;
        }

        if (correctedPort != null) {
            log.warn("自动纠偏: connection={}, dbType={}, 原端口={} → 修正端口={}",
                    conn.getConnectionName(), conn.getDbType(), port, correctedPort);
            conn.setPort(correctedPort);
            // 同步写回 DB
            if (dbConnectionRepository != null) {
                try {
                    dbConnectionRepository.updateById(conn);
                } catch (Exception e) {
                    log.warn("纠偏后写回 DB 失败: {}", e.getMessage());
                }
            }
        }
    }

    // ==================== 代码扫描 ====================

    int scanAssetsWithAdapters(String projectId, String versionId,
                               String baseDir, String backendDir, String frontendDir) {
        return scanAssetsWithAdapters(projectId, versionId, baseDir, backendDir, frontendDir, null);
    }

    private int scanAssetsWithAdapters(String projectId, String versionId,
                                       String baseDir, String backendDir, String frontendDir, ScanTask task) {
        return scanAssetsWithAdapters(projectId, versionId, baseDir, backendDir, frontendDir, task, null, null);
    }

    private int scanAssetsWithAdapters(String projectId, String versionId,
                                       String baseDir, String backendDir, String frontendDir,
                                       ScanTask task, ResolvedScanPlan resolvedPlan,
                                       Set<String> changedPaths) {
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
                .config(new java.util.concurrent.ConcurrentHashMap<>())
                .build();

        // P0.1c：为 Java 适配器设置源码根目录，启用 SymbolSolver 跨文件类型解析
        if (backendDir != null && !backendDir.isBlank()) {
            java.io.File sourceRoot = new java.io.File(backendDir);
            if (sourceRoot.isDirectory()) {
                for (ExtractionAdapter adapter : extractionAdapterRegistry.getAllAdapters()) {
                    if (adapter instanceof JavaCodeAdapter javaCodeAdapter) {
                        javaCodeAdapter.setSourceRoot(sourceRoot);
                    } else if (adapter instanceof JavaServiceCallAdapter javaServiceCallAdapter) {
                        javaServiceCallAdapter.setSourceRoot(sourceRoot);
                    }
                }
            }
        }

        if (assetDiscoveryService != null) {
            ResolvedScanPlan effectivePlan = resolvedPlan != null
                    ? resolvedPlan
                    : singleRepoPlan(projectId, versionId, baseDir, backendDir, frontendDir);
            return scanDiscoveredAssetsWithAdapters(projectId, versionId, context, effectivePlan, task, changedPaths);
        }

        try {
            // 排除巨型目录 + 仅收集有适配器能处理的文件类型（M8 修复：try-with-resources 关闭 Stream）
            List<Path> files;
            try (java.util.stream.Stream<Path> stream = Files.walk(root, 10)) {  // L9 修复：限制深度 10 层
                files = stream
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
            }

            // 增量扫描：仅保留内容变更的文件，跳过未变更文件以避免重复抽取
            if (changedPaths != null && !changedPaths.isEmpty()) {
                final Path rootForRel = root;
                int beforeFilter = files.size();
                files = files.stream()
                        .filter(f -> changedPaths.contains(rootForRel.relativize(f).toString()))
                        .collect(Collectors.toList());
                log.info("Incremental adapter scan: {} of {} candidate files changed (projectId={}, versionId={})",
                        files.size(), beforeFilter, projectId, versionId);
            }

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
                                var adapters = extractionAdapterRegistry.selectAdapters(context, asset);
                                if (adapters.isEmpty()) {
                                    return null;
                                }
                                for (ExtractionAdapter adapter : adapters) {
                                    try {
                                        ExtractionResult result = adapter.extract(context, asset);
                                        if (result != null && result.getProcessedAssets() > 0) {
                                            processed.addAndGet(result.getProcessedAssets());
                                        } else {
                                            processed.incrementAndGet();
                                        }
                                    } catch (Exception e) {
                                        log.warn("Adapter {} failed for {}: {}",
                                                adapter.capability().getName(), asset.getRelativePath(), e.getMessage());
                                    }
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
                                                 ScanTask task,
                                                 Set<String> changedPaths) {
        AssetDiscoveryService.DiscoveryResult discoveryResult = assetDiscoveryService.discoverAssets(plan);
        List<SourceAsset> assets = discoveryResult.getAssets();
        int discoveredCount = discoveryResult.getDiscoveredCount();

        // 记录实际发现的总数（截断前）
        if (discoveredCount > assets.size()) {
            log.info("Asset discovery: found {} files, processing {} (limited by maxFiles={})",
                    discoveredCount, assets.size(), plan.getMaxFiles());
        }

        // 增量扫描：仅对变更文件执行抽取；快照与删除检测仍使用完整 assets 列表
        // 修正：增量模式下 changedPaths 非空（含 0 个变更）也走过滤，避免 0 变更退化为全量
        List<SourceAsset> assetsToExtract = assets;
        if (plan.isIncremental() && changedPaths != null) {
            if (changedPaths.isEmpty()) {
                log.info("Incremental scan: 0 files changed, skipping adapter extraction (projectId={}, versionId={})",
                        projectId, versionId);
                assetsToExtract = List.of();
            } else {
                assetsToExtract = assets.stream()
                        .filter(a -> a.getRelativePath() != null && changedPaths.contains(a.getRelativePath()))
                        .collect(Collectors.toList());
                log.info("Incremental adapter scan: {} of {} discovered assets changed (projectId={}, versionId={})",
                        assetsToExtract.size(), assets.size(), projectId, versionId);
            }
        }

        // 使用并发 Adapter 执行器（替换原顺序 for 循环）
        // 若 adapterExecutionService 为 null（测试环境），回退到直调 adapter registry
        int processed;
        if (adapterExecutionService != null) {
            processed = adapterExecutionService.executeDiscoveredAssets(
                    context, assetsToExtract, discoveredCount, task,
                    () -> isCancelled(versionId),
                    plan.isIncremental(),
                    assetDiscoveryService);
        } else {
            // fallback: 顺序执行（测试兼容）
            processed = 0;
            int total = assetsToExtract.size();
            int visited = 0;
            logTaskProgress(task, 0, total, "adapter candidate files");
            for (SourceAsset asset : assetsToExtract) {
                if (isCancelled(versionId)) break;
                visited++;
                try {
                    if (plan.isIncremental() && assetDiscoveryService != null
                            && assetDiscoveryService.isIncrementalSkip(asset, projectId, versionId)) {
                        continue;
                    }
                    var adapters = extractionAdapterRegistry.selectAdapters(context, asset);
                    for (ExtractionAdapter adapter : adapters) {
                        try {
                            ExtractionResult result = adapter.extract(context, asset);
                            if (result != null && result.getProcessedAssets() > 0) {
                                processed += result.getProcessedAssets();
                            } else {
                                processed++;
                            }
                        } catch (Exception ex) {
                            log.warn("Adapter {} failed for {}: {}",
                                    adapter.capability().getName(), asset.getRelativePath(), ex.getMessage());
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
            // 实际执行 Neo4j 节点删除：按 sourcePath 批量删除
            List<String> deletedPaths = deletions.stream()
                    .map(SourceAssetSnapshot::getRelativePath)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            int deletedNodes = neo4jGraphDao.deleteNodesBySourcePaths(projectId, versionId, deletedPaths);
            log.info("Incremental deletion completed: projectId={}, versionId={}, deletedAssets={}, deletedNeo4jNodes={}",
                    projectId, versionId, deletedPaths.size(), deletedNodes);
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
                || name.endsWith(".adoc")
                || name.endsWith(".html")
                || name.endsWith(".htm");
    }

    /**
     * 查找项目上一条 SUCCESS 状态的扫描版本（排除当前版本）。
     * 用于增量扫描时克隆上一版本的完整图谱。
     */
    private ScanVersion findLastSuccessVersion(String projectId, String currentVersionId) {
        try {
            LambdaQueryWrapper<ScanVersion> wrapper = new LambdaQueryWrapper<ScanVersion>()
                    .eq(ScanVersion::getProjectId, projectId)
                    .eq(ScanVersion::getScanStatus, "SUCCESS")
                    .ne(ScanVersion::getId, currentVersionId)
                    .orderByDesc(ScanVersion::getCreatedAt)
                    .last("LIMIT 1");
            return scanVersionRepository.selectOne(wrapper);
        } catch (Exception e) {
            log.warn("findLastSuccessVersion failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 收集适配器候选文本文件内容，用于增量哈希比对。
     * 仅读取文本类文件，文件路径使用相对路径（相对于 baseDir）。
     */
    private Map<String, String> collectAdapterCandidateContents(String baseDir) {
        Map<String, String> pathToContent = new HashMap<>();
        Path root = Paths.get(baseDir);
        if (!Files.exists(root)) {
            return pathToContent;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root, 10)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String pathStr = p.toString();
                        return !pathStr.contains("/node_modules/") && !pathStr.contains("/.git/")
                                && !pathStr.contains("/target/") && !pathStr.contains("/dist/")
                                && !pathStr.contains("/build/") && !pathStr.contains("/__pycache__/")
                                && !pathStr.contains("/.idea/") && !pathStr.contains("/.vscode/");
                    })
                    .filter(this::isTextAdapterCandidate)
                    .collect(Collectors.toList());
            for (Path file : files) {
                try {
                    String relativePath = root.relativize(file).toString();
                    String content = Files.readString(file);
                    pathToContent.put(relativePath, content);
                } catch (IOException e) {
                    log.debug("Skip reading file for hashing: {}", file);
                }
            }
        } catch (IOException e) {
            log.warn("collectAdapterCandidateContents failed for {}: {}", baseDir, e.getMessage());
        }
        return pathToContent;
    }

    /** 仅文本类适配器候选文件（二进制如 .pdf/.docx 不参与哈希，始终视为变更） */
    private boolean isTextAdapterCandidate(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".java")
                || name.endsWith(".xml")
                || name.endsWith(".vue")
                || name.endsWith(".jsx")
                || name.endsWith(".tsx")
                || name.endsWith(".ts")
                || name.endsWith(".js")
                || name.endsWith(".md")
                || name.endsWith(".txt")
                || name.endsWith(".rst")
                || name.endsWith(".adoc")
                || name.endsWith(".html")
                || name.endsWith(".htm");
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

    public int scanDatabaseMetadata(String projectId, String versionId, DataSource dataSource,
                                    DbConnection connection, int maxTables) {
        return databaseMetadataScanService.scan(projectId, versionId, dataSource, connection, maxTables);
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
        task.setId(IdUtil.fastUUID());
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
        completeTask(task, summary, error, null);
    }

    private void completeTask(ScanTask task, String summary, String error, String terminalStatus) {
        if (scanTaskRecorder != null) {
            scanTaskRecorder.completeTask(task, summary, error, terminalStatus);
            return;
        }
        // fallback: 测试环境
        try {
            task.setOutputSummary(objectMapper.writeValueAsString(summary));
        } catch (Exception e) {
            // 先转义反斜杠，再转义双引号（顺序不能颠倒，否则会二次转义）
            task.setOutputSummary("\"" + summary.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        task.setErrorMessage(error);
        task.setTaskStatus(terminalStatus != null ? terminalStatus : (error == null ? "SUCCESS" : "FAILED"));
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
            fact.setId(IdUtil.fastUUID());
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

        try {
            // M10修复：@Async 方法入口添加异常处理
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
        } catch (Exception e) {
            log.error("M10: Resume full scan failed: projectId={}, versionId={}", projectId, versionId, e);
            // 更新扫描状态为失败
            try {
                ScanVersion failedVersion = scanVersionRepository.getById(versionId);
                if (failedVersion != null) {
                    failedVersion.setScanStatus("FAILED");
                    failedVersion.setErrorMessage("续扫失败: " + e.getMessage());
                    scanVersionRepository.updateById(failedVersion);
                }
            } catch (Exception ex) {
                log.error("M10: Failed to update scan status: versionId={}", versionId, ex);
            }
        }
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

    /**
     * 更新 ScanVersion 的 AI 增强状态字段。
     */
    private void updateScanVersionAiStatus(String versionId, String status) {
        try {
            ScanVersion version = scanVersionRepository.getById(versionId);
            if (version != null) {
                version.setAiEnrichmentStatus(status);
                scanVersionRepository.updateById(version);
            }
        } catch (Exception e) {
            log.warn("Failed to update AI enrichment status for versionId={}: {}", versionId, e.getMessage());
        }
    }
}
