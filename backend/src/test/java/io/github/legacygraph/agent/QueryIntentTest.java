package io.github.legacygraph.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link QueryIntent} 单元测试 — 验证 CHANGE_IMPACT 意图与 GraphRAG 互斥。
 */
class QueryIntentTest {

    @Test
    void changeImpact_doesNotRequirePlanner() {
        assertFalse(QueryIntent.CHANGE_IMPACT.requiresPlanner(),
                "CHANGE_IMPACT 走变更影响专用链路，不触发 GraphRAG Planner");
    }

    @Test
    void changeImpact_requiresChangeImpact() {
        assertTrue(QueryIntent.CHANGE_IMPACT.requiresChangeImpact());
    }

    @Test
    void otherIntents_doNotRequireChangeImpact() {
        assertFalse(QueryIntent.FACT_LOOKUP.requiresChangeImpact());
        assertFalse(QueryIntent.STRUCTURAL.requiresChangeImpact());
        assertFalse(QueryIntent.RELATIONAL.requiresChangeImpact());
        assertFalse(QueryIntent.EXPLANATION.requiresChangeImpact());
    }

    @Test
    void changeImpact_depth3() {
        assertEquals(3, QueryIntent.CHANGE_IMPACT.getRecommendedGraphDepth());
    }

    @Test
    void structuralAndRelational_requirePlanner() {
        assertTrue(QueryIntent.STRUCTURAL.requiresPlanner());
        assertTrue(QueryIntent.RELATIONAL.requiresPlanner());
    }
}
