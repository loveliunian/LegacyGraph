package io.github.legacygraph.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.dto.plugin.ExternalPluginDescriptor;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.adapter.ScanContext;
import io.github.legacygraph.verification.VerificationResult;
import io.github.legacygraph.verification.VerifiedEdge;
import io.github.legacygraph.verification.VerifiedNodeProperty;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP 插件适配器 —— 动态适配所有通过 PluginRegistry 注册的外部 MCP 插件。
 * <p>
 * 这是一个"多合一"适配器：不固定 pluginId，而是根据传入的 pluginIds 从 PluginRegistry
 * 查找对应的 ExternalPluginDescriptor，调用其 MCP 端点执行验证。
 * </p>
 * <p>
 * 不实现 {@link PluginAdapter} 接口，避免被 Spring 当作固定适配器收集。
 * 由 {@link PluginInvocationService} 显式注入并按 pluginId 逐个调用。
 * </p>
 * <p>
 * MCP 调用流程：
 * <ol>
 *   <li>调用 {@code tools/call} 方法，参数传入 projectId/versionId/baseDir</li>
 *   <li>解析返回的 JSON，提取 confirmedEdges / missingEdges / suspiciousEdges / nodeProperties</li>
 *   <li>封装为 {@link VerificationResult} 返回</li>
 * </ol>
 * </p>
 */
@Slf4j
public class McpPluginAdapter {

    private static final int HTTP_TIMEOUT_SECONDS = 30;

    private final PluginRegistry pluginRegistry;
    private final ObjectMapper objectMapper;
    private final Neo4jGraphDao neo4jGraphDao;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .build();

    /** 传给插件的本地图谱节点 key 上限（避免请求体过大） */
    private static final int MAX_NODE_KEYS = 2000;

    public McpPluginAdapter(PluginRegistry pluginRegistry, ObjectMapper objectMapper,
                             Neo4jGraphDao neo4jGraphDao) {
        this.pluginRegistry = pluginRegistry;
        this.objectMapper = objectMapper;
        this.neo4jGraphDao = neo4jGraphDao;
    }

    /**
     * 是否支持此扫描上下文
     */
    public boolean supports(ScanContext context) {
        return context != null && context.getBaseDir() != null;
    }

    /**
     * 健康检查：至少有一个外部 MCP 插件注册
     */
    public boolean checkHealth() {
        return !pluginRegistry.listAll().isEmpty();
    }

    /**
     * 针对特定插件 ID 执行 MCP 调用。
     * 此方法供 PluginInvocationService 在发现 PluginRegistry 中有外部 MCP 插件时直接调用。
     *
     * @param pluginId   插件 ID
     * @param projectId  项目 ID
     * @param versionId  版本 ID
     * @param context    扫描上下文
     * @return 验证结果
     */
    public VerificationResult invokeForPlugin(String pluginId, String projectId, String versionId, ScanContext context) {
        ExternalPluginDescriptor desc = pluginRegistry.getExternal(pluginId);
        if (desc == null) {
            log.warn("插件 {} 未在 Registry 中找到，跳过", pluginId);
            return VerificationResult.empty(pluginId);
        }

        String endpoint = desc.getMcpEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("插件 {} 无 MCP 端点配置，跳过", pluginId);
            return VerificationResult.empty(pluginId);
        }

        log.info("调用 MCP 插件 {}: endpoint={}", pluginId, endpoint);
        try {
            // 查询本地图谱节点 key 清单，传给插件做交叉验证对齐
            List<String> knownNodeKeys = collectLocalNodeKeys(projectId, versionId);
            log.info("插件 {} 上下文: 传入 {} 个本地节点 key", pluginId, knownNodeKeys.size());

            // 构造 MCP tools/call 请求
            String requestBody = buildMcpCallRequest(pluginId, projectId, versionId, context, knownNodeKeys);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            if (desc.getAuth() != null && !desc.getAuth().isBlank()) {
                builder.header("Authorization", desc.getAuth());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("MCP 插件 {} 返回 HTTP {}: {}", pluginId, response.statusCode(), truncate(response.body()));
                return VerificationResult.empty(pluginId);
            }

            return parseMcpResponse(pluginId, response.body());
        } catch (Exception e) {
            log.warn("MCP 插件 {} 调用异常: {}", pluginId, e.getMessage());
            return VerificationResult.empty(pluginId);
        }
    }

    /** 构造 MCP JSON-RPC tools/call 请求体（含本地图谱节点 key 清单） */
    private String buildMcpCallRequest(String pluginId, String projectId, String versionId,
                                        ScanContext context, List<String> knownNodeKeys) {
        String baseDir = context.getBaseDir() != null ? context.getBaseDir() : "";
        String backendDir = context.getBackendDir() != null ? context.getBackendDir() : "";
        String frontendDir = context.getFrontendDir() != null ? context.getFrontendDir() : "";
        // 将节点 key 列表序列化为 JSON 数组字符串
        String knownNodeKeysJson = knownNodeKeys.stream()
                .map(k -> "\"" + escapeJson(k) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"verify_graph\",\"arguments\":{"
                + "\"projectId\":\"" + escapeJson(projectId) + "\","
                + "\"versionId\":\"" + escapeJson(versionId) + "\","
                + "\"baseDir\":\"" + escapeJson(baseDir) + "\","
                + "\"backendDir\":\"" + escapeJson(backendDir) + "\","
                + "\"frontendDir\":\"" + escapeJson(frontendDir) + "\","
                + "\"knownNodeKeys\":" + knownNodeKeysJson
                + "}}}";
    }

    /**
     * 查询本地图谱节点 key 清单（按版本号），用于传给外部插件做交叉验证对齐。
     * 限制最多 MAX_NODE_KEYS 个，按 nodeType 分批查询以覆盖关键节点类型。
     */
    private List<String> collectLocalNodeKeys(String projectId, String versionId) {
        List<String> keys = new ArrayList<>();
        // 查询关键节点类型：Service、Controller、Method、Table、Mapper、Api、Page
        String[] keyTypes = {"Service", "Controller", "Method", "Table", "Mapper", "Api", "Page"};
        int perTypeLimit = MAX_NODE_KEYS / keyTypes.length;
        for (String nodeType : keyTypes) {
            try {
                List<GraphNode> nodes = neo4jGraphDao.queryNodes(projectId, versionId,
                        nodeType, null, null, null, null, perTypeLimit);
                for (GraphNode node : nodes) {
                    if (node.getNodeKey() != null && !node.getNodeKey().isBlank()) {
                        keys.add(node.getNodeKey());
                    }
                    if (keys.size() >= MAX_NODE_KEYS) break;
                }
                if (keys.size() >= MAX_NODE_KEYS) break;
            } catch (Exception e) {
                log.debug("查询节点 key 失败 (type={}): {}", nodeType, e.getMessage());
            }
        }
        return keys;
    }

    /** 解析 MCP 响应，提取验证结果 */
    private VerificationResult parseMcpResponse(String pluginId, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode result = root.path("result");
            if (result.isMissingNode()) {
                result = root; // 某些 MCP 响应可能不包 result
            }

            List<VerifiedEdge> confirmedEdges = parseEdges(result.path("confirmedEdges"), pluginId);
            List<VerifiedEdge> missingEdges = parseEdges(result.path("missingEdges"), pluginId);
            List<VerifiedEdge> suspiciousEdges = parseEdges(result.path("suspiciousEdges"), pluginId);
            List<VerifiedNodeProperty> nodeProperties = parseNodeProperties(result.path("nodeProperties"), pluginId);

            int totalChecked = confirmedEdges.size() + suspiciousEdges.size();
            int totalConfirmed = confirmedEdges.size();

            log.info("MCP 插件 {} 返回: 确认 {} 条, 补漏 {} 条, 可疑 {} 条, 属性 {} 个",
                    pluginId, confirmedEdges.size(), missingEdges.size(), suspiciousEdges.size(), nodeProperties.size());

            return VerificationResult.builder()
                    .adapterName(pluginId)
                    .confirmedEdges(confirmedEdges)
                    .missingEdges(missingEdges)
                    .suspiciousEdges(suspiciousEdges)
                    .nodeProperties(nodeProperties)
                    .totalChecked(totalChecked)
                    .totalConfirmed(totalConfirmed)
                    .build();
        } catch (Exception e) {
            log.warn("MCP 插件 {} 响应解析失败: {}", pluginId, e.getMessage());
            return VerificationResult.empty(pluginId);
        }
    }

    private List<VerifiedEdge> parseEdges(JsonNode edgesNode, String sourceTool) {
        List<VerifiedEdge> edges = new ArrayList<>();
        if (!edgesNode.isArray()) return edges;
        for (JsonNode node : edgesNode) {
            try {
                edges.add(VerifiedEdge.builder()
                        .fromNodeKey(node.path("fromNodeKey").asText(""))
                        .toNodeKey(node.path("toNodeKey").asText(""))
                        .edgeType(node.path("edgeType").asText("CALLS"))
                        .confidence(node.has("confidence") ? node.path("confidence").asDouble(0.85) : 0.85)
                        .sourceTool(sourceTool)
                        .build());
            } catch (Exception e) {
                log.debug("跳过无法解析的边: {}", node);
            }
        }
        return edges;
    }

    private List<VerifiedNodeProperty> parseNodeProperties(JsonNode propsNode, String sourceTool) {
        List<VerifiedNodeProperty> props = new ArrayList<>();
        if (!propsNode.isArray()) return props;
        for (JsonNode node : propsNode) {
            try {
                props.add(VerifiedNodeProperty.builder()
                        .nodeKey(node.path("nodeKey").asText(""))
                        .propertyName(node.path("propertyName").asText(""))
                        .propertyValue(node.path("propertyValue"))
                        .sourceTool(sourceTool)
                        .build());
            } catch (Exception e) {
                log.debug("跳过无法解析的属性: {}", node);
            }
        }
        return props;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
