package io.github.legacygraph.builder;

import io.github.legacygraph.builder.match.FieldSimilarityCalculator;
import io.github.legacygraph.common.EdgeType;
import io.github.legacygraph.common.NodeStatus;
import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.graph.GraphEdgeClaim;
import io.github.legacygraph.dto.graph.GraphNodeClaim;
import io.github.legacygraph.entity.GraphEdge;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.model.FrontendPageFact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 前端图谱构建器
 * 将抽取的前端页面、菜单、按钮、API调用构建为图谱节点和关系
 */
@Slf4j
@Component
public class FrontendGraphBuilder {

    private final Neo4jGraphDao neo4jGraphDao;
    private final EvidenceGraphWriter writer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FrontendGraphBuilder(Neo4jGraphDao neo4jGraphDao,
                               EvidenceGraphWriter writer) {
        this.neo4jGraphDao = neo4jGraphDao;
        this.writer = writer;
    }

    /**
     * 构建前端页面图谱（带源文件路径）
     */
    public void buildFrontendGraph(String projectId, String versionId, List<FrontendPageFact> pages, String sourcePath) {
        buildFrontendGraph(projectId, versionId, pages);
    }

    /**
     * 构建前端页面图谱
     */
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
        // G4: 写入 module / vueFilePath 属性，让 feature-view 可按模块过滤
        Map<String, Object> pageProps = new java.util.HashMap<>();
        pageProps.put("module", deriveModule(page.getComponentPath()));
        pageProps.put("vueFilePath", page.getComponentPath());
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
                NodeStatus.CONFIRMED,
                pageProps
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
                        permKey.toLowerCase(),
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
                String httpMethod = apiCall.getMethod() != null ? apiCall.getMethod().toUpperCase() : "GET";
                String normalizedPath = GraphBuilder.normalizePath(apiCall.getUrl());
                String apiKey = GraphBuilder.normalizeApiKey(httpMethod, normalizedPath);

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
                            permKey.toLowerCase(),
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

                // API调用 (G9: 使用 button 真实 httpMethod，不再硬编码 POST)
                if (button.getApiUrl() != null && !button.getApiUrl().isEmpty()) {
                    String normalizedPath = GraphBuilder.normalizePath(button.getApiUrl());
                    String btnHttpMethod = button.getHttpMethod() != null && !button.getHttpMethod().isBlank()
                            ? button.getHttpMethod().toUpperCase() : "POST";
                    if (button.getHttpMethod() == null || button.getHttpMethod().isBlank()) {
                        log.debug("Button '{}' apiUrl={} has unresolved httpMethod, defaulting to POST",
                                button.getText(), button.getApiUrl());
                    }
                    String apiKey = btnHttpMethod + " " + normalizedPath;
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
     * 处理独立抽取出来的前端API调用，匹配后端ApiEndpoint并创建调用关系
     */
    public void buildFrontendApiGraph(String projectId, String versionId,
            java.util.List<io.github.legacygraph.model.FrontendPageFact.FrontendApiCall> apiCalls) {
        log.info("Building frontend API graph: projectId={}, versionId={}, apiCalls={}",
                projectId, versionId, apiCalls.size());

        for (io.github.legacygraph.model.FrontendPageFact.FrontendApiCall apiCall : apiCalls) {
            // 规范化API key用于匹配
            String httpMethod = apiCall.getMethod() != null ? apiCall.getMethod().toUpperCase() : "GET";
            String normalizedPath = GraphBuilder.normalizePath(apiCall.getUrl());
            String apiKey = GraphBuilder.normalizeApiKey(httpMethod, normalizedPath);

            // 创建前端API节点
            String displayName = httpMethod + " " + apiCall.getUrl();
            GraphNode frontendApiNode = findOrCreateNode(
                    projectId, versionId,
                    NodeType.ApiEndpoint.name(),
                    "frontend:" + apiKey,
                    displayName,
                    displayName,
                    "前端调用API: " + displayName,
                    SourceType.FRONTEND_AST.name(),
                    apiCall.getSourceFile(),
                    apiCall.getLineNumber(),
                    apiCall.getLineNumber(),
                    BigDecimal.ONE,
                    NodeStatus.PENDING_CONFIRM
            );

            // 查找后端匹配的ApiEndpoint
            Optional<GraphNode> backendApiOpt = findBackendApi(projectId, versionId, apiKey);
            double score = calculateMatchScore(apiCall, backendApiOpt);

            if (backendApiOpt.isPresent() && score >= 0.6) {
                GraphNode backendApi = backendApiOpt.get();
                // 前端API -CALLS-> 后端API
                createEdge(projectId, versionId,
                        frontendApiNode.getId(), backendApi.getId(),
                        EdgeType.CALLS.name(),
                        "frontend:" + apiKey + "->calls->" + apiKey,
                        SourceType.FRONTEND_AST.name(),
                        BigDecimal.valueOf(score),
                        score >= 0.8 ? NodeStatus.CONFIRMED : NodeStatus.PENDING_CONFIRM
                );
                log.debug("Matched frontend API {} to backend with score {}", apiKey, score);
            }
        }

        log.info("Completed building frontend API graph, processed {} API calls", apiCalls.size());
    }

    /**
     * 查找后端已有的API节点
     */
    private Optional<GraphNode> findBackendApi(String projectId, String versionId, String apiKey) {
        return neo4jGraphDao.findNode(projectId, versionId, NodeType.ApiEndpoint.name(), apiKey);
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

            // 参数相似性打分：路径参数个数一致时加分（区分 /user/{id} 与 /user/{id}/role/{rid}）
            if (countPathParams(frontendPath) == countPathParams(backendPath)) {
                score += 0.1;
            }
        }

        // G7: 前后端 API 字段相似度（请求字段 + 响应字段）
        // 前端 FrontendApiCall 当前无 requestSchema/responseSchema 字段，暂传空数组
        String[] reqFields = new String[0];
        String[] respFields = new String[0];
        // 后端从 backendApi 的 properties JSON 中尝试提取 requestFields / responseFields（可能不存在，传空数组）
        String[] apiReqFields = new String[0];
        String[] apiRespFields = new String[0];
        String propsJson = backendApi.getProperties();
        if (propsJson != null && !propsJson.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> props = objectMapper.readValue(propsJson, Map.class);
                apiReqFields = toStringArray(props.get("requestFields"));
                apiRespFields = toStringArray(props.get("responseFields"));
            } catch (Exception e) {
                log.debug("Failed to parse backend API properties JSON for field similarity: {}", propsJson, e);
            }
        }
        score += FieldSimilarityCalculator.similarity(reqFields, apiReqFields) * 0.1;
        score += FieldSimilarityCalculator.similarity(respFields, apiRespFields) * 0.1;

        return Math.min(score, 1.2);
    }

    /**
     * 将 properties JSON 中提取的 Object 转为 String[]，支持 List 类型；其他类型返回空数组。
     */
    private String[] toStringArray(Object value) {
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            String[] result = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                result[i] = item == null ? null : item.toString();
            }
            return result;
        }
        return new String[0];
    }

    /**
     * 统计路径中的参数占位符数量（形如 {id}）
     */
    private int countPathParams(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        int count = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{[^}]+\\}").matcher(path);
        while (m.find()) {
            count++;
        }
        return count;
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
     * 查找或创建节点（委托给 EvidenceGraphWriter）。
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status) {
        return findOrCreateNode(projectId, versionId, nodeType, nodeKey, nodeName,
                displayName, description, sourceType, sourcePath,
                startLine, endLine, confidence, status, null);
    }

    /**
     * 查找或创建节点（带额外属性，G4：module/vueFilePath 等）。
     */
    private GraphNode findOrCreateNode(String projectId, String versionId,
            String nodeType, String nodeKey, String nodeName,
            String displayName, String description,
            String sourceType, String sourcePath,
            Integer startLine, Integer endLine,
            BigDecimal confidence, NodeStatus status,
            Map<String, Object> extraProperties) {
        String propsJson = null;
        if (extraProperties != null && !extraProperties.isEmpty()) {
            try {
                propsJson = objectMapper.writeValueAsString(extraProperties);
            } catch (Exception e) {
                log.warn("Failed to serialize extra properties for {}: {}", nodeKey, e.getMessage());
            }
        }
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
                .properties(propsJson)
                .build());
    }

    /**
     * G4: 从前端文件路径提取模块名（第一层目录，如 views/user/Index.vue → user）。
     */
    private static String deriveModule(String componentPath) {
        if (componentPath == null || componentPath.isBlank()) {
            return "UNCLASSIFIED";
        }
        String normalized = componentPath.replace('\\', '/');
        // 去掉前导 ./ 或 /
        normalized = normalized.replaceAll("^\\./+", "").replaceAll("^/+", "");
        String[] segments = normalized.split("/");
        for (String seg : segments) {
            if (seg.isBlank() || "src".equals(seg) || "views".equals(seg)
                    || "pages".equals(seg) || "components".equals(seg)) {
                continue;
            }
            return seg.toLowerCase();
        }
        return "UNCLASSIFIED";
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
     * 归一化page key
     */
    private String normalizePageKey(String routePath, String componentPath) {
        return (routePath != null ? routePath : "") + "#" + (componentPath != null ? componentPath : "");
    }
}
