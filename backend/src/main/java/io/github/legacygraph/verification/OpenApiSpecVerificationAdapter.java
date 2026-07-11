package io.github.legacygraph.verification;

import io.github.legacygraph.common.NodeType;
import io.github.legacygraph.common.SourceType;
import io.github.legacygraph.dao.Neo4jGraphDao;
import io.github.legacygraph.entity.GraphNode;
import io.github.legacygraph.extractors.adapter.ScanContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 规范对比验证适配器 — 对照 SPEC 与 CODE_AST 两种来源的 ApiEndpoint 节点。
 * <p>
 * 流程：
 * <ol>
 *   <li>读取 {@link io.github.legacygraph.extractors.adapter.OpenApiSpecAdapter} 抽取的 {@code ApiEndpoint(SPEC)} 节点</li>
 *   <li>读取 {@code JavaCodeAdapter} 抽取的 {@code ApiEndpoint(CODE_AST)} 节点</li>
 *   <li>按 path+method（nodeKey）做 join，分类为 MATCHED / MISSING / DRIFTED / EXTRA</li>
 *   <li>MATCHED → confirmedEdges（VERIFIED_BY）</li>
 *   <li>DRIFTED → suspiciousEdges + 节点标记 suspicious=true</li>
 *   <li>MISSING → missingEdges（SPEC 有但 CODE_AST 缺失，待确认实现）</li>
 * </ol>
 * </p>
 * <p>
 * 健康检查：SPEC 类型的 ApiEndpoint 节点数 > 0 时视为可用（有 spec 才能对比）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenApiSpecVerificationAdapter implements ExternalVerificationAdapter {

    private static final String ADAPTER_NAME = "openapi-spec-verification";
    private static final String SOURCE_TOOL = "openapi";
    private static final String SPEC_SOURCE_TYPE = "SPEC";
    private static final String VERIFIED_BY_EDGE = "VERIFIED_BY";

    private static final double CONFIRMED_CONFIDENCE = 1.0;
    private static final double MISSING_CONFIDENCE = 0.85;
    private static final double SUSPICIOUS_CONFIDENCE = 0.5;

    private final Neo4jGraphDao neo4jGraphDao;

    @Value("${legacygraph.external-verification.openapi.enabled:true}")
    private boolean openapiEnabled;

    @Override
    public String adapterName() {
        return ADAPTER_NAME;
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean supports(ScanContext context) {
        return openapiEnabled && context != null;
    }

    @Override
    public boolean checkHealth() {
        // 无外部服务依赖，只要开关开启即健康
        return openapiEnabled;
    }

    @Override
    public VerificationResult verify(String projectId, String versionId, ScanContext context) {
        // 1. 读取 SPEC 和 CODE_AST 两种来源的 ApiEndpoint 节点
        List<GraphNode> specNodes = neo4jGraphDao.queryNodes(
                projectId, versionId,
                NodeType.ApiEndpoint.name(), SPEC_SOURCE_TYPE,
                null, null, 500);

        List<GraphNode> codeNodes = neo4jGraphDao.queryNodes(
                projectId, versionId,
                NodeType.ApiEndpoint.name(), SourceType.CODE_AST.name(),
                null, null, 2000);

        if (specNodes.isEmpty()) {
            log.info("OpenApiSpecVerification: no SPEC ApiEndpoint nodes found, skip (project={}, version={})",
                    projectId, versionId);
            return VerificationResult.empty(ADAPTER_NAME);
        }

        log.info("OpenApiSpecVerification: comparing {} SPEC nodes vs {} CODE_AST nodes (project={}, version={})",
                specNodes.size(), codeNodes.size(), projectId, versionId);

        // 2. 按 nodeKey（HTTP_METHOD + path）做 join
        Map<String, GraphNode> codeNodeMap = new HashMap<>();
        for (GraphNode node : codeNodes) {
            if (node.getNodeKey() != null) {
                codeNodeMap.put(node.getNodeKey(), node);
            }
        }

        List<VerifiedEdge> confirmedEdges = new ArrayList<>();
        List<VerifiedEdge> suspiciousEdges = new ArrayList<>();
        List<VerifiedEdge> missingEdges = new ArrayList<>();
        List<VerifiedNodeProperty> nodeProperties = new ArrayList<>();
        int totalChecked = 0;
        int totalConfirmed = 0;

        for (GraphNode specNode : specNodes) {
            String key = specNode.getNodeKey();
            if (key == null) continue;
            totalChecked++;

            GraphNode codeNode = codeNodeMap.get(key);
            if (codeNode == null) {
                // MISSING: SPEC 有但 CODE_AST 缺失
                missingEdges.add(VerifiedEdge.builder()
                        .fromNodeKey(key)
                        .toNodeKey(key)
                        .edgeType(VERIFIED_BY_EDGE)
                        .confidence(MISSING_CONFIDENCE)
                        .sourceTool(SOURCE_TOOL)
                        .build());
                continue;
            }

            // 检查属性是否漂移
            if (isDrifted(specNode, codeNode)) {
                // DRIFTED: 两端都有但属性不一致
                suspiciousEdges.add(VerifiedEdge.builder()
                        .fromNodeKey(key)
                        .toNodeKey(key)
                        .edgeType(VERIFIED_BY_EDGE)
                        .confidence(SUSPICIOUS_CONFIDENCE)
                        .sourceTool(SOURCE_TOOL)
                        .build());
                // 标记 CODE_AST 节点 suspicious=true
                nodeProperties.add(VerifiedNodeProperty.builder()
                        .nodeKey(codeNode.getNodeKey())
                        .propertyName("suspicious")
                        .propertyValue(true)
                        .sourceTool(SOURCE_TOOL)
                        .build());
            } else {
                // MATCHED: 确认一致
                confirmedEdges.add(VerifiedEdge.builder()
                        .fromNodeKey(key)
                        .toNodeKey(key)
                        .edgeType(VERIFIED_BY_EDGE)
                        .confidence(CONFIRMED_CONFIDENCE)
                        .sourceTool(SOURCE_TOOL)
                        .build());
                totalConfirmed++;
            }
        }

        // 3. 检查 EXTRA: CODE_AST 有但 SPEC 缺失（文档未覆盖的端点）
        Map<String, GraphNode> specNodeMap = new HashMap<>();
        for (GraphNode specNode : specNodes) {
            if (specNode.getNodeKey() != null) {
                specNodeMap.put(specNode.getNodeKey(), specNode);
            }
        }
        int extraCount = 0;
        for (GraphNode codeNode : codeNodes) {
            String key = codeNode.getNodeKey();
            if (key != null && !specNodeMap.containsKey(key)) {
                // EXTRA: CODE_AST 有但 SPEC 缺失 → 标记 undocumented
                nodeProperties.add(VerifiedNodeProperty.builder()
                        .nodeKey(key)
                        .propertyName("undocumented")
                        .propertyValue(true)
                        .sourceTool(SOURCE_TOOL)
                        .build());
                extraCount++;
            }
        }

        log.info("OpenApiSpecVerification completed: checked={}, confirmed={}, drifted={}, missing={}, extra={}",
                totalChecked, totalConfirmed, suspiciousEdges.size(), missingEdges.size(), extraCount);

        return VerificationResult.builder()
                .adapterName(ADAPTER_NAME)
                .confirmedEdges(confirmedEdges)
                .missingEdges(missingEdges)
                .suspiciousEdges(suspiciousEdges)
                .nodeProperties(nodeProperties)
                .totalChecked(totalChecked)
                .totalConfirmed(totalConfirmed)
                .build();
    }

    /**
     * 检查 SPEC 与 CODE_AST 两个 ApiEndpoint 节点的属性是否漂移。
     * <p>
     * 漂移判定：properties JSON 中关键属性（responseType / requestBody / params）存在差异。
     * </p>
     */
    private boolean isDrifted(GraphNode specNode, GraphNode codeNode) {
        String specProps = specNode.getProperties();
        String codeProps = codeNode.getProperties();
        if (specProps == null && codeProps == null) return false;
        if (specProps == null || codeProps == null) return true;

        // 简化判定：properties JSON 不相等视为漂移
        // 生产中可改为按字段逐项比对（responseType / requestBody / params）
        return !specProps.equals(codeProps);
    }
}
