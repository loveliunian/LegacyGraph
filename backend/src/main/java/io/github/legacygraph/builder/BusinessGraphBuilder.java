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

    public BusinessGraphBuilder(Neo4jGraphDao neo4jGraphDao,
                              DocChunkRepository docChunkRepository,
                              EvidenceGraphWriter writer) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.docChunkRepository = docChunkRepository;
        this.writer = writer;
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
        buildBusinessGraph(projectId, versionId, facts, null);
    }

    /**
     * 构建业务图谱节点，并保留文档来源路径用于 AI 证据追溯。
     */
    @Transactional
    public void buildBusinessGraph(String projectId, String versionId,
                                   DocUnderstandingAgent.BusinessFactExtraction facts,
                                   String sourcePath) {
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
                    SourceType.DOC_AI.name(),
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(domain.getConfidence()),
                    domain.getConfidence() >= 0.7 ? NodeStatus.PENDING_CONFIRM : NodeStatus.PENDING_CONFIRM
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
                    SourceType.DOC_AI.name(),
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(process.getConfidence()),
                    process.getConfidence() >= 0.7 ? NodeStatus.PENDING_CONFIRM : NodeStatus.PENDING_CONFIRM
            );

            // 每个步骤对应一个功能
            if (process.getSteps() != null) {
                for (String step : process.getSteps()) {
                    GraphNode featureNode = findOrCreateNode(
                            projectId, versionId,
                            NodeType.Feature.name(),
                            process.getName() + "/" + step,
                            step,
                            step,
                            null,
                            SourceType.DOC_AI.name(),
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
                            SourceType.DOC_AI.name(),
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
                    SourceType.DOC_AI.name(),
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(obj.getConfidence()),
                    obj.getConfidence() >= 0.7 ? NodeStatus.PENDING_CONFIRM : NodeStatus.PENDING_CONFIRM
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
                    SourceType.DOC_AI.name(),
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(rule.getConfidence()),
                    rule.getConfidence() >= 0.7 ? NodeStatus.PENDING_CONFIRM : NodeStatus.PENDING_CONFIRM
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
                    SourceType.DOC_AI.name(),
                    sourcePath,
                    null,
                    null,
                    BigDecimal.valueOf(0.8),
                    NodeStatus.PENDING_CONFIRM
            );
        }

        log.info("Built business graph: {} domains, {} processes, {} objects, {} rules",
                facts.getBusinessDomains().size(),
                facts.getBusinessProcesses().size(),
                facts.getBusinessObjects().size(),
                facts.getBusinessRules().size());
    }

    /**
     * 功能映射：将文档中的功能映射到已有的前端页面和后端接口
     */
    @Transactional
    public void mapFeaturesToCode(String projectId, String versionId) {
        // 获取所有文档抽取的Feature节点
        List<GraphNode> docFeatures = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Feature.name(), SourceType.DOC_AI.name(), null, null, 0);

        // 获取所有已有的Page节点
        List<GraphNode> pages = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.Page.name(), null, null, null, 0);

        // 获取所有已有的ApiEndpoint节点
        List<GraphNode> apis = neo4jGraphDao.queryNodes(
                projectId, versionId, NodeType.ApiEndpoint.name(), null, null, null, 0);

        int mappedCount = 0;
        // 基于名称语义相似度做简单匹配（向量服务已可用，后续可替换为 semanticSearch 提升精度）
        for (GraphNode feature : docFeatures) {
            String featureName = feature.getNodeName().toLowerCase();

            // 匹配Page
            for (GraphNode page : pages) {
                String pageName = page.getDisplayName() != null ?
                        page.getDisplayName().toLowerCase() :
                        page.getNodeName().toLowerCase();

                double score = nameSimilarity(featureName, pageName);
                if (score > 0.6) {
                    // 业务功能 EXPOSED_BY 页面
                    createEdge(projectId, versionId,
                            feature.getId(), page.getId(),
                            EdgeType.EXPOSED_BY.name(),
                            feature.getNodeKey() + "->exposed_by->" + page.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score * 0.8),
                            score >= 0.8 ? NodeStatus.PENDING_CONFIRM : NodeStatus.PENDING_CONFIRM
                    );
                    mappedCount++;
                }
            }

            // 匹配API
            for (GraphNode api : apis) {
                String apiName = api.getDisplayName() != null ?
                        api.getDisplayName().toLowerCase() :
                        api.getNodeName().toLowerCase();
                double score = nameSimilarity(featureName, apiName);
                if (score > 0.5) {
                    createEdge(projectId, versionId,
                            feature.getId(), api.getId(),
                            EdgeType.IMPLEMENTED_BY.name(),
                            feature.getNodeKey() + "->implemented_by->" + api.getNodeKey(),
                            SourceType.AI_INFERENCE.name(),
                            BigDecimal.valueOf(score * 0.7),
                            score >= 0.7 ? NodeStatus.PENDING_CONFIRM : NodeStatus.PENDING_CONFIRM
                    );
                    mappedCount++;
                }
            }
        }

        log.info("Mapped {} feature-doc to code", mappedCount);
    }

    /**
     * 简单名称相似度计算
     */
    private double nameSimilarity(String a, String b) {
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
}
