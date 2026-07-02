package io.github.legacygraph.dto.graph;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 图谱边声明（Claim）。
 * <p>
 * 调用方（Builder/AI Agent）通过此 DTO 声明"两个节点之间应当存在一条边"，
 * 由 {@code EvidenceGraphWriter} 处理去重、置信度裁决和证据继承。
 * </p>
 *
 * @see io.github.legacygraph.builder.EvidenceGraphWriter#upsertEdge(GraphEdgeClaim)
 */
@Data
@Builder
public class GraphEdgeClaim {

    /** 项目ID（必填） */
    private String projectId;

    /** 扫描版本ID（必填） */
    private String versionId;

    /** 源节点ID（必填） */
    private String fromNodeId;

    /** 目标节点ID（必填） */
    private String toNodeId;

    /** 边类型（必填，如 CONTAINS / CALLS / HANDLED_BY 等） */
    private String edgeType;

    /** 边唯一键（必填，用于去重） */
    private String edgeKey;

    /** 来源类型（如 CODE_AST / AI_INFERENCE / RUNTIME_TRACE） */
    private String sourceType;

    /** 置信度（0-1） */
    private BigDecimal confidence;

    /** 调用方期望状态，最终仍由 EvidenceGraphWriter 做策略裁决 */
    private String status;

    /** 额外属性（JSON 字符串） */
    private String properties;

    /** 幂等键：同一意图重复提交不产生副作用（可选） */
    private String idempotencyKey;

    public GraphEdgeClaim() {}

    public GraphEdgeClaim(String projectId, String versionId, String fromNodeId,
                          String toNodeId, String edgeType, String edgeKey,
                          String sourceType, BigDecimal confidence, String status,
                          String properties, String idempotencyKey) {
        this.projectId = projectId;
        this.versionId = versionId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.edgeType = edgeType;
        this.edgeKey = edgeKey;
        this.sourceType = sourceType;
        this.confidence = confidence;
        this.status = status;
        this.properties = properties;
        this.idempotencyKey = idempotencyKey;
    }
}
