package io.github.legacygraph.agent;

import lombok.Data;

/**
 * 查询意图分类器
 */
public enum QueryIntent {
    FACT_LOOKUP,      // 事实查询："OrderService 有哪些方法？"
    STRUCTURAL,       // 结构查询："订单创建涉及哪些表？"
    RELATIONAL,       // 关系查询："A 和 B 有什么依赖关系？"
    COMPARATIVE,      // 对比查询："V1 和 V2 版本的差异？"
    TEMPORAL,         // 时序查询："这个接口是什么时候加的？"
    EXPLANATION,      // 解释查询："为什么这样设计？"
    CHANGE_IMPACT;    // 变更影响查询："加字段/改表需要怎么做？"

    /**
     * 根据意图类型推荐检索深度
     */
    public int getRecommendedGraphDepth() {
        return switch (this) {
            case FACT_LOOKUP -> 1;
            case STRUCTURAL -> 2;
            case RELATIONAL -> 3;
            case COMPARATIVE -> 2;
            case TEMPORAL -> 1;
            case EXPLANATION -> 2;
            case CHANGE_IMPACT -> 3;
        };
    }

    /**
     * 是否需要使用 GraphRagPlanner
     */
    public boolean requiresPlanner() {
        return this == STRUCTURAL || this == RELATIONAL;
    }

    /**
     * 是否走变更影响专用链路（ImpactSubgraphService + ChangeImpactAgent），
     * 而非通用 GraphRAG Planner。与 requiresPlanner() 互斥。
     */
    public boolean requiresChangeImpact() {
        return this == CHANGE_IMPACT;
    }
}
