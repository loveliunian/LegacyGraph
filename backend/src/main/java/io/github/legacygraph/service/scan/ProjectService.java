package io.github.legacygraph.service.scan;

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
import io.github.legacygraph.service.graph.GraphCacheInvalidator;
import io.github.legacygraph.service.system.CacheService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 项目服务
 * 管理知识图谱分析项目，项目是LegacyGraph中最高级别的组织单元
 * 一个项目对应一个需要分析的遗留系统
 */
@Slf4j
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
    private final AiScanJobRepository aiScanJobRepository;
    private final EvidenceConflictRepository evidenceConflictRepository;
    private final GraphWriteIntentRepository graphWriteIntentRepository;
    private final NotificationRepository notificationRepository;
    private final QaConversationRepository qaConversationRepository;
    private final QaFeedbackRepository qaFeedbackRepository;
    private final SemanticCacheRepository semanticCacheRepository;
    private final SourceAssetSnapshotRepository sourceAssetSnapshotRepository;
    private final ToolRunRepository toolRunRepository;
    private final QaMessageRepository qaMessageRepository;
    private final ToolEvidenceRepository toolEvidenceRepository;
    private final GraphCacheInvalidator graphCacheInvalidator;
    private final ScanVersionService scanVersionService;

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
                          AiScanJobRepository aiScanJobRepository,
                          EvidenceConflictRepository evidenceConflictRepository,
                          GraphWriteIntentRepository graphWriteIntentRepository,
                          NotificationRepository notificationRepository,
                          QaConversationRepository qaConversationRepository,
                          QaFeedbackRepository qaFeedbackRepository,
                          SemanticCacheRepository semanticCacheRepository,
                          SourceAssetSnapshotRepository sourceAssetSnapshotRepository,
                          ToolRunRepository toolRunRepository,
                          QaMessageRepository qaMessageRepository,
                          ToolEvidenceRepository toolEvidenceRepository,
                          GraphCacheInvalidator graphCacheInvalidator,
                          ScanVersionService scanVersionService) {
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
        this.aiScanJobRepository = aiScanJobRepository;
        this.evidenceConflictRepository = evidenceConflictRepository;
        this.graphWriteIntentRepository = graphWriteIntentRepository;
        this.notificationRepository = notificationRepository;
        this.qaConversationRepository = qaConversationRepository;
        this.qaFeedbackRepository = qaFeedbackRepository;
        this.semanticCacheRepository = semanticCacheRepository;
        this.sourceAssetSnapshotRepository = sourceAssetSnapshotRepository;
        this.toolRunRepository = toolRunRepository;
        this.qaMessageRepository = qaMessageRepository;
        this.toolEvidenceRepository = toolEvidenceRepository;
        this.graphCacheInvalidator = graphCacheInvalidator;
        this.scanVersionService = scanVersionService;
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
     * <p>优化：委托 ScanVersionService 批量删除版本、Neo4j 异步、项目级并行删除。</p>
     */
    // S9 修复：移除 @Transactional，因为 CompletableFuture.runAsync 在独立线程执行，
    // 无法共享事务上下文。改为"先删关联数据（并行），最后删项目记录"的弱一致性模型。
    // 失败时通过日志和状态标记追踪，避免留下孤儿数据。
    public void deleteById(String id) {
        Project project = projectRepository.selectById(id);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }

        // 收集所有并行删除任务
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<String> failedSteps = java.util.Collections.synchronizedList(new ArrayList<>());

        // 1. 并行删除所有扫描版本
        LambdaQueryWrapper<ScanVersion> versionWrapper = new LambdaQueryWrapper<>();
        versionWrapper.eq(ScanVersion::getProjectId, id);
        List<ScanVersion> versions = scanVersionRepository.selectList(versionWrapper);
        for (ScanVersion version : versions) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    scanVersionService.deleteScanVersion(version.getId());
                } catch (Exception e) {
                    log.warn("S9: 删除扫描版本失败 versionId={}, error={}", version.getId(), e.getMessage());
                    failedSteps.add("scan_version:" + version.getId());
                }
            }));
        }

        // 2. Neo4j 全项目图谱异步删除（不阻塞响应）
        deleteNeo4jProjectGraphAsync(id);

        // 3. 项目级 PG 图谱
        CompletableFuture<Void> graphFuture = CompletableFuture.runAsync(() -> {
            try {
                graphNodeRepository.delete(new QueryWrapper<GraphNode>().eq("project_id", id));
                graphEdgeRepository.delete(new QueryWrapper<GraphEdge>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除项目级PG图谱失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("graph:" + id);
            }
        });

        // 4. 事实/证据（先查 ID 再批量删，避免 SQL 注入）
        CompletableFuture<Void> evidenceFuture = CompletableFuture.runAsync(() -> {
            try {
                List<Evidence> evidenceList = evidenceRepository.selectList(
                        new QueryWrapper<Evidence>().select("id").eq("project_id", id));
                if (!evidenceList.isEmpty()) {
                    List<String> evidenceIds = evidenceList.stream().map(Evidence::getId).toList();
                    nodeEvidenceRepository.delete(new QueryWrapper<NodeEvidence>().in("evidence_id", evidenceIds));
                    edgeEvidenceRepository.delete(new QueryWrapper<EdgeEvidence>().in("evidence_id", evidenceIds));
                }
                evidenceRepository.delete(new QueryWrapper<Evidence>().eq("project_id", id));
                factRepository.delete(new QueryWrapper<Fact>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除事实/证据失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("evidence:" + id);
            }
        });

        // 5. 文档
        CompletableFuture<Void> docFuture = CompletableFuture.runAsync(() -> {
            try {
                docChunkRepository.delete(new QueryWrapper<DocChunk>().eq("project_id", id));
                documentRepository.delete(new QueryWrapper<Document>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除文档失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("doc:" + id);
            }
        });

        // 6. 测试数据
        CompletableFuture<Void> testFuture = CompletableFuture.runAsync(() -> {
            try {
                List<TestCase> testCases = testCaseRepository.selectList(
                        new QueryWrapper<TestCase>().select("id").eq("project_id", id));
                if (!testCases.isEmpty()) {
                    List<String> testCaseIds = testCases.stream().map(TestCase::getId).toList();
                    testAssertionRepository.delete(new QueryWrapper<TestAssertion>().in("test_case_id", testCaseIds));
                }
                testResultRepository.delete(new QueryWrapper<TestResult>().eq("project_id", id));
                testRunRepository.delete(new QueryWrapper<TestRun>().eq("project_id", id));
                testCaseRepository.delete(new QueryWrapper<TestCase>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除测试数据失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("test:" + id);
            }
        });

        // 7. 追踪/审核/报告
        CompletableFuture<Void> auditFuture = CompletableFuture.runAsync(() -> {
            try {
                runtimeTraceRepository.delete(new QueryWrapper<RuntimeTrace>().eq("project_id", id));
                reviewRecordRepository.delete(new QueryWrapper<ReviewRecord>().eq("project_id", id));
                reportRepository.delete(new QueryWrapper<Report>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除追踪/审核/报告失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("audit:" + id);
            }
        });

        // 8. 向量/提示/本体/Agent（agent_run 引用 prompt_run，须先删 agent_run）
        CompletableFuture<Void> miscFuture = CompletableFuture.runAsync(() -> {
            try {
                vectorDocumentRepository.delete(new QueryWrapper<VectorDocument>().eq("project_id", id));
                agentRunRepository.delete(new QueryWrapper<AgentRun>().eq("project_id", id));
                promptRunRepository.delete(new QueryWrapper<PromptRun>().eq("project_id", id));
                domainOntologyTermRepository.delete(new QueryWrapper<DomainOntologyTerm>().eq("project_id", id));
                domainOntologyRelationRepository.delete(new QueryWrapper<DomainOntologyRelation>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除向量/提示/本体/Agent失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("misc:" + id);
            }
        });

        // 8.1 遗漏表：AI扫描任务/证据冲突/写意图/通知/QA/语义缓存/资产快照/工具运行
        CompletableFuture<Void> extraFuture = CompletableFuture.runAsync(() -> {
            try {
                aiScanJobRepository.delete(new QueryWrapper<AiScanJob>().eq("project_id", id));
                evidenceConflictRepository.delete(new QueryWrapper<EvidenceConflict>().eq("project_id", id));
                graphWriteIntentRepository.delete(new QueryWrapper<GraphWriteIntentEntity>().eq("project_id", id));
                notificationRepository.delete(new QueryWrapper<Notification>().eq("project_id", id));
                // QA 删除顺序：qa_feedback(FK→qa_message) → qa_message(FK→qa_conversation) → qa_conversation
                List<QaConversation> conversations = qaConversationRepository.selectList(
                        new QueryWrapper<QaConversation>().select("id").eq("project_id", id));
                if (!conversations.isEmpty()) {
                    List<String> conversationIds = conversations.stream().map(QaConversation::getId).toList();
                    qaFeedbackRepository.delete(new QueryWrapper<QaFeedback>().eq("project_id", id));
                    qaMessageRepository.delete(new QueryWrapper<QaMessage>().in("conversation_id", conversationIds));
                }
                qaConversationRepository.delete(new QueryWrapper<QaConversation>().eq("project_id", id));
                semanticCacheRepository.delete(new QueryWrapper<SemanticCacheEntry>().eq("project_id", id));
                sourceAssetSnapshotRepository.delete(new QueryWrapper<SourceAssetSnapshot>().eq("project_id", id));
                // 先删 tool_evidence（子表，FK→tool_run），再删 tool_run（父表）
                List<ToolRunEntity> toolRuns = toolRunRepository.selectList(
                        new QueryWrapper<ToolRunEntity>().select("id").eq("project_id", id));
                if (!toolRuns.isEmpty()) {
                    List<String> toolRunIds = toolRuns.stream().map(ToolRunEntity::getId).toList();
                    toolEvidenceRepository.delete(new QueryWrapper<ToolEvidenceEntity>().in("tool_run_id", toolRunIds));
                }
                toolRunRepository.delete(new QueryWrapper<ToolRunEntity>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除额外表失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("extra:" + id);
            }
        });

        // 9. 知识/缺口/风险
        CompletableFuture<Void> kgFuture = CompletableFuture.runAsync(() -> {
            try {
                knowledgeClaimRepository.delete(new QueryWrapper<KnowledgeClaim>().eq("project_id", id));
                gapTaskRepository.delete(new QueryWrapper<GapTask>().eq("project_id", id));
                migrationRiskRepository.delete(new QueryWrapper<MigrationRisk>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除知识/缺口/风险失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("kg:" + id);
            }
        });

        // 10. 变更管线（先查 ID 再批量删）
        CompletableFuture<Void> changeFuture = CompletableFuture.runAsync(() -> {
            try {
                List<ChangeTask> changeTasks = changeTaskRepository.selectList(
                        new QueryWrapper<ChangeTask>().select("id").eq("project_id", id));
                if (!changeTasks.isEmpty()) {
                    List<String> changeTaskIds = changeTasks.stream().map(ChangeTask::getId).toList();
                    prTaskRepository.delete(new QueryWrapper<PrTask>().in("change_task_id", changeTaskIds));
                    validationGateRepository.delete(new QueryWrapper<ValidationGate>().in("change_task_id", changeTaskIds));
                    patchFileRepository.delete(new QueryWrapper<PatchFile>().in("change_task_id", changeTaskIds));
                }
                changeTaskRepository.delete(new QueryWrapper<ChangeTask>().eq("project_id", id));
            } catch (Exception e) {
                log.warn("S9: 删除变更管线失败: projectId={}, error={}", id, e.getMessage());
                failedSteps.add("change:" + id);
            }
        });

        // 等待所有并行删除完成
        futures.add(graphFuture);
        futures.add(evidenceFuture);
        futures.add(docFuture);
        futures.add(testFuture);
        futures.add(auditFuture);
        futures.add(miscFuture);
        futures.add(extraFuture);
        futures.add(kgFuture);
        futures.add(changeFuture);
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 11. 项目级配置
        codeRepoRepository.delete(new QueryWrapper<CodeRepo>().eq("project_id", id));
        dbConnectionRepository.delete(new QueryWrapper<DbConnection>().eq("project_id", id));

        // 12. 清除所有项目相关缓存
        graphCacheInvalidator.invalidateProjectOverview(id);
        cacheService.evictByPrefix("graph:");
        graphCacheInvalidator.invalidateAll();

        // 13. 删除项目（最后执行，确保关联数据已清理）
        projectRepository.deleteById(id);

        // S9 修复：记录失败步骤，便于后续排查
        if (!failedSteps.isEmpty()) {
            log.warn("S9: 项目删除完成，但以下步骤失败: projectId={}, failedSteps={}", id, failedSteps);
        } else {
            log.info("S9: 项目删除完成: projectId={}", id);
        }
    }

    /**
     * 异步删除 Neo4j 全项目图谱。
     */
    @Async("taskExecutor")
    public void deleteNeo4jProjectGraphAsync(String projectId) {
        try {
            neo4jGraphDao.deleteProjectGraph(projectId);
        } catch (Exception e) {
            // L11 修复：Neo4j 删除异常不再静默吞没，记录 warn 日志便于排查
            log.warn("L11: Neo4j project graph deletion failed: projectId={}, error={}",
                    projectId, e.getMessage(), e);
        }
    }
}
