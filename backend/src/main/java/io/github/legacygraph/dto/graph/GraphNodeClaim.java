package io.github.legacygraph.dto.graph;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 图谱节点声明（Claim）。
 * <p>
 * 调用方（Builder/AI Agent）通过此 DTO 声明"应当存在一个节点"，由
 * {@code EvidenceGraphWriter} 根据 sourceType、confidence、evidenceCount、
 * runtimeVerified 等规则决定最终状态（CONFIRMED / PENDING_CONFIRM）。
 * </p>
 *
 * @see io.github.legacygraph.builder.EvidenceGraphWriter#upsertNode(GraphNodeClaim)
 */
@Data
@Builder
public class GraphNodeClaim {

    /** 项目ID（必填） */
    private String projectId;

    /** 扫描版本ID（必填） */
    private String versionId;

    /** 节点类型（必填，如 Controller/ApiEndpoint/Table 等） */
    private String nodeType;

    /** 节点唯一键（必填，用于去重） */
    private String nodeKey;

    /** 节点名称 */
    private String nodeName;

    /** 显示名称 */
    private String displayName;

    /** 描述 */
    private String description;

    /** 来源类型（如 CODE_AST / DB_METADATA / AI_INFERENCE） */
    private String sourceType;

    /** 来源路径 */
    private String sourcePath;

    /** 起始行号 */
    private Integer startLine;

    /** 结束行号 */
    private Integer endLine;

    /** 置信度（0-1） */
    private BigDecimal confidence;

    /** 调用方期望状态，最终仍由 EvidenceGraphWriter 做策略裁决 */
    private String status;

    /** 额外属性（JSON 字符串） */
    private String properties;

    /**
     * 创建无参构造（Lombok @Builder 需要）。
     * 同时提供全参构造供 Builder 模式使用。
     */
    public GraphNodeClaim() {}

    public GraphNodeClaim(String projectId, String versionId, String nodeType,
                          String nodeKey, String nodeName, String displayName,
                          String description, String sourceType, String sourcePath,
                          Integer startLine, Integer endLine, BigDecimal confidence,
                          String status, String properties) {
        this.projectId = projectId;
        this.versionId = versionId;
        this.nodeType = nodeType;
        this.nodeKey = nodeKey;
        this.nodeName = nodeName;
        this.displayName = displayName;
        this.description = description;
        this.sourceType = sourceType;
        this.sourcePath = sourcePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.confidence = confidence;
        this.status = status;
        this.properties = properties;
    }
}
