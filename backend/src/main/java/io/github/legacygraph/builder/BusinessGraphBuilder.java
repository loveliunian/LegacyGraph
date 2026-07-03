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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    public BusinessGraphBuilder(Neo4jGraphDao neo4jGraphDao,
                              DocChunkRepository docChunkRepository,
                              EvidenceGraphWriter writer,
                              FeatureIdentityNormalizer featureIdentityNormalizer) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.docChunkRepository = docChunkRepository;
        this.writer = writer;
        this.featureIdentityNormalizer = featureIdentityNormalizer;
    }

    /**
     * 保存文档切片
     */
    @Transactional
    public void saveDocumentChunks(String projectId, String versionId, String docName, String docPath,
            List<io.github.legacygraph.extractors.DocumentExtractor.DocumentChunk> chunks) {
        for (var chunk : chunks) {
            DocChunk dc = new DocChunk();
            dc.setId(UUID.randomUUID().toString());
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
    @Transactional
    public void buildBusinessGraph(String projectId, String versionId, DocUnderstandingAgent.BusinessFactExtraction facts) {
        buildBusinessGraph(projectId, versionId, facts, null, SourceType.DOC_AI.name());
    }

    /**
     * 构建业务图谱节点，并保留文档来源路径用于 AI 证据追溯。
     */
    @Transactional
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
    @Transactional
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
     * <p>收集所有匹配边后批量 MERGE，避免逐条创建导致的大量 Neo4j 往返。</p>
     */
    @Transactional
    public int mapFeaturesToCode(String projectId, String versionId) {
        // 获取所有 Feature 节点（含 DOC_AI 和 CODE_AI 来源），避免代码抽取的 Feature 成为孤岛
        List<GraphNode> docFeatures = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(), null, null, null, 0);

        // 获取所有已有的Page节点
        List<GraphNode> pages = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Page.name(), null, null, null, 0);

        // 获取所有已有的ApiEndpoint节点
        List<GraphNode> apis = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.ApiEndpoint.name(), null, null, null, 0);

        List<GraphEdge> candidateEdges = new ArrayList<>();
        // 基于名称语义相似度做简单匹配（向量服务已可用，后续可替换为 semanticSearch 提升精度）
        for (GraphNode feature : safeList(docFeatures)) {
            String featureName = normalizeSearchName(feature);
            if (featureName.isBlank()) {
                continue;
            }

            // 匹配Page
            for (GraphNode page : safeList(pages)) {
                String pageName = normalizeSearchName(page);
                if (pageName.isBlank()) {
                    continue;
                }

                double score = nameSimilarity(featureName, pageName);
                if (score > 0.6) {
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            feature.getId(), page.getId(),
                            EdgeType.EXPOSED_BY.name(),
                            feature.getNodeKey() + "->exposed_by->" + page.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score * 0.8),
                            score >= 0.8 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }

            // 匹配API
            for (GraphNode api : safeList(apis)) {
                String apiName = normalizeSearchName(api);
                if (apiName.isBlank()) {
                    continue;
                }
                double score = nameSimilarity(featureName, apiName);
                if (score > 0.5) {
                    candidateEdges.add(buildEdgePOJO(projectId, versionId,
                            feature.getId(), api.getId(),
                            EdgeType.IMPLEMENTED_BY.name(),
                            feature.getNodeKey() + "->implemented_by->" + api.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score * 0.7),
                            score >= 0.7 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM));
                }
            }
        }

        // 批量 MERGE 所有匹配边（避免空列表调用）
        if (candidateEdges.isEmpty()) {
            log.info("Mapped 0 feature-doc to code (no matches)");
            return 0;
        }
        int mappedCount = neo4jGraphDao.mergeEdgesBatch(candidateEdges);
        log.info("Mapped {} feature-doc to code (batch merged)", mappedCount);
        return mappedCount;
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
            String objName = normalizeEntityName(nodeDisplayName(obj));
            if (objName.isBlank()) continue;
            for (GraphNode tech : techEntities) {
                String techName = normalizeEntityName(nodeDisplayName(tech));
                if (techName.isBlank()) continue;
                double score = nameSimilarity(objName, techName);
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
                            BigDecimal.valueOf(score * 0.8),
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
        return normalizeEntityName(nodeDisplayName(node));
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
     * 简单名称相似度计算
     */
    private double nameSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0;
        }
        // 包含关系得分高
        if (a.contains(b) || b.contains(a)) {
            return 0.75;
        }
        // 简单Jaccard
        String[] aWords = a.split("[^a-zA-Z0-9一-龥]+");
        String[] bWords = b.split("[^a-zA-Z0-9一-龥]+");
        int intersection = 0;
        int union = aWords.length + bWords.length;
        for (String aw : aWords) {
            for (String bw : bWords) {
                if (aw.equalsIgnoreCase(bw)) {
                    intersection++;
                    union--;
                }
            }
        }
        if (union == 0) return 0;
        return (double) intersection / union;
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
        edge.setId(UUID.randomUUID().toString());
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
