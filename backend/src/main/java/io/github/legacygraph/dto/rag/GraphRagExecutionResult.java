package io.github.legacygraph.dto.rag;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * GraphRAG 执行结果 — GraphRagPlanExecutor 返回的完整结果。
 */
@Data
@Builder
public class GraphRagExecutionResult {

    /** 执行的 Claim 查询结果 */
    @Builder.Default
    private List<GraphRagEvidenceCard> claimResults = new ArrayList<>();

    /** 执行的路径查询结果 */
    @Builder.Default
    private List<GraphRagEvidenceCard> pathResults = new ArrayList<>();

    /** 证据查询结果 */
    @Builder.Default
    private List<GraphRagEvidenceCard> evidenceResults = new ArrayList<>();

    /** 由于证据不足转为 GapTask 的子问题列表 */
    @Builder.Default
    private List<String> gapSubQueries = new ArrayList<>();

    /** 收集到的所有证据卡片 */
    @Builder.Default
    private List<GraphRagEvidenceCard> allCards = new ArrayList<>();
}
