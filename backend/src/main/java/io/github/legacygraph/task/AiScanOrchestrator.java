package io.github.legacygraph.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.CodeFactAgent;
import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.agent.FeatureMappingAgent;
import io.github.legacygraph.agent.TestCaseAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.dto.FactExtractionResult;
import io.github.legacygraph.dto.GeneratedTestCase;
import io.github.legacygraph.dto.claim.KnowledgeClaimDraft;
import io.github.legacygraph.entity.Document;
import io.github.legacygraph.entity.Fact;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.entity.TestCase;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.repository.DocumentRepository;
import io.github.legacygraph.repository.FactRepository;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.repository.ScanTaskRepository;
import io.github.legacygraph.repository.TestCaseRepository;
import io.github.legacygraph.service.DocumentContentService;
import io.github.legacygraph.service.GapFinderService;
import io.github.legacygraph.service.KnowledgeClaimService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final CodeFactAgent codeFactAgent;
    private final BusinessGraphBuilder businessGraphBuilder;
    private final ObjectMapper objectMapper;
    private final DocumentContentService documentContentService = new DocumentContentService();
    private final KnowledgeClaimService knowledgeClaimService;
    private final GapFinderService gapFinderService;

    /** 测试生成的高价值节点上限，避免编排耗时过长 */
    private static final int MAX_TEST_GEN_NODES = 20;
    /** 审核准备节点上限 */
    private static final int MAX_REVIEW_NODES = 50;
    /** 代码事实抽取的类节点上限，避免 LLM 调用过多 */
    private static final int MAX_CODE_EXTRACT_NODES = 30;
    /** 单个代码文件读取上限（字符），配合 truncate 控制 prompt 体积 */
    private static final int CODE_CONTENT_LIMIT = 8000;

    public AiScanOrchestrator(ScanTaskRepository scanTaskRepository,
                              DocumentRepository documentRepository,
                              FactRepository factRepository,
                              ReviewRecordRepository reviewRecordRepository,
                              TestCaseRepository testCaseRepository,
                              Neo4jGraphDao neo4jGraphDao,
                              DocUnderstandingAgent docUnderstandingAgent,
                              FeatureMappingAgent featureMappingAgent,
                              TestCaseAgent testCaseAgent,
                              CodeFactAgent codeFactAgent,
                              BusinessGraphBuilder businessGraphBuilder,
                              ObjectMapper objectMapper,
                              KnowledgeClaimService knowledgeClaimService,
                              GapFinderService gapFinderService) {
        this.scanTaskRepository = scanTaskRepository;
        this.documentRepository = documentRepository;
        this.factRepository = factRepository;
        this.reviewRecordRepository = reviewRecordRepository;
        this.testCaseRepository = testCaseRepository;
        this.neo4jGraphDao = neo4jGraphDao;
        this.docUnderstandingAgent = docUnderstandingAgent;
        this.featureMappingAgent = featureMappingAgent;
        this.testCaseAgent = testCaseAgent;
        this.codeFactAgent = codeFactAgent;
        this.businessGraphBuilder = businessGraphBuilder;
        this.objectMapper = objectMapper;
        this.knowledgeClaimService = knowledgeClaimService;
        this.gapFinderService = gapFinderService;
    }

    /**
     * 执行扫描后 AI 编排。未启用 AI 时直接返回。
     */
    public void orchestrate(String projectId, String versionId, AiScanConfig config) {
        if (config == null || !config.isEnableAi()) {
            log.info("AI orchestration skipped (enableAi=false): versionId={}", versionId);
            // P1-B：创建结构化跳过任务，确保"未开启 AI"在 scan_task 列表中可见，
            // 避免"没扫到"与"没开扫"无法区分（呼应"不静默截断"原则）。
            ScanTask skipTask = createTask(projectId, versionId, "AI_ORCHESTRATION", "AI 编排");
            completeTask(skipTask,
                    "⚠ AI 编排已跳过：enableAi=false（未启用 AI 归纳）。"
                    + "业务图谱（业务域/流程/功能/对象/角色）将不会生成。"
                    + "如需生成业务图谱，请在 scanScope 中设置 enableAi=true。",
                    null);
            // completeTask 会把 ⚠ 摘要标为 WARNING，便于任务列表筛选跳过原因
            // 即使 AI 未启用，仍执行确定性缺口扫描（不依赖 LLM）
            if (gapFinderService != null) {
                try {
                    gapFinderService.scanGaps(projectId, versionId);
                } catch (Exception gapEx) {
                    log.warn("Knowledge gap scan failed (non-blocking): versionId={}, err={}",
                            versionId, gapEx.getMessage());
                }
            }
            return;
        }
        log.info("Starting AI orchestration: projectId={}, versionId={}, config={}",
                projectId, versionId, config);

        runDocExtract(projectId, versionId);
        runCodeExtract(projectId, versionId);
        runFeatureCodeMapping(projectId, versionId);
        runFeatureMapping(projectId, versionId);
        if (config.isAutoGenerateTestCase()) {
            runTestGenerate(projectId, versionId);
        }
        runReviewPrepare(projectId, versionId, config.getMinConfidence());
        runKnowledgeGapScan(projectId, versionId);

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
            for (Document doc : docs) {
                String content = readDocContent(doc);
                if (content == null || content.isBlank()) {
                    continue;
                }
                try {
                    DocUnderstandingAgent.BusinessFactExtraction extraction =
                            docUnderstandingAgent.extractBusinessFacts(projectId,
                                    truncate(content, 8000), doc.getFilePath());
                    factCount += persistBusinessFacts(projectId, versionId, doc, extraction);
                    buildBusinessGraph(projectId, versionId, doc, extraction);
                    upsertClaimDrafts(projectId, versionId,
                            docUnderstandingAgent.toClaimDrafts(projectId, versionId, extraction, doc.getFilePath()));
                } catch (Exception e) {
                    log.warn("Doc extract failed for doc {}: {}", doc.getId(), e.getMessage());
                }
            }
            completeTask(task, buildDocExtractSummary(factCount, docs.size()), null);
        } catch (Exception e) {
            log.error("AI_DOC_EXTRACT failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
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

    // ==================== AI_CODE_EXTRACT ====================

    /**
     * P0-A：从代码抽取业务事实，让"无文档"项目也能产出业务/功能节点。
     *
     * <p>复用 {@link CodeFactAgent} 对 Service/Controller 类源码做 LLM 语义理解，
     * 抽取结果桥接为 {@link DocUnderstandingAgent.BusinessFactExtraction}（填充 features），
     * 复用 {@link BusinessGraphBuilder#buildBusinessGraph} 落图，避免重复的落库映射代码。</p>
     */
    private void runCodeExtract(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_CODE_EXTRACT", "代码业务事实抽取");
        try {
            // 取 Service/Controller 类节点作为业务语义最集中的抽取对象
            List<GraphNode> codeNodes = new ArrayList<>();
            codeNodes.addAll(neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Service.name(), null, null, null, MAX_CODE_EXTRACT_NODES));
            codeNodes.addAll(neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Controller.name(), null, null, null, MAX_CODE_EXTRACT_NODES));

            if (codeNodes.isEmpty()) {
                completeTask(task, "⚠ 无 Service/Controller 类节点，跳过代码事实抽取", null);
                return;
            }

            int factCount = 0;
            int processed = 0;
            Set<String> visitedPaths = new HashSet<>();
            for (GraphNode node : codeNodes) {
                if (processed >= MAX_CODE_EXTRACT_NODES) {
                    break;
                }
                String content = readCodeContent(node, visitedPaths);
                if (content == null || content.isBlank()) {
                    continue;
                }
                processed++;
                try {
                    FactExtractionResult result = codeFactAgent.extractFacts(
                            projectId, truncate(content, CODE_CONTENT_LIMIT), node.getSourcePath());
                    factCount += persistAndBuildCodeFacts(projectId, versionId, node, result);
                } catch (Exception e) {
                    log.warn("Code fact extract failed for node {}: {}", node.getNodeKey(), e.getMessage());
                }
            }
            String summary = factCount > 0
                    ? "AI 从代码抽取业务事实 " + factCount + " 条，分析类节点 " + processed + " 个"
                    : "⚠ 分析类节点 " + processed + " 个，未抽取到业务事实";
            completeTask(task, summary, null);
        } catch (Exception e) {
            log.error("AI_CODE_EXTRACT failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }

    /**
     * 将代码事实抽取结果落 Fact 表，并桥接为业务图谱功能节点。
     */
    private int persistAndBuildCodeFacts(String projectId, String versionId, GraphNode node,
                                         FactExtractionResult result) {
        if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
            return 0;
        }
        int count = 0;
        DocUnderstandingAgent.BusinessFactExtraction bridge =
                new DocUnderstandingAgent.BusinessFactExtraction();
        for (FactExtractionResult.FactItem item : result.getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            double confidence = item.getConfidence() != null
                    ? item.getConfidence().doubleValue() : 0.6;
            String key = item.getKey() != null && !item.getKey().isBlank()
                    ? item.getKey() : "code-feature:" + item.getName();
            if (saveAiFact(projectId, versionId, "CODE_FEATURE", key, item.getName(),
                    node.getSourcePath(), item, confidence, SourceType.CODE_AI.name())) {
                count++;
            }
            bridge.getFeatures().add(item.getName());
        }
        // 复用 P0-B 的功能清单落图路径
        if (!bridge.getFeatures().isEmpty() && businessGraphBuilder != null) {
            try {
                businessGraphBuilder.buildBusinessGraph(projectId, versionId, bridge,
                        node.getSourcePath(), SourceType.CODE_AI.name());
            } catch (Exception e) {
                log.warn("Business graph build from code facts failed for node {}: {}",
                        node.getNodeKey(), e.getMessage());
            }
        }
        upsertClaimDrafts(projectId, versionId,
                codeFactAgent.toClaimDrafts(projectId, versionId, result, node.getSourcePath()));
        return count;
    }

    /**
     * 读取代码节点对应的源文件内容。按 sourcePath 去重，避免同文件多节点重复抽取。
     */
    private String readCodeContent(GraphNode node, Set<String> visitedPaths) {
        String path = node.getSourcePath();
        if (path == null || path.isBlank() || !visitedPaths.add(path)) {
            return null;
        }
        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return null;
            }
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Failed to read code content {}: {}", path, e.getMessage());
            return null;
        }
    }

    // ==================== AI_FEATURE_CODE_MAPPING ====================

    /**
     * P1-C：将文档/代码抽取的 Feature 节点按名称相似度映射到已有的 Page/API 实现，
     * 建立 EXPOSED_BY / IMPLEMENTED_BY 边，避免 Feature 成为孤立节点。
     *
     * <p>此前仅手动接口 {@code FactController.extractDocFacts} 会调用 mapFeaturesToCode，
     * 自动扫描路径遗漏该步骤，导致自动抽出的 Feature 与代码断连。此处对齐两条路径。</p>
     */
    private void runFeatureCodeMapping(String projectId, String versionId) {
        ScanTask task = createTask(projectId, versionId, "AI_FEATURE_CODE_MAPPING", "功能到代码实现映射");
        try {
            int featureMappings = businessGraphBuilder.mapFeaturesToCode(projectId, versionId);
            // P2：业务对象 ↔ 数据库表对齐，连通业务层与技术层
            int objectMappings = businessGraphBuilder.mapBusinessObjectsToTables(projectId, versionId);
            int totalMappings = featureMappings + objectMappings;
            if (totalMappings == 0) {
                completeTask(task,
                        "⚠ 未建立 Feature/业务对象技术映射 —— 可能无 Feature/Page/API，"
                        + "或无 BusinessObject/技术实体候选",
                        null);
            } else {
                completeTask(task, "已建立 Feature→Page/API 映射 " + featureMappings
                        + " 条，业务对象技术映射 " + objectMappings + " 条", null);
            }
        } catch (Exception e) {
            log.error("AI_FEATURE_CODE_MAPPING failed: versionId={}", versionId, e);
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
                        doc.getFilePath(), domain, domain.getConfidence(), SourceType.DOC_AI.name())) {
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
                        doc.getFilePath(), process, confidence, SourceType.DOC_AI.name())) {
                    count++;
                }
            }
        }
        if (extraction.getBusinessObjects() != null) {
            for (DocUnderstandingAgent.BusinessObject obj : extraction.getBusinessObjects()) {
                if (saveAiFact(projectId, versionId, "BUSINESS_OBJECT", "object:" + obj.getName(),
                        obj.getName(), doc.getFilePath(), obj, obj.getConfidence(), SourceType.DOC_AI.name())) {
                    count++;
                }
            }
        }
        if (extraction.getBusinessRules() != null) {
            for (DocUnderstandingAgent.BusinessRule rule : extraction.getBusinessRules()) {
                String key = "rule:" + nonBlank(rule.getName(), rule.getExpression());
                if (saveAiFact(projectId, versionId, "BUSINESS_RULE", key, rule.getName(),
                        doc.getFilePath(), rule, rule.getConfidence(), SourceType.DOC_AI.name())) {
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
                        role, doc.getFilePath(), role, 0.7, SourceType.DOC_AI.name())) {
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
                        doc.getFilePath(), transition, transition.getConfidence(), SourceType.DOC_AI.name())) {
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
                        feature, doc.getFilePath(), feature, 0.7, SourceType.DOC_AI.name())) {
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
            // 收集已有 Feature 节点作为映射锚点，避免 LLM 凭空生成映射
            List<GraphNode> features = neo4jGraphDao.queryNodes(projectId, versionId,
                    NodeType.Feature.name(), null, null, null, 100);
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
            // 将已有 Feature 节点作为产品文档上下文传入，让 LLM 以 Feature 为锚点做映射
            request.setProductDoc(features.isEmpty() ? "" : "已有功能点:\n" + summarizeNodes(features));

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
            try {
                GraphEdge edge = createPendingFeatureEdge(projectId, versionId, mapping);
                if (edge != null) {
                    persisted++;
                    if (createMappingReviewRecord(projectId, versionId, mapping, edge.getId())) {
                        persisted++;
                    }
                } else {
                    log.debug("Skipping review record: no edge created for mapping pageKey={} apiKey={}",
                            mapping.getPageKey(), mapping.getApiKey());
                }
            } catch (Exception e) {
                log.warn("Failed to persist AI feature mapping edge: {}", e.getMessage());
            }
        }
        upsertClaimDrafts(projectId, versionId,
                featureMappingAgent.toClaimDrafts(projectId, versionId, result));
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
                               String factName, String sourcePath, Object data, double confidence,
                               String sourceType) {
        try {
            Fact fact = new Fact();
            fact.setId(UUID.randomUUID().toString());
            fact.setProjectId(projectId);
            fact.setVersionId(versionId);
            fact.setFactType(factType);
            fact.setFactKey(factKey);
            fact.setFactName(factName);
            fact.setSourceType(sourceType != null ? sourceType : SourceType.DOC_AI.name());
            fact.setSourcePath(sourcePath);
            fact.setNormalizedData(objectMapper.writeValueAsString(data));
            fact.setConfidence(confidence);
            // AI 结果默认 PENDING_CONFIRM，不能直接 CONFIRMED
            fact.setStatus("PENDING_CONFIRM");
            fact.setCreatedBy("ai-orchestrator");
            fact.setCreatedAt(LocalDateTime.now());
            fact.setUpdatedAt(LocalDateTime.now());
            factRepository.upsert(fact);
            return true;
        } catch (Exception e) {
            log.warn("Failed to save AI fact {}: {}", factKey, e.getMessage());
            return false;
        }
    }

    private String readDocContent(Document doc) {
        if (doc.getFilePath() == null || doc.getFilePath().isBlank()) {
            return null;
        }
        try {
            Path filePath = Path.of(doc.getFilePath());
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return null;
            }
            return documentContentService.readText(doc.getFilePath());
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
        if (error != null) {
            task.setTaskStatus("FAILED");
        } else if (summary != null && summary.startsWith("⚠")) {
            // P1-B：空结果/跳过等非错误但有告警的情况，标记为 WARNING
            // 与 SUCCESS 区分，便于在扫描任务列表中快速定位"业务图谱为空"的原因
            task.setTaskStatus("WARNING");
        } else {
            task.setTaskStatus("SUCCESS");
        }
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        scanTaskRepository.updateById(task);
    }

    // ==================== Knowledge Claim 桥接 ====================

    private void upsertClaimDrafts(String projectId, String versionId,
                                   List<KnowledgeClaimDraft> drafts) {
        if (knowledgeClaimService == null || drafts == null || drafts.isEmpty()) {
            return;
        }
        try {
            knowledgeClaimService.upsertDrafts(drafts);
        } catch (Exception e) {
            log.warn("Knowledge claim upsert failed: projectId={}, versionId={}, err={}",
                    projectId, versionId, e.getMessage());
        }
    }

    /**
     * 扫描结束后执行知识缺口发现（确定性 + LLM 增强）。
     */
    private void runKnowledgeGapScan(String projectId, String versionId) {
        if (gapFinderService == null) {
            log.debug("GapFinderService not available, skipping gap scan: versionId={}", versionId);
            return;
        }
        ScanTask task = createTask(projectId, versionId, "AI_GAP_FINDING", "知识缺口扫描");
        try {
            GapFinderService.GapScanResult result = gapFinderService.scanGaps(projectId, versionId);
            completeTask(task, "生成知识缺口 " + result.getCreated()
                    + " 条，重新打开 " + result.getReopened()
                    + " 条，保持 " + result.getUnchanged() + " 条", null);
        } catch (Exception e) {
            log.error("AI_GAP_FINDING failed: versionId={}", versionId, e);
            completeTask(task, null, e.getMessage());
        }
    }
}
