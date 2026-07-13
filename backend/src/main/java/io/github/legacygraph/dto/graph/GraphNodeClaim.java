package io.github.legacygraph.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@NoArgsConstructor
@AllArgsConstructor
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

    /** 幂等键：同一意图重复提交不产生副作用（可选） */
    private String idempotencyKey;

    /** 扫描类型（CODE_SCAN / DATABASE_SCAN / DOC_SCAN / AI_SCAN） */
    private String scanType;

    /** 所属类的全限定名（用于代码节点） */
    private String className;

    /**
     * 别名列表（JSON 数组字符串，如 ["用户中心","yh","user"]）。
     * <p>业务域节点合并优化（graph-merge-optimization-plan.md 改进②）：用于跨源别名识别与 Blocking。
     * 为空时由 {@code EvidenceGraphWriter} 对业务域类型自动派生。</p>
     */
    private String aliasNames;

}
