package io.github.legacygraph.task.step;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.service.scan.DocumentContentService;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI_DOC_EXTRACT — 文档业务事实抽取（写入 lg_fact，PENDING_CONFIRM）。
 */
@Slf4j
@Component
public class DocExtractStep implements AiScanStepExecutor {

    /** 文档 LLM 调用单次上限字符数 */
    private static final int DOC_CONTENT_LIMIT = 8000;

    private final AiScanStepSupport support;
    private final DocumentRepository documentRepository;
    private final DocUnderstandingAgent docUnderstandingAgent;
    private final BusinessGraphBuilder businessGraphBuilder;
    private final Neo4jGraphDao neo4jGraphDao;
    private final DocumentContentService documentContentService = new DocumentContentService();
    private final Counter agentCallCounter;
    private final Counter graphNodeCounter;
    private final Counter graphEdgeCounter;

    public DocExtractStep(AiScanStepSupport support,
                          DocumentRepository documentRepository,
                          DocUnderstandingAgent docUnderstandingAgent,
                          BusinessGraphBuilder businessGraphBuilder,
                          Neo4jGraphDao neo4jGraphDao,
                          @Qualifier("agentCallCounter") Counter agentCallCounter,
                          @Qualifier("graphNodeCounter") Counter graphNodeCounter,
                          @Qualifier("graphEdgeCounter") Counter graphEdgeCounter) {
        this.support = support;
        this.documentRepository = documentRepository;
        this.docUnderstandingAgent = docUnderstandingAgent;
        this.businessGraphBuilder = businessGraphBuilder;
        this.neo4jGraphDao = neo4jGraphDao;
        this.agentCallCounter = agentCallCounter;
        this.graphNodeCounter = graphNodeCounter;
        this.graphEdgeCounter = graphEdgeCounter;
    }

    @Override
    public String getStepName() {
        return "AI_DOC_EXTRACT";
    }

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.INIT;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        io.github.legacygraph.entity.ScanTask task =
                support.createTask(projectId, versionId, "AI_DOC_EXTRACT", "文档业务事实抽取");
        try {
            List<Document> docs = documentRepository.selectList(
                    new LambdaQueryWrapper<Document>()
                            .eq(Document::getProjectId, projectId)
                            .eq(Document::getVersionId, versionId));
            if (docs.isEmpty()) {
                support.completeTask(task, buildDocExtractSummary(0, 0), null);
                return StepExecutionResult.builder().success(true)
                        .message(buildDocExtractSummary(0, 0)).processedCount(0).build();
            }

            // 断点续传：跳过已完成的文件
            java.util.Set<String> donePaths = support.findDoneFilePaths(projectId, versionId, "DOC_EXTRACT");
            long skipped = docs.stream().filter(d -> donePaths.contains(d.getFilePath())).count();
            if (skipped > 0) {
                log.info("AI_DOC_EXTRACT checkpoint resume: {} docs already done, {} remaining",
                        skipped, docs.size() - skipped);
            }

            log.info("AI_DOC_EXTRACT starting: versionId={}, docCount={}, parallelism={}, skipped={}",
                    versionId, docs.size(), AiScanStepSupport.DOC_EXTRACT_PARALLELISM, skipped);

            AtomicInteger factCount = new AtomicInteger(0);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Document doc : docs) {
                // 跳过已完成的文件
                if (donePaths.contains(doc.getFilePath())) {
                    continue;
                }
                String filePath = doc.getFilePath();
                futures.add(CompletableFuture.runAsync(() -> {
                    support.markExtracting(projectId, versionId, filePath, "DOC_EXTRACT");
                    String content = readDocContent(doc);
                    if (content == null || content.isBlank()) {
                        doc.setParseStatus("FAILED");
                        doc.setErrorMessage("无法读取文档内容：文件不存在或为空");
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        try { documentRepository.updateById(doc); } catch (Exception e) { log.warn("Failed to update doc: {}", doc.getId(), e); }
                        support.markExtractFailed(projectId, versionId, filePath, "DOC_EXTRACT", "empty content");
                        return;
                    }
                    CompletableFuture.runAsync(() ->
                            support.vectorizeContent(projectId, versionId, "DOC", doc.getFilePath(), content));
                    try {
                        String docContent = support.truncate(content, DOC_CONTENT_LIMIT);
                        DocUnderstandingAgent.BusinessFactExtraction extraction =
                                support.cachedExtract("doc", docContent, () -> {
                                    agentCallCounter.increment();
                                    return docUnderstandingAgent.extractBusinessFacts(projectId, docContent, doc.getFilePath());
                                }, DocUnderstandingAgent.BusinessFactExtraction.class,
                                e -> e == null || AiScanStepSupport.allEmpty(e.getBusinessDomains(), e.getBusinessProcesses(),
                                        e.getBusinessObjects(), e.getBusinessRules(), e.getRoles(),
                                        e.getFeatures(), e.getStatusTransitions()));
                        int count = persistBusinessFacts(projectId, versionId, doc, extraction);
                        factCount.addAndGet(count);
                        buildBusinessGraph(projectId, versionId, doc, extraction);
                        support.upsertClaimDrafts(projectId, versionId,
                                docUnderstandingAgent.toClaimDrafts(projectId, versionId, extraction, doc.getFilePath()));
                        // P2 修复：抽取成功后更新状态为 PARSED
                        doc.setParseStatus("PARSED");
                        doc.setFactCount(count);
                        doc.setParsedAt(java.time.LocalDateTime.now());
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        documentRepository.updateById(doc);
                        support.markExtractDone(projectId, versionId, filePath, "DOC_EXTRACT",
                                "facts=" + count);
                    } catch (Exception e) {
                        log.warn("Doc extract failed for doc {}: {}", doc.getId(), e.getMessage());
                        doc.setParseStatus("FAILED");
                        doc.setErrorMessage(e.getMessage());
                        doc.setUpdatedAt(java.time.LocalDateTime.now());
                        try {
                            documentRepository.updateById(doc);
                        } catch (Exception updateEx) {
                            log.warn("Failed to update doc status to FAILED: {}", doc.getId(), updateEx);
                        }
                        support.markExtractFailed(projectId, versionId, filePath, "DOC_EXTRACT", e.getMessage());
                    }
                }, support.getDocExtractExecutor()));
            }

            // 等待所有文档处理完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            int totalFacts = factCount.get();
            log.info("AI_DOC_EXTRACT completed: versionId={}, factCount={}, docCount={}",
                    versionId, totalFacts, docs.size());
            String summary = buildDocExtractSummary(totalFacts, docs.size());
            support.completeTask(task, summary, null);
            return StepExecutionResult.builder().success(true).message(summary)
                    .processedCount(totalFacts).build();
        } catch (Exception e) {
            log.error("AI_DOC_EXTRACT failed: versionId={}", versionId, e);
            support.completeTask(task, null, e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    /**
     * P1-B：区分"没扫到"与"没开扫"。文档数或事实数为 0 时给出显式提示，
     * 便于在扫描任务列表中定位业务图谱为空的原因（呼应"不静默截断"原则）。
     */
    private String buildDocExtractSummary(int factCount, int docCount) {
        if (docCount == 0) {
            return "⚠ 未发现任何文档 —— 业务事实 0 条。请确认 scanScope 含 DOC_PARSE 且项目已配置产品/需求文档";
        }
        if (factCount == 0) {
            return "⚠ 扫描文档 " + docCount + " 个，但未抽取到业务事实 —— 可能文档无业务语义或 LLM 未返回内容";
        }
        return "AI 抽取业务事实 " + factCount + " 条，扫描文档 " + docCount + " 个";
    }

    private int persistBusinessFacts(String projectId, String versionId, Document doc,
                                     DocUnderstandingAgent.BusinessFactExtraction extraction) {
        if (extraction == null) {
            return 0;
        }
        // 收集本批次所有 Fact，一次性 batchUpsert（替代逐条 upsert 的远程 DB 往返）
        List<Fact> facts = new ArrayList<>();
        if (extraction.getBusinessDomains() != null) {
            for (DocUnderstandingAgent.BusinessDomain domain : extraction.getBusinessDomains()) {
                String key = "domain:" + support.nonBlank(domain.getName(), domain.getDescription());
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_DOMAIN", key, domain.getName(),
                        doc.getFilePath(), domain, domain.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessProcesses() != null) {
            for (DocUnderstandingAgent.BusinessProcess process : extraction.getBusinessProcesses()) {
                String key = process.getKey() != null ? process.getKey()
                        : "process:" + process.getName();
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_PROCESS", key, process.getName(),
                        doc.getFilePath(), process, process.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessObjects() != null) {
            for (DocUnderstandingAgent.BusinessObject obj : extraction.getBusinessObjects()) {
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_OBJECT", "object:" + obj.getName(),
                        obj.getName(), doc.getFilePath(), obj, obj.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getBusinessRules() != null) {
            for (DocUnderstandingAgent.BusinessRule rule : extraction.getBusinessRules()) {
                String key = "rule:" + support.nonBlank(rule.getName(), rule.getExpression());
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_RULE", key, rule.getName(),
                        doc.getFilePath(), rule, rule.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getRoles() != null) {
            for (String role : extraction.getRoles()) {
                if (role == null || role.isBlank()) {
                    continue;
                }
                support.addFact(facts, support.buildFact(projectId, versionId, "BUSINESS_ROLE", "role:" + role,
                        role, doc.getFilePath(), role, 0.7, SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getStatusTransitions() != null) {
            for (DocUnderstandingAgent.StatusTransition transition : extraction.getStatusTransitions()) {
                String key = "transition:" + support.nonBlank(transition.getBusinessObject(), "object")
                        + ":" + support.nonBlank(transition.getFromStatus(), "?")
                        + "->" + support.nonBlank(transition.getToStatus(), "?")
                        + ":" + support.nonBlank(transition.getTrigger(), "");
                String name = support.nonBlank(transition.getBusinessObject(), "对象") + " "
                        + support.nonBlank(transition.getFromStatus(), "?") + " -> "
                        + support.nonBlank(transition.getToStatus(), "?");
                support.addFact(facts, support.buildFact(projectId, versionId, "STATUS_TRANSITION", key, name,
                        doc.getFilePath(), transition, transition.getConfidence(), SourceType.DOC_AI.name()));
            }
        }
        if (extraction.getFeatures() != null) {
            for (String feature : extraction.getFeatures()) {
                if (feature == null || feature.isBlank()) {
                    continue;
                }
                support.addFact(facts, support.buildFact(projectId, versionId, "FEATURE", "feature:" + feature,
                        feature, doc.getFilePath(), feature, 0.7, SourceType.DOC_AI.name()));
            }
        }
        return support.saveAiFactsBatch(facts);
    }

    private void buildBusinessGraph(String projectId, String versionId, Document doc,
                                    DocUnderstandingAgent.BusinessFactExtraction extraction) {
        if (businessGraphBuilder == null || extraction == null) {
            return;
        }
        try {
            int beforeNodes = countGraphNodes(projectId, versionId);
            int beforeEdges = countGraphEdges(projectId, versionId);
            businessGraphBuilder.buildBusinessGraph(projectId, versionId, extraction, doc.getFilePath());
            int afterNodes = countGraphNodes(projectId, versionId);
            int afterEdges = countGraphEdges(projectId, versionId);
            graphNodeCounter.increment(afterNodes - beforeNodes);
            graphEdgeCounter.increment(afterEdges - beforeEdges);
        } catch (Exception e) {
            log.warn("Business graph build failed for doc {}: {}", doc.getId(), e.getMessage());
        }
    }

    private String readDocContent(Document doc) {
        if (doc.getFilePath() == null || doc.getFilePath().isBlank()) {
            log.warn("readDocContent: filePath is null/blank for doc {} ({})", doc.getId(), doc.getDocName());
            return null;
        }
        try {
            Path filePath = Path.of(doc.getFilePath());
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                log.warn("readDocContent: file not found or not regular: {} (doc={})", doc.getFilePath(), doc.getId());
                return null;
            }
            return documentContentService.readText(doc.getFilePath());
        } catch (Exception e) {
            log.warn("readDocContent: failed to read {}: {}", doc.getFilePath(), e.getMessage());
            return null;
        }
    }

    /**
     * 统计指定版本的图谱节点数（用于 Prometheus 指标计算差值）
     */
    private int countGraphNodes(String projectId, String versionId) {
        try {
            return (int) neo4jGraphDao.countNodes(projectId, versionId, null);
        } catch (Exception e) {
            log.debug("countGraphNodes failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 统计指定版本的图谱边数（用于 Prometheus 指标计算差值）
     */
    private int countGraphEdges(String projectId, String versionId) {
        try {
            return (int) neo4jGraphDao.countEdges(projectId, versionId, null);
        } catch (Exception e) {
            log.debug("countGraphEdges failed: {}", e.getMessage());
            return 0;
        }
    }
}
