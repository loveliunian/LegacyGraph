package io.github.legacygraph.service.qa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * 检索意图路由器 — 基于关键词对查询进行意图分类，并返回各召回方式的权重矩阵。
 *
 * <p>意图权重矩阵（方案文档 G-07）：
 * <pre>
 * | Intent                    | 关键词 | 向量 | 图节点 | Claim | 项目约定 |
 * | REQUIREMENT_UNDERSTANDING | 0.20  | 0.35 | 0.15  | 0.25  | 0.05    |
 * | CHANGE_IMPACT             | 0.30  | 0.20 | 0.25  | 0.10  | 0.15    |
 * | SOLUTION_DESIGN           | 0.15  | 0.20 | 0.20  | 0.15  | 0.30    |
 * | CODE_EXPLANATION          | 0.25  | 0.30 | 0.25  | 0.10  | 0.10    |
 * | ARCHITECTURE_OVERVIEW     | 0.05  | 0.20 | 0.30  | 0.15  | 0.30    |
 * | DATA_LINEAGE              | 0.30  | 0.15 | 0.40  | 0.10  | 0.05    |
 * </pre>
 *
 * <p>权重 Map 的键约定：
 * <ul>
 *   <li>{@link #KEY_KEYWORD}  - 关键词召回</li>
 *   <li>{@link #KEY_VECTOR}   - 向量召回</li>
 *   <li>{@link #KEY_GRAPH}    - 图节点召回</li>
 *   <li>{@link #KEY_CLAIM}   - Claim 召回</li>
 *   <li>{@link #KEY_CONVENTION} - 项目约定召回</li>
 * </ul>
 */
@Slf4j
@Service
public class RetrievalIntentRouter {

    /** 权重 Map 键：关键词召回 */
    public static final String KEY_KEYWORD = "keyword";
    /** 权重 Map 键：向量召回 */
    public static final String KEY_VECTOR = "vector";
    /** 权重 Map 键：图节点召回 */
    public static final String KEY_GRAPH = "graph";
    /** 权重 Map 键：Claim 召回 */
    public static final String KEY_CLAIM = "claim";
    /** 权重 Map 键：项目约定召回 */
    public static final String KEY_CONVENTION = "convention";

    /**
     * 检索意图枚举 — 对应不同检索场景下的召回权重配置。
     *
     * <p>注意：此枚举与 {@code io.github.legacygraph.agent.QueryIntent} 不同，
     * 后者用于 LLM 驱动的查询路由分类（FACT_LOOKUP/STRUCTURAL 等）；
     * 本枚举专门用于检索阶段的召回权重调整。</p>
     */
    public enum QueryIntent {
        /** 需求理解：理解用户原始诉求 */
        REQUIREMENT_UNDERSTANDING,
        /** 变更影响：分析变更/改动的影响范围 */
        CHANGE_IMPACT,
        /** 方案设计：评估或设计技术方案 */
        SOLUTION_DESIGN,
        /** 代码解释：解释代码实现/方法逻辑 */
        CODE_EXPLANATION,
        /** 架构概览：整体架构与结构概览 */
        ARCHITECTURE_OVERVIEW,
        /** 数据血缘：追踪数据表/字段的流向 */
        DATA_LINEAGE
    }

    /**
     * 基于关键词对查询进行意图分类。
     *
     * <p>分类逻辑（按优先级顺序匹配，命中即返回）：
     * <ul>
     *   <li>{@link QueryIntent#CHANGE_IMPACT}：包含"影响"、"变更"、"impact"、"change"</li>
     *   <li>{@link QueryIntent#SOLUTION_DESIGN}：包含"方案"、"设计"、"solution"、"design"</li>
     *   <li>{@link QueryIntent#CODE_EXPLANATION}：包含"代码"、"实现"、"code"、"function"、"method"</li>
     *   <li>{@link QueryIntent#ARCHITECTURE_OVERVIEW}：包含"架构"、"结构"、"architecture"、"overview"</li>
     *   <li>{@link QueryIntent#DATA_LINEAGE}：包含"数据"、"表"、"字段"、"data"、"table"、"lineage"</li>
     *   <li>默认：{@link QueryIntent#REQUIREMENT_UNDERSTANDING}</li>
     * </ul>
     *
     * @param query 用户查询文本（null 或空时返回默认意图）
     * @return 分类得到的查询意图
     */
    public QueryIntent classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryIntent.REQUIREMENT_UNDERSTANDING;
        }
        // 统一小写后做包含匹配，兼容中英文混合查询
        String lower = query.toLowerCase(Locale.ROOT);

        // 按优先级顺序匹配：变更影响 → 方案设计 → 代码解释 → 架构概览 → 数据血缘
        if (containsAny(lower, "影响", "变更", "impact", "change")) {
            return QueryIntent.CHANGE_IMPACT;
        }
        if (containsAny(lower, "方案", "设计", "solution", "design")) {
            return QueryIntent.SOLUTION_DESIGN;
        }
        if (containsAny(lower, "代码", "实现", "code", "function", "method")) {
            return QueryIntent.CODE_EXPLANATION;
        }
        if (containsAny(lower, "架构", "结构", "architecture", "overview")) {
            return QueryIntent.ARCHITECTURE_OVERVIEW;
        }
        if (containsAny(lower, "数据", "表", "字段", "data", "table", "lineage")) {
            return QueryIntent.DATA_LINEAGE;
        }
        return QueryIntent.REQUIREMENT_UNDERSTANDING;
    }

    /**
     * 返回指定意图下各召回方式的权重。
     *
     * <p>返回的 Map 为不可变 Map，键为召回方式标识
     * （{@link #KEY_KEYWORD} / {@link #KEY_VECTOR} / {@link #KEY_GRAPH} /
     * {@link #KEY_CLAIM} / {@link #KEY_CONVENTION}），值为对应权重。
     * 各意图的权重之和均为 1.0。</p>
     *
     * @param intent 查询意图（null 时使用默认意图 REQUIREMENT_UNDERSTANDING）
     * @return 召回方式 → 权重 的不可变映射
     */
    public Map<String, Double> getWeights(QueryIntent intent) {
        if (intent == null) {
            intent = QueryIntent.REQUIREMENT_UNDERSTANDING;
        }
        return switch (intent) {
            case REQUIREMENT_UNDERSTANDING -> Map.of(
                KEY_KEYWORD, 0.20, KEY_VECTOR, 0.35, KEY_GRAPH, 0.15,
                KEY_CLAIM, 0.25, KEY_CONVENTION, 0.05);
            case CHANGE_IMPACT -> Map.of(
                KEY_KEYWORD, 0.30, KEY_VECTOR, 0.20, KEY_GRAPH, 0.25,
                KEY_CLAIM, 0.10, KEY_CONVENTION, 0.15);
            case SOLUTION_DESIGN -> Map.of(
                KEY_KEYWORD, 0.15, KEY_VECTOR, 0.20, KEY_GRAPH, 0.20,
                KEY_CLAIM, 0.15, KEY_CONVENTION, 0.30);
            case CODE_EXPLANATION -> Map.of(
                KEY_KEYWORD, 0.25, KEY_VECTOR, 0.30, KEY_GRAPH, 0.25,
                KEY_CLAIM, 0.10, KEY_CONVENTION, 0.10);
            case ARCHITECTURE_OVERVIEW -> Map.of(
                KEY_KEYWORD, 0.05, KEY_VECTOR, 0.20, KEY_GRAPH, 0.30,
                KEY_CLAIM, 0.15, KEY_CONVENTION, 0.30);
            case DATA_LINEAGE -> Map.of(
                KEY_KEYWORD, 0.30, KEY_VECTOR, 0.15, KEY_GRAPH, 0.40,
                KEY_CLAIM, 0.10, KEY_CONVENTION, 0.05);
        };
    }

    /**
     * 判断 lower 文本是否包含任一关键词（大小写已归一化）。
     */
    private boolean containsAny(String lower, String... keywords) {
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
