package io.github.legacygraph.builder;

import io.github.legacygraph.common.NodeType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 节点属性模板 — 为五类核心节点提供 QA 检索必备属性清单。
 * <p>
 * 在 {@link GraphBuilder#findOrCreateNode} 创建节点时，
 * 先填充模板属性，再合并调用方传入的 additionalAttrs，确保节点属性不丢失。
 * </p>
 */
public final class NodeAttributeTemplates {

    private NodeAttributeTemplates() {}

    /** Controller 节点必备属性 */
    private static final Set<String> CONTROLLER_ATTRS = Set.of(
            "httpMethod", "pathPattern", "requiredRoles", "rateLimit");

    /** Service 节点必备属性 */
    private static final Set<String> SERVICE_ATTRS = Set.of(
            "transactionalBoundary", "cacheableAnnotations", "businessOwner");

    /** Mapper 节点必备属性 */
    private static final Set<String> MAPPER_ATTRS = Set.of(
            "mappedStatementId", "sqlType", "parameterObject");

    /** ApiEndpoint 节点必备属性 */
    private static final Set<String> API_ENDPOINT_ATTRS = Set.of(
            "requestSchema", "responseSchema", "errorCodes");

    /** Table 节点必备属性 */
    private static final Set<String> TABLE_ATTRS = Set.of(
            "domainTag", "complianceLevel", "piiColumns");

    /**
     * 返回指定节点类型的必备属性模板（值为 null，待回填）。
     *
     * @param type 节点类型
     * @return 属性 Map；非核心节点类型返回空 Map
     */
    public static Map<String, Object> requiredFor(NodeType type) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (type == null) return attrs;

        switch (type) {
            case Controller -> CONTROLLER_ATTRS.forEach(k -> attrs.put(k, null));
            case Service -> SERVICE_ATTRS.forEach(k -> attrs.put(k, null));
            case Mapper -> MAPPER_ATTRS.forEach(k -> attrs.put(k, null));
            case ApiEndpoint -> API_ENDPOINT_ATTRS.forEach(k -> attrs.put(k, null));
            case Table -> TABLE_ATTRS.forEach(k -> attrs.put(k, null));
            default -> { /* 非核心节点类型，无模板 */ }
        }
        return attrs;
    }

    /**
     * 返回指定节点类型名称的必备属性模板。
     *
     * @param nodeType 节点类型名称（与 {@link NodeType#name()} 对齐）
     * @return 属性 Map
     */
    public static Map<String, Object> requiredFor(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) return new LinkedHashMap<>();
        try {
            return requiredFor(NodeType.valueOf(nodeType));
        } catch (IllegalArgumentException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 判断指定节点类型是否有必备属性模板。
     */
    public static boolean hasTemplate(NodeType type) {
        return type == NodeType.Controller || type == NodeType.Service
                || type == NodeType.Mapper || type == NodeType.ApiEndpoint
                || type == NodeType.Table;
    }
}
