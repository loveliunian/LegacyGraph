package io.github.legacygraph.dto.rag;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * GraphRAG 证据卡片 — 每次查询返回的证据单元。
 * AI 输出报告时只能引用 EvidenceCard 中的事实。
 */
@Data
@Builder
public class GraphRagEvidenceCard {

    private String evidenceId;

    /** 来源类型：CODE / DOC / DB / TEST / RUNTIME */
    private String sourceType;

    /** 来源路径 */
    private String sourcePath;

    /** 起始行号 */
    private Integer startLine;

    /** 结束行号 */
    private Integer endLine;

    /** 关联节点 key */
    private String nodeKey;

    /** 关联 Claim ID */
    private String claimId;

    /** 内容摘录 */
    private String excerpt;

    /** 置信度 */
    private BigDecimal confidence;

    /** 状态 */
    private String status;
}
