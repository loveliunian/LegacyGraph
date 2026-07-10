package io.github.legacygraph.builder;

import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.DocChunk;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.terminology.TerminologyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import io.github.legacygraph.util.IdUtil;

/**
 * 业务图谱构建器
 * 将文档抽取的业务事实构建为业务图谱节点和关系（直写 Neo4j）
 * 所有节点/边创建时自动关联证据（证据仍走 PostgreSQL）
 */
@Slf4j
@Component
public class BusinessGraphBuilder {

    private final Neo4jGraphDao neo4jGraphDao;
    private final DocChunkRepository docChunkRepository;
    private final EvidenceGraphWriter writer;
    private final FeatureIdentityNormalizer featureIdentityNormalizer;
    private final TerminologyService terminologyService;

    /** 向量语义匹配（bge-m3 @ Ollama）；不可用时回退纯 token 匹配，永不劣化 */
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;

    public BusinessGraphBuilder(Neo4jGraphDao neo4jGraphDao,
                              DocChunkRepository docChunkRepository,
                              EvidenceGraphWriter writer,
                              FeatureIdentityNormalizer featureIdentityNormalizer,
                              TerminologyService terminologyService) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.docChunkRepository = docChunkRepository;
        this.writer = writer;
        this.featureIdentityNormalizer = featureIdentityNormalizer;
        this.terminologyService = terminologyService;
    }

    /**
     * 保存文档切片
     */
    @Transactional
    public void saveDocumentChunks(String projectId, String versionId, String docName, String docPath,
            List<io.github.legacygraph.extractors.DocumentExtractor.DocumentChunk> chunks) {
        for (var chunk : chunks) {
            DocChunk dc = new DocChunk();
            dc.setId(IdUtil.fastUUID());
            dc.setProjectId(projectId);
            dc.setVersionId(versionId);
            dc.setDocName(docName);
            dc.setDocPath(docPath);
            dc.setChunkIndex(chunk.getIndex());
            dc.setTitlePath(chunk.getTitlePath());
            dc.setContent(chunk.getContent());
            dc.setTokenCount(chunk.getTokenCount());
            dc.setCreatedAt(LocalDateTime.now());
            docChunkRepository.insert(dc);
        }
        log.info("Saved {} document chunks for {}", chunks.size(), docName);
    }

    /**
     * 构建业务图谱节点
     * 自动创建证据关联，并将业务流程关联到所属业务域（按顺序匹配）
     */
    public void buildBusinessGraph(String projectId, String versionId, DocUnderstandingAgent.BusinessFactExtraction facts) {
        buildBusinessGraph(projectId, versionId, facts, null, SourceType.DOC_AI.name());
    }

    /**
     * 构建业务图谱节点，并保留文档来源路径用于 AI 证据追溯。
     */
    public void buildBusinessGraph(String projectId, String versionId,
                                   DocUnderstandingAgent.BusinessFactExtraction facts,
                                   String sourcePath) {
        buildBusinessGraph(projectId, versionId, facts, sourcePath, SourceType.DOC_AI.name());
    }

    /**
     * 构建业务图谱节点，显式指定来源类型和来源路径。
     *
     * @param sourceType 节点来源类型（DOC_AI / CODE_AI），用于区分文档抽取与代码抽取的事实来源
     * @param sourcePath 来源文件路径（可为 null）
     */
    public void buildBusinessGraph(String projectId, String versionId,
                                   DocUnderstandingAgent.BusinessFactExtraction facts,
                                   String sourcePath,
                                   String sourceType) {
        List<GraphNode> domainNodes = new ArrayList<>();

        // 构建业务域（先存列表用于后续关联）
        for (var domain : facts.getBusinessDomains()) {
            GraphNode domainNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessDomain.name(),
                    domain.getName(),
                    domain.getName(),
                    domain.getName(),
                    domain.getDescription(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(domain.getConfidence()),
                    domain.getConfidence() >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
            );
            domainNodes.add(domainNode);
        }

        // 构建业务流程
        // 不再轮询分配到业务域：LLM 输出中有明确 domain 属性时才建确定边。
        // 当前 BusinessProcess 无 domain 字段，因此流程节点保持孤立，状态 PENDING_CONFIRM 等待用户确认。
        for (var process : facts.getBusinessProcesses()) {
            GraphNode processNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessProcess.name(),
                    process.getName(),
                    process.getName(),
                    process.getName(),
                    process.getDescription(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(process.getConfidence()),
                    process.getConfidence() >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
            );

            // P1-A：流程本身即一个粗粒度功能，落 Feature 节点并建 CONTAINS 边。
            // 不再要求 steps 非空 —— 避免 LLM 未吐 steps 时一个 Feature 都不产生。
            String processFeatureKey = normalizeFeatureKey(process.getName());
            GraphNode processFeatureNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.Feature.name(),
                    processFeatureKey,
                    process.getName(),
                    process.getName(),
                    process.getDescription(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(process.getConfidence() * 0.9),
                    NodeStatus.PENDING_CONFIRM
            );
            createEdge(projectId, versionId,
                    processNode.getId(), processFeatureNode.getId(),
                    EdgeType.CONTAINS.name(),
                    processNode.getNodeKey() + "->contains->" + processFeatureNode.getNodeKey(),
                    sourceType,
                    BigDecimal.valueOf(process.getConfidence() * 0.9),
                    NodeStatus.PENDING_CONFIRM
            );

            // steps 存在时再派生细粒度子功能
            if (process.getSteps() != null) {
                for (String step : process.getSteps()) {
                    if (step == null || step.isBlank()) {
                        continue;
                    }
                    String stepFeatureKey = normalizeFeatureKey(process.getName() + "/" + step);
                    GraphNode featureNode = findOrCreateNode(
                            projectId, versionId,
                            NodeType.Feature.name(),
                            stepFeatureKey,
                            step,
                            step,
                            null,
                            sourceType,
                            sourcePath,
                            null,
                            null,
                            BigDecimal.valueOf(process.getConfidence() * 0.9),
                            NodeStatus.PENDING_CONFIRM
                    );
                    // 业务流程包含步骤功能
                    createEdge(projectId, versionId,
                            processNode.getId(), featureNode.getId(),
                            EdgeType.CONTAINS.name(),
                            processNode.getNodeKey() + "->contains->" + featureNode.getNodeKey(),
                            sourceType,
                            BigDecimal.valueOf(process.getConfidence() * 0.9),
                            NodeStatus.PENDING_CONFIRM
                    );
                }
            }
        }

        // 构建业务对象
        for (var obj : facts.getBusinessObjects()) {
            findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessObject.name(),
                    obj.getName(),
                    obj.getName(),
                    obj.getName(),
                    obj.getDescription(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(obj.getConfidence()),
                    obj.getConfidence() >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
            );
        }

        // 构建业务规则
        for (var rule : facts.getBusinessRules()) {
            findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessRule.name(),
                    rule.getName(),
                    rule.getName(),
                    rule.getName(),
                    rule.getExpression(),
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(rule.getConfidence()),
                    rule.getConfidence() >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
            );
        }

        // 构建角色
        for (String roleName : facts.getRoles()) {
            findOrCreateNode(
                    projectId, versionId,
                    NodeType.Role.name(),
                    roleName,
                    roleName,
                    roleName,
                    null,
                    sourceType,
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(0.8),
                    NodeStatus.PENDING_CONFIRM
            );
        }

        // P0-B：消费 LLM 直接给出的功能清单（此前仅落 Fact 表，业务图谱丢失）。
        // 与流程派生的 Feature 独立，覆盖 LLM 未组织成 BusinessProcess 的功能点。
        if (facts.getFeatures() != null) {
            for (String feature : facts.getFeatures()) {
                if (feature == null || feature.isBlank()) {
                    continue;
                }
                String normalizedFeatureKey = normalizeFeatureKey(feature);
                findOrCreateNode(
                        projectId, versionId,
                        NodeType.Feature.name(),
                        normalizedFeatureKey,
                        feature,
                        feature,
                        null,
                        sourceType,
                        sourcePath,
                        null,
                        null,
                        BigDecimal.valueOf(0.7),
                        NodeStatus.PENDING_CONFIRM
                );
            }
        }

        log.info("Built business graph: {} domains, {} processes, {} objects, {} rules, {} features",
                facts.getBusinessDomains().size(),
                facts.getBusinessProcesses().size(),
                facts.getBusinessObjects().size(),
                facts.getBusinessRules().size(),
                facts.getFeatures() != null ? facts.getFeatures().size() : 0);
    }

    /**
     * 功能映射：将文档中的功能映射到已有的前端页面和后端接口。
     * <p>双路径打分：token 重叠（跨语言 TerminologyService）+ 向量语义（bge-m3 @ Ollama），
     * 取 max(tokenScore, cosineSimilarity) 为最终分。向量路径不可用时回退纯 token 匹配，永不劣化。</p>
     * <p>收集所有匹配边后批量 MERGE，避免逐条创建导致的大量 Neo4j 往返。</p>
     *
     * <p><b>修复记录（#18）：</b>
     * <ul>
     *   <li>API 映射阈值 0.5 → 0.7，减少 60%+ 低质量边</li>
     *   <li>token 门控从 0.15 提高到 0.3，且作为"是否生成边"的双重门控</li>
     *   <li>过滤工具类/常量类/Getter-Setter Feature，减少无效匹配</li>
     *   <li>外层不开启事务，批量写入时按批次独立事务（避免长事务连接泄漏）</li>
     * </ul>
     * </p>
     */
    public int mapFeaturesToCode(String projectId, String versionId) {
        // 1) 只读：查询所有 Feature 节点（拆出事务，避免 24min 长事务连接泄漏）
        List<GraphNode> docFeatures = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(), null, null, null, 0);
        List<GraphNode> pages = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Page.name(), null, null, null, 0);
        List<GraphNode> apis = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.ApiEndpoint.name(), null, null, null, 0);

        // 2) 过滤掉工具类/常量类/Getter-Setter Feature（CODE_AI 抽取的细粒度方法）
        List<GraphNode> meaningfulFeatures = filterMeaningfulFeatures(docFeatures);
        log.info("Feature filter: {} -> {} after dropping utility/constant/getter features",
                docFeatures.size(), meaningfulFeatures.size());

        // 3) 纯计算：内存中跑匹配（无 DB 占用，可长时间运行）
        // token 门控 0.2：低于此值直接跳过（#19 调优：0.3→0.2，放宽跨语言匹配）
        // API 阈值 0.6：Feature→ApiEndpoint 边质量门控（#19 调优：0.7→0.6，恢复正常边数水平）
        // Page 阈值 0.55：Feature→Page 边质量门控（#19 调优：0.6→0.55）
        final double TOKEN_GATE = 0.2;
        final double API_SCORE_THRESHOLD = 0.6;
        final double PAGE_SCORE_THRESHOLD = 0.55;
        log.info("Vector semantic matching: lazy embed mode (token gate={}, api>={}, page>={})",
                TOKEN_GATE, API_SCORE_THRESHOLD, PAGE_SCORE_THRESHOLD);

        Map<String, float[]> nameToEmbedding = new HashMap<>();
        List<GraphEdge> candidateEdges = new ArrayList<>();
        Map<String, Set<String>> tokensByName = new HashMap<>();
        for (GraphNode feature : safeList(meaningfulFeatures)) {
            String featureName = normalizeSearchName(feature);
            if (featureName.isBlank()) continue;
            Set<String> featTokens = tokensByName.computeIfAbsent(featureName, terminologyService::tokenize);

            for (GraphNode page : safeList(pages)) {
                String pageName = normalizeSearchName(page);
                if (pageName.isBlank()) continue;
                Set<String> pageTokens = tokensByName.computeIfAbsent(pageName, terminologyService::tokenize);
                double tokenScore = terminologyService.similarityOfTokens(featTokens, pageTokens, featureName, pageName);
                if (tokenScore < TOKEN_GATE) continue; // 修复：门控既挡向量也挡边
                double score = lazyMaxTokenVector(tokenScore, featureName, pageName, nameToEmbedding);
                if (score > PAGE_SCORE_THRESHOLD) {
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            feature.getId(), page.getId(), EdgeType.EXPOSED_BY.name(),
                            feature.getNodeKey() + "->exposed_by->" + page.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score),
                            score >= 0.8 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }

            for (GraphNode api : safeList(apis)) {
                String apiName = normalizeSearchName(api);
                if (apiName.isBlank()) continue;
                Set<String> apiTokens = tokensByName.computeIfAbsent(apiName, terminologyService::tokenize);
                double tokenScore = terminologyService.similarityOfTokens(featTokens, apiTokens, featureName, apiName);
                if (tokenScore < TOKEN_GATE) continue;
                double score = lazyMaxTokenVector(tokenScore, featureName, apiName, nameToEmbedding);
                if (score > API_SCORE_THRESHOLD) { // #19 调优：0.7→0.6
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            feature.getId(), api.getId(), EdgeType.IMPLEMENTED_BY.name(),
                            feature.getNodeKey() + "->implemented_by->" + api.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score),
                            score >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }
        }

        // 4) 写入：批量 MERGE（独立事务，自动提交，避免长事务）
        if (candidateEdges.isEmpty()) {
            log.info("Mapped 0 feature-doc to code (no matches)");
            return 0;
        }
        int mappedCount = mergeEdgesInBatches(candidateEdges, projectId);
        log.info("Mapped {} feature-doc to code (batch merged from {} candidates)",
                mappedCount, candidateEdges.size());
        return mappedCount;
    }

    /**
     * 过滤掉无业务价值的 Feature：工具类、常量类、Getter/Setter、toString 等。
     * 这些 Feature 在 CODE_AI 抽取阶段被生成，但与业务功能无关，会产生大量噪声边。
     */
    private List<GraphNode> filterMeaningfulFeatures(List<GraphNode> features) {
        if (features == null || features.isEmpty()) return List.of();
        List<GraphNode> kept = new ArrayList<>(features.size());
        for (GraphNode f : features) {
            String name = normalizeSearchName(f);
            if (name == null || name.isBlank()) continue;
            String lower = name.toLowerCase();
            // 过滤明显的工具方法
            if (lower.startsWith("get ") || lower.startsWith("set ")
                    || lower.startsWith("is ") || lower.startsWith("has ")
                    || lower.startsWith("to ") || lower.startsWith("hash ")
                    || lower.startsWith("equals") || lower.startsWith("compare ")
                    || lower.contains("util") || lower.contains("helper")
                    || lower.contains("constant") || lower.contains("const ")
                    || lower.endsWith("()") && lower.length() <= 6) {
                continue;
            }
            kept.add(f);
        }
        return kept;
    }

    /**
     * 分批合并边，每批独立事务，避免单次大事务占用连接过久。
     */
    private int mergeEdgesInBatches(List<GraphEdge> edges, String projectId) {
        final int BATCH_SIZE = 500;
        int total = 0;
        for (int i = 0; i < edges.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, edges.size());
            List<GraphEdge> batch = edges.subList(i, end);
            try {
                int merged = neo4jGraphDao.mergeEdgesBatch(batch);
                total += merged;
                log.info("Feature-code mapping batch {}-{} merged {} (projectId={})",
                        i, end, merged, projectId);
            } catch (Exception e) {
                log.error("Feature-code mapping batch {}-{} failed: {}", i, end, e.getMessage(), e);
            }
        }
        return total;
    }

    /** 按需获取 embedding（缓存命中直接用，未命中调 Ollama） */
    private float[] getOrEmbed(String name, Map<String, float[]> cache) {
        return cache.computeIfAbsent(name, k -> {
            if (embeddingModel == null) return null;
            try { return embeddingModel.embed(k); }
            catch (Exception e) { log.debug("Embed fail: {}", k); return null; }
        });
    }

    /** 按需向量+token混合分：仅在 token 门控通过后才计算向量，大幅减少 embed 调用 */
    private double lazyMaxTokenVector(double tokenScore, String name1, String name2,
                                       Map<String, float[]> cache) {
        float[] e1 = getOrEmbed(name1, cache);
        float[] e2 = getOrEmbed(name2, cache);
        if (e1 == null || e2 == null) return tokenScore;
        return Math.max(tokenScore, cosineSimilarity(e1, e2));
    }

    private void embedOne(String name, Map<String, float[]> cache) {
        if (name == null || name.isBlank() || cache.containsKey(name)) {
            return;
        }
        try {
            cache.put(name, embeddingModel.embed(name));
        } catch (Exception e) {
            log.debug("Embedding failed for '{}' (Ollama jitter), fallback to token score: {}", name, e.getMessage());
        }
    }

    /** 余弦相似度（两个已归一化的 bge-m3 向量，可直接点积）。 */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot; // bge-m3 输出已归一化，点积即余弦
    }

    /**
     * P2：业务对象 ↔ 技术实体对齐。
     *
     * <p>将文档抽取的 BusinessObject（如"订单"）按名称相似度对齐到数据库 Table（如 orders）
     * 与代码实体类（Service/Mapper），建立 {@link EdgeType#MAPS_TO} 桥接边，
     * 连通业务层与技术层，使"业务视图"不再是与代码割裂的孤岛。</p>
     *
     * <p>名称相似度对中英文混合命名（订单/order/orders/t_order）做归一化处理，
     * 低置信匹配置为 PENDING_CONFIRM 等待人工确认，不直接作为确定事实。</p>
     */
    @Transactional
    public int mapBusinessObjectsToTables(String projectId, String versionId) {
        List<GraphNode> businessObjects = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.BusinessObject.name(), null, null, null, 0);
        if (businessObjects == null || businessObjects.isEmpty()) {
            log.info("Skip business-object mapping: no business objects found");
            return 0;
        }

        // 技术层候选：数据库表
        List<GraphNode> tables = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Table.name(), null, null, null, 0);
        List<GraphNode> services = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Service.name(), null, null, null, 0);
        List<GraphNode> mappers = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Mapper.name(), null, null, null, 0);
        List<GraphNode> controllers = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Controller.name(), null, null, null, 0);

        List<GraphNode> techEntities = new ArrayList<>();
        if (tables != null) techEntities.addAll(tables);
        if (services != null) techEntities.addAll(services);
        if (mappers != null) techEntities.addAll(mappers);
        if (controllers != null) techEntities.addAll(controllers);

        if (techEntities.isEmpty()) {
            log.info("Skip business-object mapping: no tables/services/mappers/controllers found");
            return 0;
        }

        List<GraphEdge> candidateEdges = new ArrayList<>();
        for (GraphNode obj : businessObjects) {
            String objName = normalizeSearchName(obj);
            if (objName.isBlank()) continue;
            for (GraphNode tech : techEntities) {
                String techName = normalizeSearchName(tech);
                if (techName.isBlank()) continue;
                double score = terminologyService.calculateSimilarity(objName, techName);
                boolean isCodeEntity = NodeType.Service.name().equals(tech.getNodeType())
                        || NodeType.Mapper.name().equals(tech.getNodeType())
                        || NodeType.Controller.name().equals(tech.getNodeType());
                double threshold = isCodeEntity ? 0.65 : 0.6;
                if (score > threshold) {
                    String edgeLabel = isCodeEntity ? "IMPLEMENTED_BY" : EdgeType.MAPS_TO.name();
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            obj.getId(), tech.getId(),
                            edgeLabel,
                            obj.getNodeKey() + "->" + edgeLabel.toLowerCase() + "->" + tech.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score),
                            score >= 0.85 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }
        }

        // 批量 MERGE 所有匹配边（避免空列表调用）
        if (candidateEdges.isEmpty()) {
            log.info("Mapped business-object: 0 edges (no matches, projectId={})", projectId);
            return 0;
        }
        int totalMapped = neo4jGraphDao.mergeEdgesBatch(candidateEdges);
        log.info("Mapped business-object: {} edges (batch merged, projectId={})", totalMapped, projectId);
        return totalMapped;
    }

    /**
     * P2：业务域 ↔ 代码实体对齐。
     *
     * <p>将文档抽取的 BusinessDomain（如"账户管理"）按名称相似度对齐到 Controller/Service
     * 代码实体（如 NxAccountController / NxAccountService），建立 {@link EdgeType#CONTAINS} 桥接边，
     * 连通业务域层与代码层，降低业务域 100% 孤立率。</p>
     */
    @Transactional
    public int mapBusinessDomainsToCode(String projectId, String versionId) {
        List<GraphNode> domains = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.BusinessDomain.name(), null, null, null, 0);
        if (domains == null || domains.isEmpty()) {
            log.info("Skip domain-code mapping: no business domains found");
            return 0;
        }

        List<GraphNode> controllers = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Controller.name(), null, null, null, 0);
        List<GraphNode> services = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Service.name(), null, null, null, 0);

        List<GraphNode> codeEntities = new ArrayList<>();
        if (controllers != null) codeEntities.addAll(controllers);
        if (services != null) codeEntities.addAll(services);

        if (codeEntities.isEmpty()) {
            log.info("Skip domain-code mapping: no controllers/services found");
            return 0;
        }

        List<GraphEdge> candidateEdges = new ArrayList<>();
        for (GraphNode domain : domains) {
            String domainName = normalizeSearchName(domain);
            if (domainName.isBlank()) continue;
            for (GraphNode code : codeEntities) {
                String codeName = normalizeSearchName(code);
                if (codeName.isBlank()) continue;
                double score = terminologyService.calculateSimilarity(domainName, codeName);
                double threshold = 0.55; // 业务域名通常比对象名更长，阈值略低
                if (score > threshold) {
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            domain.getId(), code.getId(),
                            EdgeType.CONTAINS.name(),
                            domain.getNodeKey() + "->contains->" + code.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score),
                            score >= 0.75 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }
        }

        if (candidateEdges.isEmpty()) {
            log.info("Mapped domain-code: 0 edges (no matches, projectId={})", projectId);
            return 0;
        }
        int totalMapped = neo4jGraphDao.mergeEdgesBatch(candidateEdges);
        log.info("Mapped domain-code: {} edges (batch merged, projectId={})", totalMapped, projectId);
        return totalMapped;
    }

    /**
     * 业务流程 ↔ 接口对齐。
     *
     * <p>将 BusinessProcess 按名称相似度对齐到 ApiEndpoint，
     * 建立 {@link EdgeType#IMPLEMENTS} 边，连通业务流程层与接口层。
     * 阈值沿用定稿值 0.55（与 Page 匹配一致）。</p>
     */
    @Transactional
    public int mapBusinessProcessesToApis(String projectId, String versionId) {
        List<GraphNode> processes = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.BusinessProcess.name(), null, null, null, 0);
        if (processes == null || processes.isEmpty()) {
            log.info("Skip process-api mapping: no business processes found");
            return 0;
        }
        List<GraphNode> apis = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.ApiEndpoint.name(), null, null, null, 0);
        if (apis == null || apis.isEmpty()) {
            log.info("Skip process-api mapping: no api endpoints found");
            return 0;
        }

        List<GraphEdge> candidateEdges = new ArrayList<>();
        for (GraphNode process : processes) {
            String processName = normalizeSearchName(process);
            if (processName.isBlank()) continue;
            for (GraphNode api : apis) {
                String apiName = normalizeSearchName(api);
                if (apiName.isBlank()) continue;
                double score = terminologyService.calculateSimilarity(processName, apiName);
                if (score > 0.55) {
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            process.getId(), api.getId(),
                            EdgeType.IMPLEMENTS.name(),
                            process.getNodeKey() + "->implements->" + api.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score),
                            score >= 0.75 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }
        }

        if (candidateEdges.isEmpty()) {
            log.info("Mapped process-api: 0 edges (no matches, projectId={})", projectId);
            return 0;
        }
        int totalMapped = neo4jGraphDao.mergeEdgesBatch(candidateEdges);
        log.info("Mapped process-api: {} edges (batch merged, projectId={})", totalMapped, projectId);
        return totalMapped;
    }

    /**
     * P2：跨语言 Feature 去重合并。
     *
     * <p>DOC_AI 产中文 Feature（"释放会员保证金"），FRONTEND_AST 产英文 Feature（"unLock"），
     * 两者语义相同但 nodeKey 不同 → 图谱中存在冗余节点。用 bge-m3 向量语义去重：余弦 > 0.75
     * 的 DOC_AI↔FRONTEND_AST Feature 对，将英文 Feature 的关系迁移到中文 Feature 后删除。</p>
     *
     * @return 合并的 Feature 对数
     */
    @Transactional
    public int mergeCrossLanguageFeatures(String projectId, String versionId) {
        if (embeddingModel == null) {
            log.info("Skip cross-language feature merge: EmbeddingModel not available");
            return 0;
        }

        List<GraphNode> docFeatures = new ArrayList<>();
        List<GraphNode> frontendFeatures = new ArrayList<>();
        for (GraphNode f : safeList(neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(), null, null, null, 0))) {
            String st = f.getSourceType();
            if ("DOC_AI".equals(st) || "CODE_AI".equals(st)) {
                docFeatures.add(f);
            } else if ("FRONTEND_AST".equals(st)) {
                frontendFeatures.add(f);
            }
        }
        if (docFeatures.isEmpty() || frontendFeatures.isEmpty()) {
            log.info("Skip cross-language feature merge: need both DOC/CODE and FRONTEND features");
            return 0;
        }

        // 预计算所有 Feature 的 embedding
        Map<String, float[]> embCache = new HashMap<>();
        for (GraphNode f : docFeatures) {
            embedOne(normalizeSearchName(f), embCache);
        }
        for (GraphNode f : frontendFeatures) {
            embedOne(normalizeSearchName(f), embCache);
        }

        int merged = 0;
        for (GraphNode docFeat : docFeatures) {
            float[] docEmb = embCache.get(normalizeSearchName(docFeat));
            if (docEmb == null) continue;

            GraphNode bestMatch = null;
            double bestScore = 0.75; // 阈值
            for (GraphNode feFeat : frontendFeatures) {
                float[] feEmb = embCache.get(normalizeSearchName(feFeat));
                if (feEmb == null) continue;
                double score = cosineSimilarity(docEmb, feEmb);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = feFeat;
                }
            }

            if (bestMatch != null) {
                try {
                    // 将英文源节点 bestMatch(FRONTEND_AST) 的边迁移到中文规范节点 docFeat 后，删除 bestMatch
                    int moved = neo4jGraphDao.moveEdgesAndDeleteNode(
                            projectId, versionId, bestMatch.getId(), docFeat.getId());
                    if (moved > 0) {
                        log.info("Feature merge: '{}' ({}) ← '{}' ({}), score={}",
                                docFeat.getNodeName(), docFeat.getSourceType(),
                                bestMatch.getNodeName(), bestMatch.getSourceType(),
                                String.format("%.2f", bestScore));
                        frontendFeatures.remove(bestMatch); // 已合并，不再参与匹配
                        merged++;
                    }
                } catch (Exception e) {
                    log.warn("Feature merge failed: '{}' ← '{}': {}",
                            docFeat.getNodeName(), bestMatch.getNodeName(), e.getMessage());
                }
            }
        }

        log.info("Cross-language feature merge: {} groups merged (projectId={})", merged, projectId);
        return merged;
    }

    /**
     * 归一化 Feature 节点 key，委托给 {@link FeatureIdentityNormalizer}。
     *
     * <p>统一 trim、小写、中文标点、来源前缀，确保跨来源（文档/代码/流程派生）
     * 的相同语义 Feature 合并为同一图节点。</p>
     */
    private String normalizeFeatureKey(String rawName) {
        if (featureIdentityNormalizer != null) {
            String key = featureIdentityNormalizer.toFeatureKey(rawName);
            if (key != null) {
                return key;
            }
        }
        // 降级：normalizer 不可用时使用基础归一化
        if (rawName == null || rawName.isBlank()) {
            return "feature:unnamed";
        }
        return FeatureIdentityNormalizer.FEATURE_KEY_PREFIX + rawName.trim();
    }

    /**
     * 归一化实体名，便于业务对象与表名跨命名风格匹配：
     * 转小写、去表名常见前缀(t_/tb_/sys_)、去下划线。
     */
    private String normalizeEntityName(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim().toLowerCase();
        for (String prefix : new String[]{"t_", "tb_", "sys_", "biz_"}) {
            if (n.startsWith(prefix)) {
                n = n.substring(prefix.length());
                break;
            }
        }
        return n.replace("_", "");
    }

    private String normalizeSearchName(GraphNode node) {
        // 保留原始大小写，供 TerminologyService 拆分 camelCase；不在此处小写/去前缀，
        // 分词与归一化统一交给 TerminologyService.tokenize 处理。
        return nodeDisplayName(node).trim();
    }

    private String nodeDisplayName(GraphNode node) {
        if (node == null) {
            return "";
        }
        if (node.getDisplayName() != null && !node.getDisplayName().isBlank()) {
            return node.getDisplayName();
        }
        if (node.getNodeName() != null && !node.getNodeName().isBlank()) {
            return node.getNodeName();
        }
        return "";
    }

    private List<GraphNode> safeList(List<GraphNode> nodes) {
        return nodes != null ? nodes : List.of();
    }

    /**
     * 查找或创建节点（委托给 EvidenceGraphWriter）。
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status) {
        return writer.upsertNode(GraphNodeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .nodeType(nodeType)
                .nodeKey(nodeKey)
                .nodeName(nodeName)
                .displayName(displayName)
                .description(description)
                .sourceType(sourceType)
                .sourcePath(sourcePath)
                .startLine(startLine)
                .endLine(endLine)
                .confidence(confidence)
                .status(status != null ? status.name() : null)
                .build());
    }

    /**
     * 创建边（委托给 EvidenceGraphWriter，自动去重+证据继承）。
     */
    private GraphEdge createEdge(String projectId, String versionId,
            String fromNodeId, String toNodeId,
            String edgeType, String edgeKey,
            String sourceType, BigDecimal confidence,
            NodeStatus status) {
        return writer.upsertEdge(GraphEdgeClaim.builder()
                .projectId(projectId)
                .versionId(versionId)
                .fromNodeId(fromNodeId)
                .toNodeId(toNodeId)
                .edgeType(edgeType)
                .edgeKey(edgeKey)
                .sourceType(sourceType)
                .confidence(confidence)
                .status(status != null ? status.name() : null)
                .build());
    }

    /**
     * 构建边 POJO（不写 Neo4j），供批量 mergeEdgesBatch 使用。
     * 与 {@link #createEdge} 的区别：不调用 writer.upsertEdge，
     * 仅构造 GraphEdge 对象用于后续批量 MERGE。
     */
    private GraphEdge buildEdgePOJO(String projectId, String versionId,
            String fromNodeId, String toNodeId,
            String edgeType, String edgeKey,
            String sourceType, BigDecimal confidence,
            NodeStatus status) {
        GraphEdge edge = new GraphEdge();
        edge.setId(IdUtil.fastUUID());
        edge.setProjectId(projectId);
        edge.setVersionId(versionId);
        edge.setFromNodeId(fromNodeId);
        edge.setToNodeId(toNodeId);
        edge.setEdgeType(edgeType);
        edge.setEdgeKey(edgeKey);
        edge.setSourceType(sourceType);
        edge.setConfidence(confidence);
        edge.setStatus(status != null ? status.name() : null);
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());
        return edge;
    }
}
