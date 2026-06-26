package io.github.legacygraph.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 图谱合并决策 - 对应文档中的 GraphMergeDecision JSON Schema
 */
@Data
public class GraphMergeDecision {

    private String candidateA;
    private String candidateB;
    private Decision decision;
    private BigDecimal score;
    private List<String> reasons;
    private List<String> positiveEvidenceIds;
    private List<String> negativeEvidenceIds;

    public enum Decision {
        AUTO_MERGE,
        REVIEW,
        REJECT
    }
}
