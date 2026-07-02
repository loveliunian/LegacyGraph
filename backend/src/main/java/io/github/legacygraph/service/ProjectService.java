package io.github.legacygraph.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.legacygraph.common.PageQuery;
import io.github.legacygraph.common.PageResult;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.CreateProjectRequest;
import io.github.legacygraph.entity.*;
import io.github.legacygraph.exception.BusinessException;
import io.github.legacygraph.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 项目服务
 * 管理知识图谱分析项目，项目是LegacyGraph中最高级别的组织单元
 * 一个项目对应一个需要分析的遗留系统
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final CodeRepoRepository codeRepoRepository;
    private final DbConnectionRepository dbConnectionRepository;
    private final CacheService cacheService;
    private final Neo4jGraphDao neo4jGraphDao;
    private final ScanVersionRepository scanVersionRepository;
    private final ScanTaskRepository scanTaskRepository;
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
    private final ReportRepository reportRepository;
    private final VectorDocumentRepository vectorDocumentRepository;
    private final PromptRunRepository promptRunRepository;
    private final DomainOntologyTermRepository domainOntologyTermRepository;
    private final DomainOntologyRelationRepository domainOntologyRelationRepository;
    private final KnowledgeClaimRepository knowledgeClaimRepository;
    private final GapTaskRepository gapTaskRepository;
    private final MigrationRiskRepository migrationRiskRepository;
    private final AgentRunRepository agentRunRepository;
    private final ChangeTaskRepository changeTaskRepository;
    private final PrTaskRepository prTaskRepository;
    private final PatchFileRepository patchFileRepository;
    private final ValidationGateRepository validationGateRepository;
    private final GraphCacheInvalidator graphCacheInvalidator;

    public ProjectService(ProjectRepository projectRepository,
                          CodeRepoRepository codeRepoRepository,
                          DbConnectionRepository dbConnectionRepository,
                          CacheService cacheService,
                          Neo4jGraphDao neo4jGraphDao,
                          ScanVersionRepository scanVersionRepository,
                          ScanTaskRepository scanTaskRepository,
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
                          ReportRepository reportRepository,
                          VectorDocumentRepository vectorDocumentRepository,
                          PromptRunRepository promptRunRepository,
                          DomainOntologyTermRepository domainOntologyTermRepository,
                          DomainOntologyRelationRepository domainOntologyRelationRepository,
                          KnowledgeClaimRepository knowledgeClaimRepository,
                          GapTaskRepository gapTaskRepository,
                          MigrationRiskRepository migrationRiskRepository,
                          AgentRunRepository agentRunRepository,
                          ChangeTaskRepository changeTaskRepository,
                          PrTaskRepository prTaskRepository,
                          PatchFileRepository patchFileRepository,
                          ValidationGateRepository validationGateRepository,
                          GraphCacheInvalidator graphCacheInvalidator) {
        this.projectRepository = projectRepository;
        this.codeRepoRepository = codeRepoRepository;
        this.dbConnectionRepository = dbConnectionRepository;
        this.cacheService = cacheService;
        this.neo4jGraphDao = neo4jGraphDao;
        this.scanVersionRepository = scanVersionRepository;
        this.scanTaskRepository = scanTaskRepository;
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
        this.reportRepository = reportRepository;
        this.vectorDocumentRepository = vectorDocumentRepository;
        this.promptRunRepository = promptRunRepository;
        this.domainOntologyTermRepository = domainOntologyTermRepository;
        this.domainOntologyRelationRepository = domainOntologyRelationRepository;
        this.knowledgeClaimRepository = knowledgeClaimRepository;
        this.gapTaskRepository = gapTaskRepository;
        this.migrationRiskRepository = migrationRiskRepository;
        this.agentRunRepository = agentRunRepository;
        this.changeTaskRepository = changeTaskRepository;
        this.prTaskRepository = prTaskRepository;
        this.patchFileRepository = patchFileRepository;
        this.validationGateRepository = validationGateRepository;
        this.graphCacheInvalidator = graphCacheInvalidator;
    }

    public PageResult<Project> listProjects(PageQuery query) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Project::getCreatedAt);
        Page<Project> page = projectRepository.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.getRecords(), page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    public Project getById(String id) {
        return projectRepository.selectById(id);
    }

    @Transactional
    public Project createProject(CreateProjectRequest request) {
        Project project = new Project();
        project.setId(UUID.randomUUID().toString());
        project.setProjectCode(request.getProjectCode());
        project.setProjectName(request.getProjectName());
        project.setDescription(request.getDescription());
        project.setProjectType(request.getProjectType() != null ? request.getProjectType() : "LEGACY");
        project.setRepoUrl(request.getRepoUrl());
        project.setDefaultBranch(request.getDefaultBranch());
        project.setStatus("INIT");
        project.setOwner(request.getOwner());
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
        projectRepository.insert(project);

        String repoUrl = request.getRepoUrl();
        if (repoUrl != null && !repoUrl.isBlank()) {
            String branch = request.getDefaultBranch();
            if (branch == null || branch.isBlank()) {
                branch = "main";
            }
            CodeRepo codeRepo = new CodeRepo();
            codeRepo.setId(UUID.randomUUID().toString());
            codeRepo.setProjectId(project.getId());
            codeRepo.setRepoName(extractRepoName(repoUrl));
            codeRepo.setRepoType("FULLSTACK");
            codeRepo.setGitUrl(repoUrl.trim());
            codeRepo.setBranchName(branch);
            codeRepo.setStatus("PENDING");
            codeRepo.setCreatedAt(LocalDateTime.now());
            codeRepo.setUpdatedAt(LocalDateTime.now());
            codeRepoRepository.insert(codeRepo);
        }
        return project;
    }

    private String extractRepoName(String gitUrl) {
        String url = gitUrl.trim();
        if (url.endsWith(".git")) {
            url = url.substring(0, url.length() - 4);
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            return url.substring(lastSlash + 1);
        }
        return url;
    }

    /**
     * 物理删除项目及所有关联数据。
     */
    @Transactional
    public void deleteById(String id) {
        Project project = projectRepository.selectById(id);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }

        // 1. 遍历所有扫描版本，逐版本级联删除
        LambdaQueryWrapper<ScanVersion> versionWrapper = new LambdaQueryWrapper<>();
        versionWrapper.eq(ScanVersion::getProjectId, id);
        for (ScanVersion version : scanVersionRepository.selectList(versionWrapper)) {
            deleteScanVersionData(version);
        }

        // 2. 删除 Neo4j 全项目图谱
        neo4jGraphDao.deleteProjectGraph(id);

        // 3. 删除项目级 PG 图谱
        graphNodeRepository.delete(new QueryWrapper<GraphNode>().eq("project_id", id));
        graphEdgeRepository.delete(new QueryWrapper<GraphEdge>().eq("project_id", id));

        // 4. 删除事实/证据
        factRepository.delete(new QueryWrapper<Fact>().eq("project_id", id));
        nodeEvidenceRepository.delete(new QueryWrapper<NodeEvidence>()
                .inSql("evidence_id", "SELECT id FROM lg_evidence WHERE project_id = '" + id + "'"));
        edgeEvidenceRepository.delete(new QueryWrapper<EdgeEvidence>()
                .inSql("evidence_id", "SELECT id FROM lg_evidence WHERE project_id = '" + id + "'"));
        evidenceRepository.delete(new QueryWrapper<Evidence>().eq("project_id", id));

        // 5. 删除文档
        docChunkRepository.delete(new QueryWrapper<DocChunk>().eq("project_id", id));
        documentRepository.delete(new QueryWrapper<Document>().eq("project_id", id));

        // 6. 删除测试数据
        testAssertionRepository.delete(new QueryWrapper<TestAssertion>()
                .inSql("test_case_id", "SELECT id FROM lg_test_case WHERE project_id = '" + id + "'"));
        testResultRepository.delete(new QueryWrapper<TestResult>().eq("project_id", id));
        testRunRepository.delete(new QueryWrapper<TestRun>().eq("project_id", id));
        testCaseRepository.delete(new QueryWrapper<TestCase>().eq("project_id", id));

        // 7. 删除追踪/审核/报告
        runtimeTraceRepository.delete(new QueryWrapper<RuntimeTrace>().eq("project_id", id));
        reviewRecordRepository.delete(new QueryWrapper<ReviewRecord>().eq("project_id", id));
        reportRepository.delete(new QueryWrapper<Report>().eq("project_id", id));

        // 8. 删除向量文档
        vectorDocumentRepository.delete(new QueryWrapper<VectorDocument>().eq("project_id", id));

        // 9. Prompt 运行
        promptRunRepository.delete(new QueryWrapper<PromptRun>().eq("project_id", id));

        // 10. 领域本体
        domainOntologyTermRepository.delete(new QueryWrapper<DomainOntologyTerm>().eq("project_id", id));
        domainOntologyRelationRepository.delete(new QueryWrapper<DomainOntologyRelation>().eq("project_id", id));

        // 11. 知识声明/缺口/风险
        knowledgeClaimRepository.delete(new QueryWrapper<KnowledgeClaim>().eq("project_id", id));
        gapTaskRepository.delete(new QueryWrapper<GapTask>().eq("project_id", id));
        migrationRiskRepository.delete(new QueryWrapper<MigrationRisk>().eq("project_id", id));

        // 12. AgentRun
        agentRunRepository.delete(new QueryWrapper<AgentRun>().eq("project_id", id));

        // 13. 变更任务管线
        prTaskRepository.delete(new QueryWrapper<PrTask>()
                .inSql("change_task_id", "SELECT id FROM lg_change_task WHERE project_id = '" + id + "'"));
        validationGateRepository.delete(new QueryWrapper<ValidationGate>()
                .inSql("change_task_id", "SELECT id FROM lg_change_task WHERE project_id = '" + id + "'"));
        patchFileRepository.delete(new QueryWrapper<PatchFile>()
                .inSql("change_task_id", "SELECT id FROM lg_change_task WHERE project_id = '" + id + "'"));
        changeTaskRepository.delete(new QueryWrapper<ChangeTask>().eq("project_id", id));

        // 14. 项目级配置
        codeRepoRepository.delete(new QueryWrapper<CodeRepo>().eq("project_id", id));
        dbConnectionRepository.delete(new QueryWrapper<DbConnection>().eq("project_id", id));

        // 15. 彻底清除所有项目相关缓存
        // 项目概览缓存
        graphCacheInvalidator.invalidateProjectOverview(id);
        // 全量图谱缓存（graph:* 和 graph:node:*）
        cacheService.evictByPrefix("graph:");
        // 所有报告和验证缓存
        graphCacheInvalidator.invalidateAll();

        // 16. 删除项目
        projectRepository.deleteById(id);
    }

    /**
     * 删除单个扫描版本的所有关联数据。
     */
    private void deleteScanVersionData(ScanVersion version) {
        String versionId = version.getId();
        String projectId = version.getProjectId();

        LambdaQueryWrapper<ScanTask> taskWrapper = new LambdaQueryWrapper<>();
        taskWrapper.eq(ScanTask::getVersionId, versionId);
        scanTaskRepository.selectList(taskWrapper)
                .forEach(task -> scanTaskRepository.deleteById(task.getId()));

        neo4jGraphDao.deleteGraph(projectId, versionId);

        graphNodeRepository.delete(new QueryWrapper<GraphNode>().eq("version_id", versionId));
        graphEdgeRepository.delete(new QueryWrapper<GraphEdge>().eq("version_id", versionId));
        factRepository.delete(new QueryWrapper<Fact>().eq("version_id", versionId));

        nodeEvidenceRepository.delete(new QueryWrapper<NodeEvidence>()
                .inSql("evidence_id", "SELECT id FROM lg_evidence WHERE version_id = '" + versionId + "'"));
        edgeEvidenceRepository.delete(new QueryWrapper<EdgeEvidence>()
                .inSql("evidence_id", "SELECT id FROM lg_evidence WHERE version_id = '" + versionId + "'"));
        evidenceRepository.delete(new QueryWrapper<Evidence>().eq("version_id", versionId));

        docChunkRepository.delete(new QueryWrapper<DocChunk>().eq("version_id", versionId));
        documentRepository.delete(new QueryWrapper<Document>().eq("version_id", versionId));

        testAssertionRepository.delete(new QueryWrapper<TestAssertion>()
                .inSql("test_case_id", "SELECT id FROM lg_test_case WHERE version_id = '" + versionId + "'"));
        testResultRepository.delete(new QueryWrapper<TestResult>().eq("version_id", versionId));
        testRunRepository.delete(new QueryWrapper<TestRun>().eq("version_id", versionId));
        testCaseRepository.delete(new QueryWrapper<TestCase>().eq("version_id", versionId));

        runtimeTraceRepository.delete(new QueryWrapper<RuntimeTrace>().eq("version_id", versionId));
        reviewRecordRepository.delete(new QueryWrapper<ReviewRecord>().eq("version_id", versionId));
        knowledgeClaimRepository.delete(new QueryWrapper<KnowledgeClaim>().eq("version_id", versionId));
        gapTaskRepository.delete(new QueryWrapper<GapTask>().eq("version_id", versionId));
        migrationRiskRepository.delete(new QueryWrapper<MigrationRisk>().eq("version_id", versionId));

        // 清除该版本的所有缓存
        cacheService.evict("scan:progress:" + versionId);
        graphCacheInvalidator.invalidateVersion(versionId);

        scanVersionRepository.deleteById(versionId);
    }
}
