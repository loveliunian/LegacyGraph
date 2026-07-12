package io.github.legacygraph.task.step;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.agent.FeatureMappingAgent;
import io.github.legacygraph.builder.BusinessGraphBuilder;
import io.github.legacygraph.builder.EvidenceGraphWriter;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.ScanStep;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.AiScanConfig;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.ReviewRecord;
import io.github.legacygraph.entity.ScanTask;
import io.github.legacygraph.repository.ReviewRecordRepository;
import io.github.legacygraph.util.IdUtil;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI_FEATURE_MAPPING — Feature → Page/API 映射对齐（LLM 生成 + 落图 + 审核准备）。
 */
@Slf4j
@Component
public class FeatureMappingStep implements AiScanStepExecutor {

    /** LLM 功能映射每批 Feature 数：一次性喂全量 Feature 会导致 LLM 输出截断返回 0 条，分批调用。 */
    private static final int FEATURE_MAPPING_BATCH_SIZE = 40;
    /** 每批 LLM 请求的目标字符上限（与 DOC_CONTENT_LIMIT 一致） */
    private static final int MAPPING_REQUEST_CHAR_LIMIT = 8000;

    private final AiScanStepSupport support;
    private final Neo4jGraphDao neo4jGraphDao;
    private final FeatureMappingAgent featureMappingAgent;
    private final EvidenceGraphWriter evidenceGraphWriter;
    private final ReviewRecordRepository reviewRecordRepository;
    private final ObjectMapper objectMapper;
    private final Counter agentCallCounter;
    private final BusinessGraphBuilder businessGraphBuilder;

    public FeatureMappingStep(AiScanStepSupport support,
                              Neo4jGraphDao neo4jGraphDao,
                              FeatureMappingAgent featureMappingAgent,
                              EvidenceGraphWriter evidenceGraphWriter,
                              ReviewRecordRepository reviewRecordRepository,
                              ObjectMapper objectMapper,
                              @Qualifier("agentCallCounter") Counter agentCallCounter,
                              BusinessGraphBuilder businessGraphBuilder) {
        this.support = support;
        this.neo4jGraphDao = neo4jGraphDao;
        this.featureMappingAgent = featureMappingAgent;
        this.evidenceGraphWriter = evidenceGraphWriter;
        this.reviewRecordRepository = reviewRecordRepository;
        this.objectMapper = objectMapper;
        this.agentCallCounter = agentCallCounter;
        this.businessGraphBuilder = businessGraphBuilder;
    }

    @Override
    public String getStepName() {
        return "AI_FEATURE_MAPPING";
    }

    @Override
    public int getOrder() {
        return 4;
    }

    @Override
    public ScanStep getScanStep() {
        return ScanStep.BUILD_GRAPH;
    }

    @Override
    public StepExecutionResult execute(StepExecutionContext ctx) {
        String projectId = ctx.getProjectId();
        String versionId = ctx.getVersionId();
        ScanTask task = support.createTask(projectId, versionId, "AI_FEATURE_MAPPING", "功能映射对齐");
        try {
            List<GraphNode> features;
            List<GraphNode> pages;
            List<GraphNode> apis;
            if (ctx.isIncremental()) {
                // 增量模式：仅查询 affected=true 的节点，跳过未变更节点（保留其已有边，不删除）
                features = neo4jGraphDao.queryAffectedNodes(projectId, versionId,
                        NodeType.Feature.name());
                pages = neo4jGraphDao.queryAffectedNodes(projectId, versionId,
                        NodeType.Page.name());
                apis = neo4jGraphDao.queryAffectedNodes(projectId, versionId,
                        NodeType.ApiEndpoint.name());
                int totalAffected = features.size() + pages.size() + apis.size();
                if (totalAffected == 0) {
                    // 增量模式下无 affected 节点（没有变更），直接返回成功，不做映射
                    log.info("增量映射：无 affected 节点，跳过 AI_FEATURE_MAPPING（保留未变更节点的已有边）");
                    support.completeTask(task, "增量模式：无 affected 节点，跳过映射", null);
                    return StepExecutionResult.builder().success(true)
                            .message("增量模式：无 affected 节点，跳过映射").build();
                }
                log.info("增量映射：处理 {} 个 affected 节点（跳过未变更节点）", totalAffected);
            } else {
                // 全量模式：查询全部 Feature / Page / ApiEndpoint 节点（limit=0 表示不限）
                features = neo4jGraphDao.queryNodes(projectId, versionId,
                        NodeType.Feature.name(), null, null, null, 0);
                pages = neo4jGraphDao.queryNodes(projectId, versionId,
                        NodeType.Page.name(), null, null, null, 0);
                apis = neo4jGraphDao.queryNodes(projectId, versionId,
                        NodeType.ApiEndpoint.name(), null, null, null, 0);
            }

            if (pages.isEmpty() && apis.isEmpty()) {
                support.completeTask(task, "无可映射的页面/接口节点，跳过", null);
                return StepExecutionResult.builder().success(true)
                        .message("无可映射的页面/接口节点，跳过").build();
            }

            // 构建 nodeKey / nodeName(小写) → 节点 的查找表，落地时按 LLM 返回的 key 或 name 解析
            Map<String, GraphNode> featureMap = buildNodeIndex(features);
            Map<String, GraphNode> pageMap = buildNodeIndex(pages);
            Map<String, GraphNode> apiMap = buildNodeIndex(apis);

            // 分批调 LLM：一次性喂全量 Feature 会导致 LLM 输出截断（实测 347 个一次喂返回 0 条）。
            // 每批只放部分 Feature 作为映射锚点，Page/API 作为目标全量传入。
            String pageSummary = summarizeNodes(pages);
            String apiSummary = summarizeNodes(apis);
            // 构建 Controller 代码上下文（从 API 节点提取 sourcePath/properties）
            String controllerContext = buildControllerContext(apis);
            // 动态计算批次大小：根据目标摘要长度反算每批能容纳的 Feature 数，避免超出 LLM 上下文
            int targetContextLen = pageSummary.length() + apiSummary.length() + controllerContext.length();
            int availableChars = MAPPING_REQUEST_CHAR_LIMIT - targetContextLen - 2000; // 预留 2000 字符给 prompt 模板
            int dynamicBatchSize = calculateDynamicBatchSize(features, availableChars);
            log.info("Feature mapping batch size: dynamic={} (targetContextLen={}, availableChars={})",
                    dynamicBatchSize, targetContextLen, availableChars);

            // 分批并发调 LLM（复用 docExtractExecutor，此时 doc/code 抽取已结束、池空闲），
            // 避免顺序批把耗时拉长。结果用 AtomicInteger 累加。
            AtomicInteger totalMappings = new AtomicInteger(0);
            AtomicInteger totalPersisted = new AtomicInteger(0);
            AtomicInteger batches = new AtomicInteger(0);
            List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
            for (int i = 0; i < features.size(); i += dynamicBatchSize) {
                List<GraphNode> batch = features.subList(i,
                        Math.min(i + dynamicBatchSize, features.size()));
                batchFutures.add(CompletableFuture.runAsync(() -> {
                    FeatureMappingAgent.MappingRequest request = new FeatureMappingAgent.MappingRequest();
                    request.setProjectId(projectId);
                    request.setVueCode(pageSummary);
                    request.setApiDefinitions(apiSummary);
                    request.setControllerCode(controllerContext);
                    request.setPermissionInfo("");
                    String batchFeatureSummary = summarizeNodes(batch);
                    request.setProductDoc("已有功能点:\n" + batchFeatureSummary);
                    try {
                        // 缓存特征映射结果：文档/代码稳定（cachedExtract 命中）时 Features/Pages/APIs 稳定，
                        // 请求内容哈希稳定 → 特征映射命中缓存 → 重扫结果可复现，且省掉这批 LLM 调用。
                        // 修复前 featureMappingAgent 无缓存，LLM 输出在 30-102 间摆动 3.4×，是扫描方差主因。
                        String cacheContent = projectId + "|" + pageSummary + "|" + apiSummary + "|" + batchFeatureSummary;
                        FeatureMappingAgent.MappingResult result = support.cachedExtract(
                                "feature-mapping",
                                cacheContent,
                                () -> mapFeaturesWithRetry(request, featureMappingAgent, agentCallCounter),
                                FeatureMappingAgent.MappingResult.class,
                                r -> r == null || r.getMappings() == null || r.getMappings().isEmpty());
                        int mappingCount = result != null && result.getMappings() != null
                                ? result.getMappings().size() : 0;
                        int persisted = persistFeatureMappings(projectId, versionId, result,
                                featureMap, pageMap, apiMap);
                        totalMappings.addAndGet(mappingCount);
                        totalPersisted.addAndGet(persisted);
                        int b = batches.incrementAndGet();
                        log.info("AI feature mapping batch {}: {} mappings, {} edges persisted",
                                b, mappingCount, persisted);
                    } catch (Exception e) {
                        log.warn("AI feature mapping batch {} failed: {}", batches.get() + 1, e.getMessage());
                    }
                }, support.getDocExtractExecutor()));
            }
            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();

            // 规则映射补充：LLM 映射完成后，用规则匹配补充遗漏的映射
            int ruleFeatureMappings = 0;
            int ruleObjectMappings = 0;
            int ruleDomainMappings = 0;
            int ruleFeatureCandidates = 0;
            int ruleProcessApiMappings = 0;
            int ruleProcessDomainMappings = 0;
            int ruleObjectMapperMappings = 0;
            int ruleRuleMappings = 0;
            if (businessGraphBuilder != null) {
                try {
                    AiScanConfig config = ctx.getConfig();
                    if (config != null) {
                        if (config.isFeatureToCodeMapping()) {
                            ruleFeatureMappings = businessGraphBuilder.mapFeaturesToCode(projectId, versionId);
                        }
                        if (config.isObjectToTableMapping()) {
                            ruleObjectMappings = businessGraphBuilder.mapBusinessObjectsToTables(projectId, versionId);
                        }
                        if (config.isDomainToCodeMapping()) {
                            ruleDomainMappings = businessGraphBuilder.mapBusinessDomainsToCode(projectId, versionId);
                        }
                        if (config.isCrossLanguageFeatureMerge()) {
                            ruleFeatureCandidates = businessGraphBuilder.mergeCrossLanguageFeatures(projectId, versionId);
                        }
                        if (config.isProcessToApiMapping()) {
                            ruleProcessApiMappings = businessGraphBuilder.mapBusinessProcessesToApis(projectId, versionId);
                        }
                        // 评估 §4 矩阵真空区 1/2/3 — 默认开关由 AiScanConfig 控制
                        if (config.isProcessToDomain()) {
                            ruleProcessDomainMappings = businessGraphBuilder.mapBusinessProcessesToDomains(projectId, versionId);
                        }
                        if (config.isObjectToMapperMapping()) {
                            ruleObjectMapperMappings = businessGraphBuilder.mapBusinessObjectsToMappers(projectId, versionId);
                        }
                        if (config.isRuleToRuleMapping()) {
                            ruleRuleMappings = businessGraphBuilder.mapBusinessRulesToRuleNodes(projectId, versionId);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Rule-based mapping failed as supplement: {}", e.getMessage());
                } finally {
                    businessGraphBuilder.clearEmbeddingCache(versionId);
                }
            }

            String summary = "AI 生成功能映射 " + totalMappings.get() + " 条（" + batches.get()
                    + " 批），落地 Feature→Page/API 边 " + totalPersisted.get() + " 条；"
                    + "规则映射补充：Feature→Page/API " + ruleFeatureMappings + " 条，"
                    + "业务对象技术映射 " + ruleObjectMappings + " 条，"
                    + "业务域技术映射 " + ruleDomainMappings + " 条，"
                    + "跨语言 Feature 待确认候选 " + ruleFeatureCandidates + " 组，"
                    + "流程→API 实现映射 " + ruleProcessApiMappings + " 条，"
                    + "流程→业务域 " + ruleProcessDomainMappings + " 条，"
                    + "业务对象→Mapper " + ruleObjectMapperMappings + " 条，"
                    + "业务规则→规则类 " + ruleRuleMappings + " 条";
            support.completeTask(task, summary, null);
            return StepExecutionResult.builder().success(true).message(summary)
                    .processedCount(totalPersisted.get()).build();
        } catch (Exception e) {
            log.error("AI_FEATURE_MAPPING failed: versionId={}", versionId, e);
            support.completeTask(task, null, e.getMessage());
            return StepExecutionResult.builder().success(false).message(e.getMessage()).build();
        }
    }

    private int persistFeatureMappings(String projectId, String versionId,
                                       FeatureMappingAgent.MappingResult result,
                                       Map<String, GraphNode> featureMap,
                                       Map<String, GraphNode> pageMap,
                                       Map<String, GraphNode> apiMap) {
        if (result == null || result.getMappings() == null) {
            return 0;
        }
        int persisted = 0;
        for (FeatureMappingAgent.Mapping mapping : result.getMappings()) {
            if (mapping == null) {
                continue;
            }
            try {
                List<GraphEdge> edges = createPendingFeatureEdges(projectId, versionId, mapping,
                        featureMap, pageMap, apiMap);
                if (!edges.isEmpty()) {
                    persisted += edges.size();
                    // 为首条边建审核记录（一条映射只产一条审核，避免噪声）
                    createMappingReviewRecord(projectId, versionId, mapping, edges.get(0).getId());
                } else {
                    log.debug("No edge created for mapping businessAction={} pageKey={} apiKey={}",
                            mapping.getBusinessAction(), mapping.getPageKey(), mapping.getApiKey());
                }
            } catch (Exception e) {
                log.warn("Failed to persist AI feature mapping edge: {}", e.getMessage());
            }
        }
        // claim 草稿仍落库（审计/后续编译用）；direct 模式下边由上面直接写入 Neo4j
        support.upsertClaimDrafts(projectId, versionId,
                featureMappingAgent.toClaimDrafts(projectId, versionId, result));
        return persisted;
    }

    /**
     * 将一条 LLM 功能映射落地为 Feature→ApiEndpoint / Feature→Page 边。
     */
    private List<GraphEdge> createPendingFeatureEdges(String projectId, String versionId,
                                                      FeatureMappingAgent.Mapping mapping,
                                                      Map<String, GraphNode> featureMap,
                                                      Map<String, GraphNode> pageMap,
                                                      Map<String, GraphNode> apiMap) throws Exception {
        List<GraphEdge> created = new ArrayList<>();
        // 解析 Feature：优先 businessAction，回退 apiKey/pageKey（LLM 偶尔不填 businessAction）
        GraphNode feature = resolveNode(featureMap, mapping.getBusinessAction(), "feature:");
        if (feature == null) {
            feature = resolveNode(featureMap, mapping.getApiKey(), "feature:");
        }
        if (feature == null) {
            feature = resolveNode(featureMap, mapping.getPageKey(), "feature:");
        }
        if (feature == null) {
            log.debug("Feature mapping dropped: feature not found for businessAction={}",
                    mapping.getBusinessAction());
            return created;
        }

        BigDecimal confidence = BigDecimal.valueOf(normalizeConfidence(mapping.getConfidence()));

        // Feature → ApiEndpoint（IMPLEMENTED_BY）
        if (mapping.getApiKey() != null && !mapping.getApiKey().isBlank()) {
            GraphNode api = resolveNode(apiMap, mapping.getApiKey(), null);
            if (api != null) {
                GraphEdge edge = upsertMappingEdge(projectId, versionId, feature, api,
                        EdgeType.IMPLEMENTED_BY.name(),
                        "ai-feature:" + feature.getNodeKey() + "->implemented_by->" + api.getNodeKey(),
                        confidence, mapping);
                if (edge != null) {
                    created.add(edge);
                }
            }
        }
        // Feature → Page（EXPOSED_BY）
        if (mapping.getPageKey() != null && !mapping.getPageKey().isBlank()) {
            GraphNode page = resolveNode(pageMap, mapping.getPageKey(), null);
            if (page != null) {
                GraphEdge edge = upsertMappingEdge(projectId, versionId, feature, page,
                        EdgeType.EXPOSED_BY.name(),
                        "ai-feature:" + feature.getNodeKey() + "->exposed_by->" + page.getNodeKey(),
                        confidence, mapping);
                if (edge != null) {
                    created.add(edge);
                }
            }
        }
        return created;
    }

    /** 构建 nodeKey + nodeName(小写) → 节点 的查找表，支持按 key 或 name 解析。 */
    private Map<String, GraphNode> buildNodeIndex(List<GraphNode> nodes) {
        Map<String, GraphNode> idx = new HashMap<>();
        if (nodes == null) {
            return idx;
        }
        for (GraphNode n : nodes) {
            if (n.getNodeKey() != null && !n.getNodeKey().isBlank()) {
                idx.putIfAbsent(n.getNodeKey(), n);
            }
            if (n.getNodeName() != null && !n.getNodeName().isBlank()) {
                idx.putIfAbsent(n.getNodeName().toLowerCase(), n);
            }
        }
        return idx;
    }

    /** 从查找表解析节点：先按原值（key），再按 prefix+原值（如 "feature:"+name），最后按小写 name。 */
    private GraphNode resolveNode(Map<String, GraphNode> map, String keyOrName, String keyPrefix) {
        if (map == null || map.isEmpty() || keyOrName == null || keyOrName.isBlank()) {
            return null;
        }
        GraphNode n = map.get(keyOrName);
        if (n != null) {
            return n;
        }
        if (keyPrefix != null) {
            n = map.get(keyPrefix + keyOrName);
            if (n != null) {
                return n;
            }
        }
        return map.get(keyOrName.toLowerCase());
    }

    /** 统一走 EvidenceGraphWriter：去重 + 证据继承 + 状态裁决。 */
    private GraphEdge upsertMappingEdge(String projectId, String versionId,
                                        GraphNode from, GraphNode to,
                                        String edgeType, String edgeKey,
                                        BigDecimal confidence,
                                        FeatureMappingAgent.Mapping mapping) throws Exception {
        GraphEdgeClaim claim = GraphEdgeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .fromNodeId(from.getId())
                .toNodeId(to.getId())
                .edgeType(edgeType)
                .edgeKey(edgeKey)
                .sourceType("AI_FEATURE_MAPPING")
                .confidence(confidence)
                .status("PENDING_CONFIRM")
                .properties(objectMapper.writeValueAsString(mapping))
                .build();
        return evidenceGraphWriter.upsertEdge(claim);
    }

    private boolean createMappingReviewRecord(String projectId, String versionId,
                                              FeatureMappingAgent.Mapping mapping, String targetId) {
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
            record.setId(IdUtil.fastUUID());
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
                    + support.nonBlank(mapping.getBusinessAction(), mappingTargetId(mapping))
                    + "，页面=" + support.nonBlank(mapping.getPageKey(), "-")
                    + "，接口=" + support.nonBlank(mapping.getApiKey(), "-"));
            record.setCreatedAt(LocalDateTime.now());
            reviewRecordRepository.insert(record);
            return true;
        } catch (Exception e) {
            log.warn("Failed to create review record for mapping {}: {}", mappingTargetId(mapping), e.getMessage());
            return false;
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

    /** 从 API 节点列表构建 Controller 上下文摘要 */
    private String buildControllerContext(List<GraphNode> apiNodes) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<GraphNode>> byController = new LinkedHashMap<>();
        for (GraphNode api : apiNodes) {
            // sourcePath 格式如 "src/main/java/.../OrderController.java"
            String path = api.getSourcePath();
            if (path == null) {
                continue;
            }
            String controllerName = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            byController.computeIfAbsent(controllerName, k -> new ArrayList<>()).add(api);
        }
        for (var entry : byController.entrySet()) {
            sb.append("### ").append(entry.getKey()).append("\n");
            for (GraphNode api : entry.getValue()) {
                sb.append("- ").append(api.getNodeName());
                // 如果 properties 中有 params/requestBody/responseType，也输出
                String props = api.getProperties();
                if (props != null && !props.isBlank() && !props.equals("{}")) {
                    sb.append(" | props: ").append(props);
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.length() > 0 ? sb.toString() : "(无 Controller 上下文)";
    }

    private String mappingTargetId(FeatureMappingAgent.Mapping mapping) {
        return support.nonBlank(mapping.getPageKey(), "-") + "->" + support.nonBlank(mapping.getApiKey(), "-");
    }

    private double normalizeConfidence(double confidence) {
        if (confidence <= 0) {
            return 0.7;
        }
        return Math.min(1.0, Math.max(0.0, confidence));
    }

    /**
     * 调用 LLM 功能映射，首次返回空映射时追加重试提示并重试一次。
     *
     * @param request            映射请求
     * @param featureMappingAgent LLM 网关 agent
     * @param agentCallCounter    调用计数器
     * @return 映射结果（可能为空）
     */
    public static io.github.legacygraph.agent.FeatureMappingAgent.MappingResult mapFeaturesWithRetry(
            io.github.legacygraph.agent.FeatureMappingAgent.MappingRequest request,
            io.github.legacygraph.agent.FeatureMappingAgent featureMappingAgent,
            Counter agentCallCounter) {
        agentCallCounter.increment();
        io.github.legacygraph.agent.FeatureMappingAgent.MappingResult result = featureMappingAgent.mapFeatures(request);
        boolean empty = result == null
                || result.getMappings() == null
                || result.getMappings().isEmpty();
        if (empty) {
            // 追加重试提示后重试一次
            String retryHint = "\n[重试提示] 首次映射结果为空，请重新分析并生成功能映射。";
            request.setProductDoc(
                    (request.getProductDoc() == null ? "" : request.getProductDoc()) + retryHint);
            agentCallCounter.increment();
            result = featureMappingAgent.mapFeatures(request);
        }
        return result;
    }

    /**
     * 根据目标上下文长度动态计算每批 Feature 数量，确保不超出 LLM 上下文限制。
     *
     * @param features       全部 Feature 节点列表
     * @param availableChars 可用字符数（扣除 Page/API/Controller 摘要后）
     * @return 动态计算的批次大小，最小值为 1，最大值为 FEATURE_MAPPING_BATCH_SIZE
     */
    private int calculateDynamicBatchSize(List<GraphNode> features, int availableChars) {
        if (features == null || features.isEmpty() || availableChars <= 0) {
            return FEATURE_MAPPING_BATCH_SIZE;
        }

        int avgFeatureLen = 0;
        int sampleSize = Math.min(features.size(), 20);
        for (int i = 0; i < sampleSize; i++) {
            GraphNode f = features.get(i);
            String name = f.getNodeName();
            String key = f.getNodeKey();
            avgFeatureLen += (name != null ? name.length() : 0) + (key != null ? key.length() : 0) + 30; // 30 为格式开销
        }
        avgFeatureLen = sampleSize > 0 ? avgFeatureLen / sampleSize : 80; // 默认平均每条 80 字符

        int estimatedBatchSize = availableChars / avgFeatureLen;
        estimatedBatchSize = Math.max(1, estimatedBatchSize);
        estimatedBatchSize = Math.min(FEATURE_MAPPING_BATCH_SIZE, estimatedBatchSize);

        return estimatedBatchSize;
    }
}
