package io.github.legacygraph.dto.requirement;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 需求条目与图谱节点链接结果（Task 7）。
 * <p>由 {@code RequirementLinkingService} 通过三步策略（显式引用 / 术语映射 / 向量语义）
 * 匹配出的目标图谱节点，用于创建 AFFECTS 边和影响子图提取。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LinkedTarget {

    /** 目标图谱节点 ID */
    private String nodeId;

    /** 目标节点 key */
    private String nodeKey;

    /** 目标节点名称 */
    private String nodeName;

    /** 目标节点类型（如 Table / Service / ApiEndpoint） */
    private String nodeType;

    /** 匹配类型：EXACT_REFERENCE / TERMINOLOGY / SEMANTIC */
    private String matchType;

    /** 匹配置信度 [0,1]，精确匹配为 1.0，语义匹配为相似度值 */
    private BigDecimal confidence;

    /** 状态：CONFIRMED（精确/术语匹配）/ PENDING_CONFIRM（语义匹配待确认） */
    private String status;

    /** 关联的需求条目编码（如 R1），用于回溯 AFFECTS 边的起点 */
    private String itemCode;

    /** 来源需求条目文本（用于追溯匹配上下文） */
    private String itemText;
}
