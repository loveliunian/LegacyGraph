package io.github.legacygraph.dto.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 混合 RAG 检索结果项 — 含多维度评分。
 * <p>
 * 排序公式（见 doc/架构与三类图谱AI优化建议.md 4.1）：
 * </p>
 * <pre>
 * final_score =
 *   0.35 * vector_score
 *   + 0.25 * graph_proximity_score
 *   + 0.20 * source_priority_score
 *   + 0.10 * freshness_score
 *   + 0.10 * runtime_verified_score
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridRagResult {

    /** 结果唯一标识 */
    private String resultId;

    /** 结果类型：node / edge / evidence / document_chunk / trace_path */
    private String resultType;

    /** 标题/名称 */
    private String title;

    /** 内容摘要 */
    private String content;

    /** 来源路径 */
    private String sourcePath;

    /** 来源类型优先级映射分数 */
    private Double sourcePriorityScore;

    /** 向量相似度分 */
    private Double vectorScore;

    /** 图谱邻近度分 */
    private Double graphProximityScore;

    /** 新鲜度分 */
    private Double freshnessScore;

    /** 运行时验证分 */
    private Double runtimeVerifiedScore;

    /** 综合加权分 */
    private Double finalScore;

    /** 证据列表 */
    private String evidenceSummary;

    /** 证据来源类型（code/db/runtime/test/doc/ai） */
    private String evidenceSource;



    // ========== 评分权重常量 ==========

    public static final double W_VECTOR = 0.35;
    public static final double W_GRAPH = 0.25;
    public static final double W_SOURCE = 0.20;
    public static final double W_FRESHNESS = 0.10;
    public static final double W_RUNTIME = 0.10;

    // ========== 来源优先级 ==========

    /**
     * 来源优先级映射（越高越可靠）。
     * runtime > code > db > test > doc > ai
     */
    public static double sourcePriority(String sourceType) {
        if (sourceType == null) return 0.3;
        return switch (sourceType.toLowerCase()) {
            case "runtime", "runtime_trace" -> 1.0;
            case "code", "code_ast" -> 0.9;
            case "db", "db_metadata", "sql_parse" -> 0.8;
            case "test", "test_execution" -> 0.7;
            case "doc", "document", "doc_ai" -> 0.5;
            case "ai", "ai_inference" -> 0.3;
            default -> 0.4;
        };
    }
}
