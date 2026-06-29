package io.github.legacygraph.builder;

import io.github.legacygraph.agent.DocUnderstandingAgent;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.entity.DocChunk;
import io.github.legacygraph.entity.EdgeEvidence;
import io.github.legacygraph.entity.Evidence;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.entity.NodeEvidence;
import io.github.legacygraph.repository.DocChunkRepository;
import io.github.legacygraph.repository.EdgeEvidenceRepository;
import io.github.legacygraph.repository.EvidenceRepository;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import io.github.legacygraph.repository.NodeEvidenceRepository;
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
 * 将文档抽取的业务事实构建为业务图谱节点和关系
 * 所有节点/边创建时自动关联证据
 */
@Slf4j
@Component
public class BusinessGraphBuilder {

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;
    private final DocChunkRepository docChunkRepository;
    private final EvidenceRepository evidenceRepository;
    private final NodeEvidenceRepository nodeEvidenceRepository;
    private final EdgeEvidenceRepository edgeEvidenceRepository;

    public BusinessGraphBuilder(GraphNodeRepository graphNodeRepository,
                              GraphEdgeRepository graphEdgeRepository,
                              DocChunkRepository docChunkRepository,
                              EvidenceRepository evidenceRepository,
                              NodeEvidenceRepository nodeEvidenceRepository,
                              EdgeEvidenceRepository edgeEvidenceRepository) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
        this.docChunkRepository = docChunkRepository;
        this.evidenceRepository = evidenceRepository;
        this.nodeEvidenceRepository = nodeEvidenceRepository;
        this.edgeEvidenceRepository = edgeEvidenceRepository;
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
                    null,
                    null,
                    null,
                    BigDecimal.valueOf(domain.getConfidence()),
                    domain.getConfidence() >= 0.7 ? NodeStatus.PENDING_CONFIRM : NodeStatus.PENDING_CONFIRM
            );
            domainNodes.add(domainNode);
        }

        // 构建业务流程，并关联到所属业务域
        // 简单策略：第 i 个流程关联到第 i % domainCount 个业务域
        int domainIndex = 0;
        int domainCount = domainNodes.size();
        for (var process : facts.getBusinessProcesses()) {
            GraphNode processNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.BusinessProcess.name(),
                    process.getName(),
                    process.getName(),
                    process.getName(),
                    process.getDescription(),
                    SourceType.DOC_AI.name(),
                    null,
                    null,
                    null,
                    BigDecimal.valueOf(process.getConfidence()),
                    process.getConfidence() >= 0.7 ? NodeStatus.PENDING_CONFIRM : NodeStatus.PENDING_CONFIRM
            );

            // 关联业务流程到业务域
            if (domainCount > 0) {
                GraphNode domainNode = domainNodes.get(domainIndex % domainCount);
                createEdge(projectId, versionId,
                        domainNode.getId(), processNode.getId(),
                        EdgeType.CONTAINS.name(),
                        domainNode.getNodeKey() + "->contains->" + processNode.getNodeKey(),
                        SourceType.DOC_AI.name(),
                        BigDecimal.valueOf(process.getConfidence() * 0.9),
                        NodeStatus.PENDING_CONFIRM
                );
            }
            domainIndex++;

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
                            null,
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
                    null,
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
                    null,
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
                    null,
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
        List<GraphNode> docFeatures = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, NodeType.Feature.name())
                .eq(GraphNode::getSourceType, SourceType.DOC_AI.name())
                .list();

        // 获取所有已有的Page节点
        List<GraphNode> pages = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, NodeType.Page.name())
                .list();

        // 获取所有已有的ApiEndpoint节点
        List<GraphNode> apis = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, NodeType.ApiEndpoint.name())
                .list();

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
     * 查找或创建节点，自动关联证据
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GraphNode> nodeQuery =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        nodeQuery.eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, nodeType)
                .eq(GraphNode::getNodeKey, nodeKey)
                .last("LIMIT 1");
        GraphNode existing = graphNodeRepository.selectOne(nodeQuery);

        if (existing != null) {
            return existing;
        }

        GraphNode node = new GraphNode();
        node.setId(UUID.randomUUID().toString());
        node.setProjectId(projectId);
        node.setVersionId(versionId);
        node.setNodeType(nodeType);
        node.setNodeKey(nodeKey);
        node.setNodeName(nodeName);
        node.setDisplayName(displayName);
        node.setDescription(description);
        node.setSourceType(sourceType);
        node.setSourcePath(sourcePath);
        node.setStartLine(startLine);
        node.setEndLine(endLine);
        node.setConfidence(confidence);
        node.setStatus(status.name());
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());

        graphNodeRepository.insert(node);

        // 创建证据并关联 - 即使没有 sourcePath，也为 DOC_AI 类型创建
        createEvidenceForNode(node, sourceType, sourcePath, startLine, endLine);

        return node;
    }

    /**
     * 为节点创建证据记录并建立关联
     */
    private void createEvidenceForNode(GraphNode node, String sourceType, String sourcePath,
            Integer startLine, Integer endLine) {
        Evidence evidence = new Evidence();
        evidence.setId(UUID.randomUUID().toString());
        evidence.setProjectId(node.getProjectId());
        evidence.setVersionId(node.getVersionId());
        evidence.setEvidenceType(mapSourceTypeToEvidenceType(sourceType));
        evidence.setSourcePath(sourcePath);
        evidence.setSourceName(node.getDisplayName());
        evidence.setStartLine(startLine);
        evidence.setEndLine(endLine);
        evidence.setCreatedAt(LocalDateTime.now());
        evidenceRepository.insert(evidence);

        // 创建节点-证据关联
        NodeEvidence nodeEvidence = new NodeEvidence();
        nodeEvidence.setId(UUID.randomUUID().toString());
        nodeEvidence.setNodeId(node.getId());
        nodeEvidence.setEvidenceId(evidence.getId());
        nodeEvidence.setRelationType("PRIMARY_SOURCE");
        nodeEvidence.setCreatedAt(LocalDateTime.now());
        nodeEvidenceRepository.insert(nodeEvidence);
    }

    /**
     * 将源码类型映射为证据类型
     */
    private String mapSourceTypeToEvidenceType(String sourceType) {
        if (sourceType == null) return "doc";
        return switch (sourceType) {
            case "CODE_AST" -> "code";
            case "MYBATIS_XML", "SQL_PARSE" -> "sql";
            case "FRONTEND_AST" -> "ui";
            case "DB_METADATA" -> "db";
            case "DOCUMENT", "DOC_AI" -> "doc";
            case "AI_INFERENCE" -> "ai";
            default -> sourceType.toLowerCase();
        };
    }

    /**
     * 创建边
     */
    private GraphEdge createEdge(String projectId, String versionId,
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
        edge.setStatus(status.name());
        edge.setCreatedAt(LocalDateTime.now());
        edge.setUpdatedAt(LocalDateTime.now());

        graphEdgeRepository.insert(edge);

        // 从源节点继承证据（创建关联）
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NodeEvidence> neQuery =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        neQuery.eq(NodeEvidence::getNodeId, fromNodeId);
        List<NodeEvidence> nodeEvidences = nodeEvidenceRepository.selectList(neQuery);
        for (NodeEvidence ne : nodeEvidences) {
            EdgeEvidence edgeEvidence = new EdgeEvidence();
            edgeEvidence.setId(UUID.randomUUID().toString());
            edgeEvidence.setEdgeId(edge.getId());
            edgeEvidence.setEvidenceId(ne.getEvidenceId());
            edgeEvidence.setRelationType("INHERITED");
            edgeEvidence.setCreatedAt(LocalDateTime.now());
            edgeEvidenceRepository.insert(edgeEvidence);
        }

        return edge;
    }
}
