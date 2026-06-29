package io.github.legacygraph.builder;

import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.model.FrontendPageFact;
import io.github.legacygraph.repository.GraphEdgeRepository;
import io.github.legacygraph.repository.GraphNodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 前端图谱构建器
 * 将抽取的前端页面、菜单、按钮、API调用构建为图谱节点和关系
 */
@Slf4j
@Component
public class FrontendGraphBuilder {

    private final GraphNodeRepository graphNodeRepository;
    private final GraphEdgeRepository graphEdgeRepository;

    public FrontendGraphBuilder(GraphNodeRepository graphNodeRepository,
                               GraphEdgeRepository graphEdgeRepository) {
        this.graphNodeRepository = graphNodeRepository;
        this.graphEdgeRepository = graphEdgeRepository;
    }

    /**
     * 构建前端页面图谱（带源文件路径）
     */
    @Transactional
    public void buildFrontendGraph(String projectId, String versionId, List<FrontendPageFact> pages, String sourcePath) {
        buildFrontendGraph(projectId, versionId, pages);
    }

    /**
     * 构建前端页面图谱
     */
    @Transactional
    public void buildFrontendGraph(String projectId, String versionId, List<FrontendPageFact> pages) {
        for (FrontendPageFact page : pages) {
            buildPageNode(projectId, versionId, page, null);
        }
    }

    /**
     * 构建单个页面节点和子节点
     */
    private GraphNode buildPageNode(String projectId, String versionId,
            FrontendPageFact page, GraphNode parentMenu) {
        // 创建Page节点
        String pageKey = normalizePageKey(page.getRoutePath(), page.getComponentPath());
        GraphNode pageNode = findOrCreateNode(
                projectId, versionId,
                NodeType.Page.name(),
                pageKey,
                page.getRouteName() != null ? page.getRouteName() : page.getRoutePath(),
                page.getTitle(),
                "前端路由: " + page.getRoutePath(),
                SourceType.FRONTEND_AST.name(),
                page.getComponentPath(),
                null,
                null,
                BigDecimal.ONE,
                NodeStatus.CONFIRMED
        );

        // 菜单/父节点包含此页面
        if (parentMenu != null) {
            createEdge(projectId, versionId,
                    parentMenu.getId(), pageNode.getId(),
                    EdgeType.CONTAINS.name(),
                    parentMenu.getNodeKey() + "->contains->" + pageKey,
                    SourceType.FRONTEND_AST.name(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );
        }

        // 创建菜单节点
        if (page.getTitle() != null && !page.getTitle().isEmpty()) {
            String menuKey = page.getRoutePath();
            GraphNode menuNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.Menu.name(),
                    menuKey,
                    page.getTitle(),
                    page.getTitle(),
                    null,
                    SourceType.FRONTEND_AST.name(),
                    page.getComponentPath(),
                    null,
                    null,
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );

            // Menu -CONTAINS-> Page
            createEdge(projectId, versionId,
                    menuNode.getId(), pageNode.getId(),
                    EdgeType.CONTAINS.name(),
                    menuKey + "->contains->" + pageKey,
                    SourceType.FRONTEND_AST.name(),
                    BigDecimal.ONE,
                    NodeStatus.CONFIRMED
            );

            // 权限
            if (page.getPermission() != null && !page.getPermission().isEmpty()) {
                String permKey = page.getPermission();
                GraphNode permNode = findOrCreateNode(
                        projectId, versionId,
                        NodeType.Permission.name(),
                        permKey,
                        permKey,
                        permKey,
                        null,
                        SourceType.FRONTEND_AST.name(),
                        page.getComponentPath(),
                        null,
                        null,
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED
                );

                createEdge(projectId, versionId,
                        menuNode.getId(), permNode.getId(),
                        EdgeType.REQUIRES_PERMISSION.name(),
                        menuKey + "->requires_permission->" + permKey,
                        SourceType.FRONTEND_AST.name(),
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED
                );
            }
        }

        // 处理API调用
        if (page.getApiCalls() != null) {
            for (FrontendPageFact.FrontendApiCall apiCall : page.getApiCalls()) {
                String normalizedPath = GraphBuilder.normalizePath(apiCall.getUrl());
                String apiKey = apiCall.getMethod().toUpperCase() + " " + normalizedPath;

                // 查找后端匹配的ApiEndpoint
                Optional<GraphNode> backendApiOpt = findBackendApi(projectId, versionId, apiKey);
                double score = calculateMatchScore(apiCall, backendApiOpt);

                if (backendApiOpt.isPresent()) {
                    GraphNode backendApi = backendApiOpt.get();
                    // Page -CALLS-> ApiEndpoint
                    createEdge(projectId, versionId,
                            pageNode.getId(), backendApi.getId(),
                            EdgeType.CALLS.name(),
                            pageKey + "->calls->" + apiKey,
                            SourceType.FRONTEND_AST.name(),
                            BigDecimal.valueOf(score),
                            score >= 0.8 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
                    );
                    log.debug("Matched frontend API {} to backend with score {}", apiKey, score);
                }
            }
        }

        // 处理按钮
        if (page.getButtons() != null) {
            for (FrontendPageFact.FrontendButton button : page.getButtons()) {
                String buttonKey = pageKey + "." + button.getText() + "#" + button.getLineNumber();
                GraphNode buttonNode = findOrCreateNode(
                        projectId, versionId,
                        NodeType.Button.name(),
                        buttonKey,
                        button.getText() != null ? button.getText() : button.getClickMethod(),
                        button.getText(),
                        null,
                        SourceType.FRONTEND_AST.name(),
                        page.getComponentPath(),
                        button.getLineNumber(),
                        null,
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED
                );

                // Page -CONTAINS-> Button
                createEdge(projectId, versionId,
                        pageNode.getId(), buttonNode.getId(),
                        EdgeType.CONTAINS.name(),
                        pageKey + "->contains->" + buttonKey,
                        SourceType.FRONTEND_AST.name(),
                        BigDecimal.ONE,
                        NodeStatus.CONFIRMED
                );

                // 权限
                if (button.getPermission() != null && !button.getPermission().isEmpty()) {
                    String permKey = button.getPermission();
                    GraphNode permNode = findOrCreateNode(
                            projectId, versionId,
                            NodeType.Permission.name(),
                            permKey,
                            permKey,
                            permKey,
                            null,
                            SourceType.FRONTEND_AST.name(),
                            page.getComponentPath(),
                            button.getLineNumber(),
                            null,
                            BigDecimal.ONE,
                            NodeStatus.CONFIRMED
                    );

                    createEdge(projectId, versionId,
                            buttonNode.getId(), permNode.getId(),
                            EdgeType.REQUIRES_PERMISSION.name(),
                            buttonKey + "->requires_permission->" + permKey,
                            SourceType.FRONTEND_AST.name(),
                            BigDecimal.ONE,
                            NodeStatus.CONFIRMED
                    );
                }

                // API调用
                if (button.getApiUrl() != null && !button.getApiUrl().isEmpty()) {
                    String normalizedPath = GraphBuilder.normalizePath(button.getApiUrl());
                    String apiKey = "POST " + normalizedPath; // 默认POST
                    Optional<GraphNode> backendApiOpt = findBackendApi(projectId, versionId, apiKey);
                    if (backendApiOpt.isPresent()) {
                        createEdge(projectId, versionId,
                                buttonNode.getId(), backendApiOpt.get().getId(),
                                EdgeType.CALLS.name(),
                                buttonKey + "->calls->" + apiKey,
                                SourceType.FRONTEND_AST.name(),
                                BigDecimal.ONE,
                                NodeStatus.CONFIRMED
                        );
                    }
                }
            }
        }

        return pageNode;
    }

    /**
     * 构建前端API与后端API的关联图谱
     */
    @Transactional
    public void buildFrontendApiGraph(String projectId, String versionId,
            java.util.List<io.github.legacygraph.model.FrontendPageFact.FrontendApiCall> apiCalls) {
        log.info("buildFrontendApiGraph placeholder: projectId={}, versionId={}, apiCalls={}",
                projectId, versionId, apiCalls.size());
    }

    /**
     * 查找后端已有的API节点
     */
    private Optional<GraphNode> findBackendApi(String projectId, String versionId, String apiKey) {
        return graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, NodeType.ApiEndpoint.name())
                .eq(GraphNode::getNodeKey, apiKey)
                .oneOpt();
    }

    /**
     * 计算前后端API匹配分数
     * 根据文档规则:
     * - HTTP Method 完全一致: +0.3
     * - Path 完全一致: +0.5
     * - Path 参数归一后匹配: +0.4
     * - request 参数字段相似: +0.1
     * - response 字段相似: +0.1
     * - baseURL 匹配: +0.1
     *
     * >= 0.8: 确认关系
     * 0.6 - 0.8: 待人工确认
     * < 0.6: 不建立
     */
    private double calculateMatchScore(FrontendPageFact.FrontendApiCall frontendCall, Optional<GraphNode> backendApiOpt) {
        if (backendApiOpt.isEmpty()) {
            return 0;
        }

        GraphNode backendApi = backendApiOpt.get();
        String frontendKey = GraphBuilder.normalizeApiKey(
                frontendCall.getMethod() != null ? frontendCall.getMethod().toUpperCase() : "GET",
                frontendCall.getUrl()
        );

        String backendKey = backendApi.getNodeKey();

        double score = 0;

        // 完全匹配
        if (frontendKey.equals(backendKey)) {
            return 1.0;
        }

        // 拆分方法和路径
        String[] frontendParts = frontendKey.split(" ", 2);
        String[] backendParts = backendKey.split(" ", 2);

        if (frontendParts.length == 2 && backendParts.length == 2) {
            String frontendMethod = frontendParts[0];
            String backendMethod = backendParts[0];
            String frontendPath = frontendParts[1];
            String backendPath = backendParts[1];

            // HTTP method 匹配
            if (frontendMethod.equalsIgnoreCase(backendMethod)) {
                score += 0.3;
            }

            // 路径完全匹配
            if (frontendPath.equals(backendPath)) {
                score += 0.5;
            } else if (normalizedPathsMatch(frontendPath, backendPath)) {
                // 参数归一后匹配
                score += 0.4;
            }

            // TODO: 参数相似性打分 (需要抽取参数)
        }

        return Math.min(score, 1.0);
    }

    /**
     * 归一化路径后比较
     * 不同参数名但格式相同也算匹配
     */
    private boolean normalizedPathsMatch(String path1, String path2) {
        // 将 {xxx} 统一化，比较静态部分
        String norm1 = path1.replaceAll("\\{[^}]+\\}", "{param}");
        String norm2 = path2.replaceAll("\\{[^}]+\\}", "{param}");
        return norm1.equals(norm2);
    }

    /**
     * 查找或创建节点
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status) {
        GraphNode existing = graphNodeRepository.lambdaQuery()
                .eq(GraphNode::getProjectId, projectId)
                .eq(GraphNode::getVersionId, versionId)
                .eq(GraphNode::getNodeType, nodeType)
                .eq(GraphNode::getNodeKey, nodeKey)
                .oneOpt()
                .orElse(null);

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
        return node;
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
        return edge;
    }

    /**
     * 归一化page key
     */
    private String normalizePageKey(String routePath, String componentPath) {
        return (routePath != null ? routePath : "") + "#" + (componentPath != null ? componentPath : "");
    }
}
