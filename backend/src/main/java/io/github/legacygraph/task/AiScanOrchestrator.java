package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.agent.FeatureMappingAgent;
import io.github.legacygraph.agent.TestCaseAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.extractors.DocumentExtractor;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 扫描后 AI 编排器 — Phase 1。
 *
 * <p>扫描成功后，按 {@link AiScanConfig} 开关执行：
 * <ol>
 *   <li>AI_DOC_EXTRACT — 文档业务事实抽取（写入 lg_fact，PENDING_CONFIRM）</li>
 *   <li>AI_FEATURE_MAPPING — Feature → Page/API/Service 映射</li>
 *   <li>AI_TEST_GENERATE — 高价值节点测试用例生成（autoGenerateTestCase 开启时）</li>
 *   <li>AI_REVIEW_PREPARE — 低置信节点生成人工审核任务</li>
 * </ol>
 *
 * <p>所有 AI 结果默认 PENDING_CONFIRM，并关联证据，遵循"AI 不能直接作为事实源"的设计原则。
 * 每个子任务独立容错：单步失败不会中断整体编排。</p>
 */
@Slf4j
@Component
public class AiScanOrchestrator {

    private final ScanTaskRepository scanTaskRepository;
    private final DocumentRepository documentRepository;
    private final FactRepository factRepository;
    private final ReviewRecordRepository reviewRecordRepository;
    private final TestCaseRepository testCaseRepository;
    private final Neo4jGraphDao neo4jGraphDao;
    private final DocUnderstandingAgent docUnderstandingAgent;
    private final FeatureMappingAgent featureMappingAgent;
    private final TestCaseAgent testCaseAgent;
    private final BusinessGraphBuilder businessGraphBuilder;
    private final ObjectMapper objectMapper;

    /** 测试生成的高价值节点上限，避免编排耗时过长 */
    private static final int MAX_TEST_GEN_NODES = 20;
    /** 审核准备节点上限 */
    private static final int MAX_REVIEW_NODES = 50;

    public AiScanOrchestrator(ScanTaskRepository scanTaskRepository,
                              DocumentRepository documentRepository,
                              FactRepository factRepository,
                              ReviewRecordRepository reviewRecordRepository,
                              TestCaseRepository testCaseRepository,
                              Neo4jGraphDao neo4jGraphDao,
                              DocUnderstandingAgent docUnderstandingAgent,
                              FeatureMappingAgent featureMappingAgent,
                              TestCaseAgent testCaseAgent,
                              BusinessGraphBuilder businessGraphBuilder,
                              ObjectMapper objectMapper) {
        this.scanTaskRepository = scanTaskRepository;
        this.documentRepository = documentRepository;
        this.factRepository = factRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.testCaseRepository = testCaseRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.docUnderstandingAgent = docUnderstandingAgent;
        this.featureMappingAgent = featureMappingAgent;
        this.testCaseAgent = testCaseAgent;
        this.businessGraphBuilder = businessGraphBuilder;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行扫描后 AI 编排。未启用 AI 时直接返回。
     */
    public void orchestrate(String projectId, String versionId, AiScanConfig config) {
        if (config == null || !config.isEnableAi()) {
            log.info("AI orchestration skipped (enableAi=false): versionId={}", versionId);
            return;
        }
        log.info("Starting AI orchestration: projectId={}, versionId={}, config={}",
                projectId, versionId, config);

        runDocExtract(projectId, versionId);
        runFeatureMapping(projectId, versionId);
        if (config.isAutoGenerateTestCase()) {
            runTestGenerate(projectId, versionId);
        }
        runReviewPrepare(projectId, versionId, config.getMinConfidence());

        log.info("AI orchestration completed: versionId={}", versionId);
    }

    // ==================== AI_DOC_EXTRACT ====================

    private void runDocExtract(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_DOC_EXTRACT", "文档业务事实抽取");
        try {
            List<Document> docs = documentRepository.selectList(
                    new LambdaQueryWrapper<Document>()
                            .eq(Document::getProjectId, projectId)
                            .eq(Document::getVersionId, versionId));
            int factCount = 0;
            DocumentExtractor extractor = new DocumentExtractor();
            for (Document doc : docs) {
                String content = readDocContent(doc, extractor);
                if (content == null || content.isBlank()) {
                    continue;
                }
                try {
                    DocUnderstandingAgent.BusinessFactExtraction extraction =
                            docUnderstandingAgent.extractBusinessFacts(projectId,
                                    truncate(content, 8000), doc.getFilePath());
                    factCount += persistBusinessFacts(projectId, versionId, doc, extraction);
                    buildBusinessGraph(projectId, versionId, doc, extraction);
                } catch (Exception e) {
                    log.warn("Doc extract failed for doc {}: {}", doc.getId(), e.getMessage());
                }
            }
            completeTask(task, "AI 抽取业务事实 " + factCount + " 条，扫描文档 " + docs.size() + " 个", null);
        } catch (Exception e) {
            log.error("AI_DOC_EXTRACT failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    private int persistBusinessFacts(String projectId, String versionId, Document doc,
                                     DocUnderstandingAgent.BusinessFactExtraction extraction) {
        if (extraction == null) {
            return 0;
        }
        int count = 0;
        if (extraction.getBusinessDomains() != null) {
            for (DocUnderstandingAgent.BusinessDomain domain : extraction.getBusinessDomains()) {
                String key = "domain:" + nonBlank(domain.getName(), domain.getDescription());
                if (saveAiFact(projectId, versionId, "BUSINESS_DOMAIN", key, domain.getName(),
                        doc.getFilePath(), domain, domain.getConfidence())) {
                    count++;
                }
            }
        }
        if (extraction.getBusinessProcesses() != null) {
            for (DocUnderstandingAgent.BusinessProcess process : extraction.getBusinessProcesses()) {
                String key = process.getKey() != null ? process.getKey()
                        : "process:" + process.getName();
                double confidence = process.getConfidence();
                if (saveAiFact(projectId, versionId, "BUSINESS_PROCESS", key, process.getName(),
                        doc.getFilePath(), process, confidence)) {
                    count++;
                }
            }
        }
        if (extraction.getBusinessObjects() != null) {
            for (DocUnderstandingAgent.BusinessObject obj : extraction.getBusinessObjects()) {
                if (saveAiFact(projectId, versionId, "BUSINESS_OBJECT", "object:" + obj.getName(),
                        obj.getName(), doc.getFilePath(), obj, obj.getConfidence())) {
                    count++;
                }
            }
        }
        if (extraction.getBusinessRules() != null) {
            for (DocUnderstandingAgent.BusinessRule rule : extraction.getBusinessRules()) {
                String key = "rule:" + nonBlank(rule.getName(), rule.getExpression());
                if (saveAiFact(projectId, versionId, "BUSINESS_RULE", key, rule.getName(),
                        doc.getFilePath(), rule, rule.getConfidence())) {
                    count++;
                }
            }
        }
        if (extraction.getRoles() != null) {
            for (String role : extraction.getRoles()) {
                if (role == null || role.isBlank()) {
                    continue;
                }
                if (saveAiFact(projectId, versionId, "BUSINESS_ROLE", "role:" + role,
                        role, doc.getFilePath(), role, 0.7)) {
                    count++;
                }
            }
        }
        if (extraction.getStatusTransitions() != null) {
            for (DocUnderstandingAgent.StatusTransition transition : extraction.getStatusTransitions()) {
                String key = "transition:" + nonBlank(transition.getBusinessObject(), "object")
                        + ":" + nonBlank(transition.getFromStatus(), "?")
                        + "->" + nonBlank(transition.getToStatus(), "?")
                        + ":" + nonBlank(transition.getTrigger(), "");
                String name = nonBlank(transition.getBusinessObject(), "对象") + " "
                        + nonBlank(transition.getFromStatus(), "?") + " -> "
                        + nonBlank(transition.getToStatus(), "?");
                if (saveAiFact(projectId, versionId, "STATUS_TRANSITION", key, name,
                        doc.getFilePath(), transition, transition.getConfidence())) {
                    count++;
                }
            }
        }
        if (extraction.getFeatures() != null) {
            for (String feature : extraction.getFeatures()) {
                if (feature == null || feature.isBlank()) {
                    continue;
                }
                if (saveAiFact(projectId, versionId, "FEATURE", "feature:" + feature,
                        feature, doc.getFilePath(), feature, 0.7)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void buildBusinessGraph(String projectId, String versionId, Document doc,
                                    DocUnderstandingAgent.BusinessFactExtraction extraction) {
        if (businessGraphBuilder == null || extraction == null) {
            return;
        }
        try {
            businessGraphBuilder.buildBusinessGraph(projectId, versionId, extraction, doc.getFilePath());
        } catch (Exception e) {
            log.warn("Business graph build failed for doc {}: {}", doc.getId(), e.getMessage());
        }
    }

    // ==================== AI_FEATURE_MAPPING ====================

    private void runFeatureMapping(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_FEATURE_MAPPING", "功能映射对齐");
        try {
            // 收集前端页面节点与后端 API 节点作为映射输入
            List<GraphNode> pages = neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Page.name(), null, null, null, 50);
            List<GraphNode> apis = neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.ApiEndpoint.name(), null, null, null, 50);

            if (pages.isEmpty() && apis.isEmpty()) {
                completeTask(task, "无可映射的页面/接口节点，跳过", null);
                return;
            }

            FeatureMappingAgent.MappingRequest request = new FeatureMappingAgent.MappingRequest();
            request.setProjectId(projectId);
            request.setVueCode(summarizeNodes(pages));
            request.setApiDefinitions(summarizeNodes(apis));
            request.setControllerCode("");
            request.setPermissionInfo("");
            request.setProductDoc("");

            FeatureMappingAgent.MappingResult result = featureMappingAgent.mapFeatures(request);
            int mappingCount = result != null && result.getMappings() != null
                    ? result.getMappings().size() : 0;
            int persistedCount = persistFeatureMappings(projectId, versionId, result);
            completeTask(task, "AI 生成功能映射 " + mappingCount + " 条，落地待确认关系/审核 " + persistedCount + " 条", null);
        } catch (Exception e) {
            log.error("AI_FEATURE_MAPPING failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    private int persistFeatureMappings(String projectId, String versionId,
                                       FeatureMappingAgent.MappingResult result) {
        if (result == null || result.getMappings() == null) {
            return 0;
        }
        int persisted = 0;
        for (FeatureMappingAgent.Mapping mapping : result.getMappings()) {
            if (mapping == null) {
                continue;
            }
            String targetId = mappingTargetId(mapping);
            try {
                GraphEdge edge = createPendingFeatureEdge(projectId, versionId, mapping);
                if (edge != null) {
                    targetId = edge.getId();
                    persisted++;
                }
            } catch (Exception e) {
                log.warn("Failed to persist AI feature mapping edge {}: {}", targetId, e.getMessage());
            }
            if (createMappingReviewRecord(projectId, versionId, mapping, targetId)) {
                persisted++;
            }
        }
        return persisted;
    }

    private GraphEdge createPendingFeatureEdge(String projectId, String versionId,
                                               FeatureMappingAgent.Mapping mapping) throws Exception {
        if (mapping.getPageKey() == null || mapping.getPageKey().isBlank()
                || mapping.getApiKey() == null || mapping.getApiKey().isBlank()) {
            return null;
        }
        GraphNode page = neo4jGraphDao.findNode(projectId, versionId, NodeType.Page.name(), mapping.getPageKey())
                .orElse(null);
        GraphNode api = neo4jGraphDao.findNode(projectId, versionId, NodeType.ApiEndpoint.name(), mapping.getApiKey())
                .orElse(null);
        if (page == null || api == null) {
            return null;
        }

        String edgeKey = "ai-feature:" + mapping.getPageKey() + "->" + mapping.getApiKey();
        // 去重：已存在则直接返回
        Optional<GraphEdge> existing = neo4jGraphDao.findEdge(projectId, versionId,
                page.getId(), api.getId(), EdgeType.CALLS.name(), edgeKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        GraphEdge edge = new GraphEdge();
        edge.setId(UUID.randomUUID().toString());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(page.getId());
        edge.setToNodeId(api.getId());
        edge.setEdgeType(EdgeType.CALLS.name());
        edge.setEdgeKey(edgeKey);
        edge.setSourceType("AI_FEATURE_MAPPING");
        edge.setConfidence(BigDecimal.valueOf(normalizeConfidence(mapping.getConfidence())));
        edge.setStatus("PENDING_CONFIRM");
        edge.setProperties(objectMapper.writeValueAsString(mapping));
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        neo4jGraphDao.createEdge(edge);
        return edge;
    }

    // ==================== AI_TEST_GENERATE ====================

    private void runTestGenerate(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_TEST_GENERATE", "测试用例生成");
        try {
            List<GraphNode> apiNodes = neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.ApiEndpoint.name(), null, null, null, MAX_TEST_GEN_NODES);
            int generated = 0;
            for (GraphNode node : apiNodes) {
                try {
                    TestCaseAgent.TestGenerationRequest req = new TestCaseAgent.TestGenerationRequest();
                    req.setProjectId(projectId);
                    req.setFeatureKey(node.getNodeKey());
                    req.setFeatureName(node.getNodeName());
                    req.setApiEndpoint(node.getNodeKey());
                    req.setHttpMethod("GET");

                    List<GeneratedTestCase> cases = testCaseAgent.generateTestCases(req);
                    for (GeneratedTestCase gen : cases) {
                        persistTestCase(projectId, versionId, node, gen, generated);
                        generated++;
                    }
                } catch (Exception e) {
                    log.warn("Test generation failed for node {}: {}", node.getNodeKey(), e.getMessage());
                }
            }
            completeTask(task, "AI 生成测试用例 " + generated + " 条", null);
        } catch (Exception e) {
            log.error("AI_TEST_GENERATE failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    private void persistTestCase(String projectId, String versionId, GraphNode node,
                                 GeneratedTestCase gen, int index) {
        try {
            TestCase tc = new TestCase();
            tc.setProjectId(projectId);
            tc.setVersionId(versionId);
            tc.setCaseCode("AI-TC-" + versionId + "-" + index);
            tc.setCaseName(gen.getCaseName() != null ? gen.getCaseName() : node.getNodeName() + " 测试");
            tc.setCaseType(gen.getCaseType() != null ? gen.getCaseType().name() : "API");
            tc.setScenario(nonBlank(gen.getFeatureKey(), node.getNodeKey()));
            tc.setTargetNodeId(node.getId());
            tc.setPriority("MEDIUM");
            tc.setPreconditions(gen.getPreconditions() != null ? String.join("\n", gen.getPreconditions()) : "");
            tc.setSteps(buildStructuredSteps(gen));
            tc.setExpectedResult(buildExpectedResult(gen));
            tc.setConfidence(BigDecimal.valueOf(0.7));
            tc.setStatus("ENABLED");
            tc.setGeneratedBy("LLM");
            tc.setCreatedAt(LocalDateTime.now());
            tc.setUpdatedAt(LocalDateTime.now());
            testCaseRepository.insert(tc);
        } catch (Exception e) {
            log.warn("Failed to persist generated test case: {}", e.getMessage());
        }
    }

    private String buildStructuredSteps(GeneratedTestCase gen) throws Exception {
        StringBuilder steps = new StringBuilder();
        if (gen.getSteps() != null && !gen.getSteps().isEmpty()) {
            steps.append(String.join("\n", gen.getSteps()));
        }
        if (gen.getRequest() != null && !gen.getRequest().isEmpty()) {
            if (!steps.isEmpty()) {
                steps.append("\n");
            }
            steps.append("REQUEST ").append(objectMapper.writeValueAsString(gen.getRequest()));
        }
        if (gen.getNeedHumanInput() != null && !gen.getNeedHumanInput().isEmpty()) {
            if (!steps.isEmpty()) {
                steps.append("\n");
            }
            steps.append("NEED_HUMAN_INPUT ").append(String.join("; ", gen.getNeedHumanInput()));
        }
        return steps.toString();
    }

    private String buildExpectedResult(GeneratedTestCase gen) throws Exception {
        if (gen.getAssertions() == null || gen.getAssertions().isEmpty()) {
            return "验证接口返回符合预期";
        }
        return objectMapper.writeValueAsString(gen.getAssertions());
    }

    // ==================== AI_REVIEW_PREPARE ====================

    private void runReviewPrepare(String projectId, String versionId, double minConfidence) {
        ScanTask task = createTask(projectId, versionId, "AI_REVIEW_PREPARE", "低置信节点审核准备");
        try {
            // 拉取该版本节点，筛选低置信节点生成审核任务
            List<GraphNode> nodes = neo4jGraphDao.queryNodes(projectId, versionId,
                    null, null, null, null, MAX_REVIEW_NODES * 4);
            int created = 0;
            for (GraphNode node : nodes) {
                if (created >= MAX_REVIEW_NODES) {
                    break;
                }
                double conf = node.getConfidence() != null ? node.getConfidence().doubleValue() : 0.0;
                if (conf >= minConfidence) {
                    continue;
                }
                if (createReviewRecord(projectId, versionId, node, conf)) {
                    created++;
                }
            }
            completeTask(task, "生成低置信审核任务 " + created + " 条（阈值 " + minConfidence + "）", null);
        } catch (Exception e) {
            log.error("AI_REVIEW_PREPARE failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    private boolean createReviewRecord(String projectId, String versionId, GraphNode node, double confidence) {
        try {
            // 去重：同一目标已有待审核记录则跳过
            long exists = reviewRecordRepository.selectCount(
                    new LambdaQueryWrapper<ReviewRecord>()
                            .eq(ReviewRecord::getProjectId, projectId)
                            .eq(ReviewRecord::getTargetId, node.getId())
                            .eq(ReviewRecord::getStatus, "PENDING"));
            if (exists > 0) {
                return false;
            }
            ReviewRecord record = new ReviewRecord();
            record.setId(UUID.randomUUID().toString());
            record.setProjectId(projectId);
            record.setVersionId(versionId);
            record.setTargetType("NODE");
            record.setTargetId(node.getId());
            record.setTargetName(node.getNodeName());
            record.setGraphType(node.getNodeType());
            record.setConfidence(confidence);
            record.setPriority(confidence < 0.3 ? "HIGH" : "MEDIUM");
            record.setStatus("PENDING");
            record.setComment("AI 编排：低置信节点，建议人工审核");
            record.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.insert(record);
            return true;
        } catch (Exception e) {
            log.warn("Failed to create review record for node {}: {}", node.getId(), e.getMessage());
            return false;
        }
    }

    private boolean createMappingReviewRecord(String projectId, String versionId, FeatureMappingAgent.Mapping mapping, String targetId) {
        try {
            long exists = reviewRecordRepository.selectCount(
                    new LambdaQueryWrapper<ReviewRecord>()
                            .eq(ReviewRecord::getProjectId, projectId)
                            .eq(ReviewRecord::getTargetId, targetId)
                            .eq(ReviewRecord::getStatus, "PENDING"));
            if (exists > 0) {
                return false;
            }
            ReviewRecord record = new ReviewRecord();
            record.setId(UUID.randomUUID().toString());
            record.setProjectId(projectId);
            record.setVersionId(versionId);
            record.setTargetType("EDGE");
            record.setTargetId(targetId);
            record.setTargetName(mappingTargetId(mapping));
            record.setGraphType("AI_FEATURE_MAPPING");
            record.setConfidence(normalizeConfidence(mapping.getConfidence()));
            record.setPriority(mapping.getConfidence() < 0.6 ? "HIGH" : "MEDIUM");
            record.setStatus("PENDING");
            record.setComment("AI 功能映射待确认："
                    + nonBlank(mapping.getBusinessAction(), mappingTargetId(mapping))
                    + "，页面=" + nonBlank(mapping.getPageKey(), "-")
                    + "，接口=" + nonBlank(mapping.getApiKey(), "-"));
            record.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.insert(record);
            return true;
        } catch (Exception e) {
            log.warn("Failed to create review record for mapping {}: {}", mappingTargetId(mapping), e.getMessage());
            return false;
        }
    }

    // ==================== 工具方法 ====================

    private boolean saveAiFact(String projectId, String versionId, String factType, String factKey,
                               String factName, String sourcePath, Object data, double confidence) {
        try {
            long exists = factRepository.selectCount(
                    new LambdaQueryWrapper<Fact>()
                            .eq(Fact::getProjectId, projectId)
                            .eq(Fact::getVersionId, versionId)
                            .eq(Fact::getFactType, factType)
                            .eq(Fact::getFactKey, factKey));
            if (exists > 0) {
                return false;
            }
            Fact fact = new Fact();
            fact.setId(UUID.randomUUID().toString());
            fact.setProjectId(projectId);
            fact.setVersionId(versionId);
            fact.setFactType(factType);
            fact.setFactKey(factKey);
            fact.setFactName(factName);
            fact.setSourceType("DOC_AI");
            fact.setSourcePath(sourcePath);
            fact.setNormalizedData(objectMapper.writeValueAsString(data));
            fact.setConfidence(confidence);
            // AI 结果默认 PENDING_CONFIRM，不能直接 CONFIRMED
            fact.setStatus("PENDING_CONFIRM");
            fact.setCreatedBy("ai-orchestrator");
            fact.setCreatedAt(LocalDateTime.now());
            fact.setUpdatedAt(LocalDateTime.now());
            factRepository.insert(fact);
            return true;
        } catch (Exception e) {
            log.warn("Failed to save AI fact {}: {}", factKey, e.getMessage());
            return false;
        }
    }

    private String readDocContent(Document doc, DocumentExtractor extractor) {
        if (doc.getFilePath() == null || doc.getFilePath().isBlank()) {
            return null;
        }
        try {
            File file = new File(doc.getFilePath());
            if (!file.exists()) {
                return null;
            }
            return extractor.extractText(file);
        } catch (Exception e) {
            log.debug("Failed to read doc content {}: {}", doc.getFilePath(), e.getMessage());
            return null;
        }
    }

    private String summarizeNodes(List<GraphNode> nodes) {
        StringBuilder sb = new StringBuilder();
        for (GraphNode n : nodes) {
            sb.append("- [").append(n.getNodeType()).append("] ")
                    .append(n.getNodeName() != null ? n.getNodeName() : "")
                    .append(" (").append(n.getNodeKey()).append(")\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String mappingTargetId(FeatureMappingAgent.Mapping mapping) {
        return nonBlank(mapping.getPageKey(), "-") + "->" + nonBlank(mapping.getApiKey(), "-");
    }

    private String nonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private double normalizeConfidence(double confidence) {
        if (confidence <= 0) {
            return 0.7;
        }
        return Math.min(1.0, Math.max(0.0, confidence));
    }

    private ScanTask createTask(String projectId, String versionId, String taskType, String taskName) {
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
        return task;
    }

    private void completeTask(ScanTask task, String summary, String error) {
        try {
            if (summary != null) {
                task.setOutputSummary(objectMapper.writeValueAsString(summary));
            }
        } catch (Exception e) {
            task.setOutputSummary("\"" + (summary != null ? summary.replace("\"", "\\\"") : "") + "\"");
        }
        task.setErrorMessage(error);
        task.setTaskStatus(error == null ? "SUCCESS" : "FAILED");
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.updateById(task);
    }
}
