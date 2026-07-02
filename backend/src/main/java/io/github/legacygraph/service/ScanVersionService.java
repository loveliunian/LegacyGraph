package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.legacygraph.common.ErrorCode;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.CreateScanVersionRequest;
import io.github.legacygraph.dto.ScanProgressResponse;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.ScanVersion;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ScanVersionService extends ServiceImpl<ScanVersionRepository, ScanVersion> {

    private final ScanTaskRepository scanTaskRepository;
    private final ScanVersionRepository scanVersionRepository;
    private final CacheService cacheService;
    private final GraphCacheInvalidator graphCacheInvalidator;
    private final Neo4jGraphDao neo4jGraphDao;
    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final FactRepository factRepository;
    private final EvidenceRepository evidenceRepository;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;
    private final DocumentRepository documentRepository;
    private final DocChunkRepository docChunkRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestRunRepository testRunRepository;
    private final TestResultRepository testResultRepository;
    private final TestAssertionRepository testAssertionRepository;
    private final RuntimeTraceRepository runtimeTraceRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final KnowledgeClaimRepository knowledgeClaimRepository;
    private final GapTaskRepository gapTaskRepository;
    private final MigrationRiskRepository migrationRiskRepository;

    /** 进度缓存 key 前缀 */
    private static final String PROGRESS_KEY = "scan:progress:";
    /** 运行中进度缓存 TTL（短，吸收高频轮询同时不长时间陈旧） */
    private static final Duration RUNNING_TTL = Duration.ofSeconds(3);
    /** 终态进度缓存 TTL（长，结果不再变化） */
    private static final Duration TERMINAL_TTL = Duration.ofMinutes(30);

    public ScanVersionService(ScanTaskRepository scanTaskRepository,
                              ScanVersionRepository scanVersionRepository,
                              CacheService cacheService,
                              GraphCacheInvalidator graphCacheInvalidator,
                              Neo4jGraphDao neo4jGraphDao,
                              GraphNodeRepository graphNodeRepository,
                              GraphEdgeRepository graphEdgeRepository,
                              FactRepository factRepository,
                              EvidenceRepository evidenceRepository,
                              NodeEvidenceRepository nodeEvidenceRepository,
                              EdgeEvidenceRepository edgeEvidenceRepository,
                              DocumentRepository documentRepository,
                              DocChunkRepository docChunkRepository,
                              TestCaseRepository testCaseRepository,
                              TestRunRepository testRunRepository,
                              TestResultRepository testResultRepository,
                              TestAssertionRepository testAssertionRepository,
                              RuntimeTraceRepository runtimeTraceRepository,
                              ReviewRecordRepository reviewRecordRepository,
                              KnowledgeClaimRepository knowledgeClaimRepository,
                              GapTaskRepository gapTaskRepository,
                              MigrationRiskRepository migrationRiskRepository) {
        this.scanTaskRepository = scanTaskRepository;
        this.scanVersionRepository = scanVersionRepository;
        this.cacheService = cacheService;
        this.graphCacheInvalidator = graphCacheInvalidator;
        this.neo4jGraphDao = neo4jGraphDao;
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.factRepository = factRepository;
        this.evidenceRepository = evidenceRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.edgeEvidenceRepository = edgeEvidenceRepository;
        this.documentRepository = documentRepository;
        this.docChunkRepository = docChunkRepository;
        this.testCaseRepository = testCaseRepository;
        this.testRunRepository = testRunRepository;
        this.testResultRepository = testResultRepository;
        this.testAssertionRepository = testAssertionRepository;
        this.runtimeTraceRepository = runtimeTraceRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.knowledgeClaimRepository = knowledgeClaimRepository;
        this.gapTaskRepository = gapTaskRepository;
        this.migrationRiskRepository = migrationRiskRepository;
    }

    /** 版本号日期格式 */
    private static final DateTimeFormatter VERSION_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 生成不易重复的版本号：scan-YYYYMMDD-HHmmss-XXXX
     */
    private static String generateVersionNo() {
        String datePart = LocalDateTime.now().format(VERSION_DATE_FMT);
        String randPart = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        return "scan-" + datePart + "-" + randPart;
    }

    /**
     * 创建扫描版本
     */
    @Transactional
    public ScanVersion createScanVersion(String projectId, CreateScanVersionRequest request) {
        // 版本号自动生成（空时兜底）
        String versionNo = request.getVersionNo();
        if (versionNo == null || versionNo.isBlank()) {
            versionNo = generateVersionNo();
        }

        // 检查同项目下版本号是否已存在
        LambdaQueryWrapper<ScanVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanVersion::getProjectId, projectId);
        wrapper.eq(ScanVersion::getVersionNo, versionNo);
        if (scanVersionRepository.selectCount(wrapper) > 0) {
            // 如果是自动生成的仍然碰撞（极端情况），重试一次
            if (request.getVersionNo() == null || request.getVersionNo().isBlank()) {
                versionNo = generateVersionNo() + "-r";
                wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(ScanVersion::getProjectId, projectId);
                wrapper.eq(ScanVersion::getVersionNo, versionNo);
                if (scanVersionRepository.selectCount(wrapper) > 0) {
                    throw new BusinessException(ErrorCode.SERVER_ERROR, "版本号生成冲突，请重试");
                }
            } else {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "扫描版本已存在: " + request.getVersionNo());
            }
        }

        ScanVersion version = new ScanVersion();
        version.setProjectId(projectId);
        version.setVersionNo(versionNo);
        version.setBranchName(request.getBranchName());
        version.setCommitId(request.getCommitId());
        version.setScanScope(request.getScanScope());
        version.setScanStatus("CREATED");
        version.setCreatedAt(LocalDateTime.now());
        version.setUpdatedAt(LocalDateTime.now());

        scanVersionRepository.insert(version);
        return version;
    }

    /**
     * 获取扫描进度（缓存优先：减轻前端高频轮询对 DB 的压力）。
     * 运行中以短 TTL 缓存吸收轮询峰值，终态以长 TTL 缓存。
     */
    public ScanProgressResponse getScanProgress(String versionId) {
        String cacheKey = PROGRESS_KEY + versionId;
        ScanProgressResponse cached = cacheService.get(cacheKey, ScanProgressResponse.class);
        if (cached != null) {
            return cached;
        }

        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("扫描版本不存在: " + versionId);
        }

        LambdaQueryWrapper<ScanTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ScanTask::getVersionId, versionId);
        List<ScanTask> tasks = scanTaskRepository.selectList(wrapper);

        List<ScanProgressResponse.TaskProgress> taskProgressList = new ArrayList<>();
        int totalTasks = tasks.size();
        int completedTasks = 0;

        for (ScanTask task : tasks) {
            ScanProgressResponse.TaskProgress tp = new ScanProgressResponse.TaskProgress();
            tp.setTaskType(task.getTaskType());
            tp.setStatus(task.getTaskStatus());
            tp.setFactCount(0);
            taskProgressList.add(tp);

            if ("SUCCESS".equals(task.getTaskStatus())) {
                completedTasks++;
            }
        }

        int progress = totalTasks > 0 ? (completedTasks * 100 / totalTasks) : 0;

        ScanProgressResponse response =
                new ScanProgressResponse(versionId, version.getScanStatus(), progress, taskProgressList);

        boolean terminal = "SUCCESS".equals(version.getScanStatus())
                || "FAILED".equals(version.getScanStatus())
                || "CANCELLED".equals(version.getScanStatus());
        cacheService.put(cacheKey, response, terminal ? TERMINAL_TTL : RUNNING_TTL);
        return response;
    }

    /**
     * 更新扫描状态
     */
    @Transactional
    public void updateScanStatus(String versionId, String status) {
        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version != null) {
            version.setScanStatus(status);
            if ("RUNNING".equals(status)) {
                version.setStartedAt(LocalDateTime.now());
            } else if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                version.setFinishedAt(LocalDateTime.now());
            }
            version.setUpdatedAt(LocalDateTime.now());
            scanVersionRepository.updateById(version);
            cacheService.evict(PROGRESS_KEY + versionId);
        }
    }

    /**
     * 删除扫描版本及其关联的所有数据（物理删除）。
     */
    @Transactional
    public void deleteScanVersion(String versionId) {
        ScanVersion version = scanVersionRepository.selectById(versionId);
        if (version == null) {
            return;
        }
        String projectId = version.getProjectId();

        // 1. 删除关联的扫描任务
        LambdaQueryWrapper<ScanTask> taskWrapper = new LambdaQueryWrapper<>();
        taskWrapper.eq(ScanTask::getVersionId, versionId);
        scanTaskRepository.selectList(taskWrapper)
                .forEach(task -> scanTaskRepository.deleteById(task.getId()));

        // 2. 删除 Neo4j 子图
        neo4jGraphDao.deleteGraph(projectId, versionId);

        // 3. 删除 PG 图谱节点和边
        graphNodeRepository.delete(new QueryWrapper<GraphNode>().eq("version_id", versionId));
        graphEdgeRepository.delete(new QueryWrapper<GraphEdge>().eq("version_id", versionId));

        // 4. 删除事实
        factRepository.delete(new QueryWrapper<Fact>().eq("version_id", versionId));

        // 5. 删除证据（先删关联表）
        nodeEvidenceRepository.delete(new QueryWrapper<NodeEvidence>()
                .inSql("evidence_id", "SELECT id FROM lg_evidence WHERE version_id = '" + versionId + "'"));
        edgeEvidenceRepository.delete(new QueryWrapper<EdgeEvidence>()
                .inSql("evidence_id", "SELECT id FROM lg_evidence WHERE version_id = '" + versionId + "'"));
        evidenceRepository.delete(new QueryWrapper<Evidence>().eq("version_id", versionId));

        // 6. 删除文档
        docChunkRepository.delete(new QueryWrapper<DocChunk>().eq("version_id", versionId));
        documentRepository.delete(new QueryWrapper<Document>().eq("version_id", versionId));

        // 7. 删除测试数据
        testAssertionRepository.delete(new QueryWrapper<TestAssertion>()
                .inSql("test_case_id", "SELECT id FROM lg_test_case WHERE version_id = '" + versionId + "'"));
        testResultRepository.delete(new QueryWrapper<TestResult>().eq("version_id", versionId));
        testRunRepository.delete(new QueryWrapper<TestRun>().eq("version_id", versionId));
        testCaseRepository.delete(new QueryWrapper<TestCase>().eq("version_id", versionId));

        // 8. 删除追踪/审核/知识/缺口/风险
        runtimeTraceRepository.delete(new QueryWrapper<RuntimeTrace>().eq("version_id", versionId));
        reviewRecordRepository.delete(new QueryWrapper<ReviewRecord>().eq("version_id", versionId));
        knowledgeClaimRepository.delete(new QueryWrapper<KnowledgeClaim>().eq("version_id", versionId));
        gapTaskRepository.delete(new QueryWrapper<GapTask>().eq("version_id", versionId));
        migrationRiskRepository.delete(new QueryWrapper<MigrationRisk>().eq("version_id", versionId));

        // 9. 删除版本本身前，彻底清除所有相关缓存
        cacheService.evict(PROGRESS_KEY + versionId);
        // 失效图谱视图缓存、验证报告缓存、报告缓存、语义检索缓存
        graphCacheInvalidator.invalidateVersion(versionId);
        // 失效该版本所有节点详情缓存
        cacheService.evictByPrefix("graph:node:");

        scanVersionRepository.deleteById(versionId);
    }
}
